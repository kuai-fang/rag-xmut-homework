#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
在本机上用本地大模型找出 top-K 与 similarity-threshold 的最佳组合。

方法：
1. 对每个测试问题，向 RAG 检索接口请求 topK=10、threshold=0 的完整排序命中（带相似度分数）。
2. 把该问题命中的 top10 片段合并为一个 prompt，让本地大模型一次性判定每个片段是否真正回答了该问题（返回 0/1 数组）——把 10 次调用压缩为 1 次。
3. 汇总全部问题的片段相关性后，离线枚举所有 (topK, threshold) 组合：
   - recall  = 命中片段(score>=threshold 且取前 topK)中包含至少一个"回答该问题"片段的题目占比
   - precision= 阈值过滤后仍被判定为有用片段占返回片段的比例（均值）
   - 目标函数 f = recall - beta * (1 - normalized_avg_precision) 兼顾召回与噪声
4. 输出各组合成绩与最优组合。注意：硬件不同（CPU/GPU/内存/EMB 模型）每条召回斜率不同，最优组合因机器而异。
"""
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

BASE = str(Path(__file__).resolve().parent)

SEARCH_URL = "http://localhost:8080/api/rag/search"
LLM_URL = "http://localhost:1234/v1/chat/completions"
LLM_MODEL = "google/gemma-4-e4b"

# 候选网格（bge-m3 的 COSINE 相似度整体偏低约 0.55~0.75，网格按要求适配本机尺度）
TOPKS = [3, 5, 8, 10]
THRESHOLDS = [0.0, 0.5, 0.55, 0.6, 0.65, 0.7, 0.75]

# 每组手册挑若干隐含"事实型"问题，覆盖不同段落（可传 --question-file 改为全量）
QUESTIONS = [
    # 新生手册
    "集美大学的校训是什么？学校属于什么办学性质？",
    "本科艺术类专业的学费标准是多少？",
    "新生报到时间和地点是什么？",
    "家庭经济困难的新生有哪些资助政策？",
    "学生宿舍的住宿条件怎么样？",
    "户口的迁移截止日期是什么时候？",
    # 招生手册
    "集美大学是由哪五所院校合并组建的？",
    "集美大学有多少个本科专业，国家级一流本科专业建设点有几个？",
    "艺术类本科专业的学费标准是多少？",
    "学校宿舍的住宿条件怎么样？",
    "招生办公室的联系电话是什么？",
    "哪些专业通过了师范专业二级认证？",
]

JUDGE_SYSTEM = (
    "你是检索质量评估器。我会给你一个用户问题，以及编号为[1]到[N]的若干知识片段。"
    "请挑选【确实包含回答该问题所需信息】的片段编号，输出成一行，编号之间用空格分隔。"
    "若没有片段有用，输出0。除此之外不要输出任何文字。"
)


def http_json(url, payload, timeout=180):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def search_full(query, topk, threshold):
    """调用 RAG 检索（带重试），返回 (list_of_score, list_of_chunk_dict)。"""
    payload = {"query": query, "topK": topk, "similarityThreshold": threshold}
    timeout = 60
    for attempt in range(3):
        try:
            resp = http_json(SEARCH_URL, payload, timeout=timeout)
            items = resp.get("data", resp) if isinstance(resp, dict) else resp
            items = items if isinstance(items, list) else []
            return items
        except Exception as e:
            last = e
            timeout += 30
            time.sleep(2)
    return []


def judge_relevance(question, chunks):
    import re
    if not chunks:
        return []
    lines = []
    for i, c in enumerate(chunks):
        text = c["content"].replace("\n", " ")[:150]
        text = " | ".join(t for t in text.split(" ") if t)[:170]
        lines.append(f"[{i+1}] {text}")
    prompt = (
        f"问题：{question}\n\n知识片段：\n" + "\n".join(lines) +
        "\n\n请输出相关片段编号："
    )
    body = {
        "model": LLM_MODEL,
        "messages": [
            {"role": "system", "content": JUDGE_SYSTEM},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.0,
        # gemma-4-e4b 是推理模型，会先写 reasoning_content 再输出正式答案。
        # token 预算必须足够大，否则推理阶段把预算耗光导致 content 为空。
        "max_tokens": 1024,
    }
    last_err = "unknown"
    text = ""
    for attempt in range(3):
        try:
            r = http_json(LLM_URL, body, timeout=900)
            msg = r["choices"][0]["message"]
            ch = (msg.get("content") or "").strip()
            # 兜底：若模型只吐出推理过程，仍尝试从 reasoning_content 抽取编号
            if not ch:
                ch = (msg.get("reasoning_content") or "").strip()
            text = ch
            break
        except Exception as e:
            last_err = str(e)
            time.sleep(2)
    if not text:
        return None, f"llm retry exhausted: {last_err}"
    # 解析输出的相关编号(1..len(chunks))，得到长度为 N 的 0/1 掩码
    ids = [int(t) for t in re.findall(r"\d+", text)]
    bits = [0] * len(chunks)
    for x in ids:
        if 1 <= x <= len(chunks):
            bits[x - 1] = 1
    return bits, text


def main():
    args = sys.argv[1:]
    use_all = "--all" in args
    result_file = args[0] if args and not args[0].startswith("--") else \
        f"{BASE}/optimize_result.json"

    global QUESTIONS
    if use_all:
        json_path = f"{BASE}/test_questions.json"
        with open(json_path, encoding="utf-8") as f:
            data = json.load(f)
        QUESTIONS = data["新生手册_10问"] + data["招生手册_10问"]
        print(f"[INFO] 使用全量 {len(QUESTIONS)} 个同构问题（来自 test_questions.json）")

    # 1) 每个问题检索一次 top10（threshold=0，取完整排序），结果实时写缓存支持断点续跑
    cache_file = f"{BASE}/optimize_cache.json"
    per_q = {}   # q -> list of (score, relevance_bit)
    if os.path.exists(cache_file):
        with open(cache_file, encoding="utf-8") as f:
            per_q = json.load(f)
        print(f"[INFO] 从缓存载入 {len(per_q)} 个已完成问题，将跳过它们")
    llm_fail = 0
    for i, q in enumerate(QUESTIONS, 1):
        if q in per_q:
            print(f"[{i}/{len(QUESTIONS)}] 跳过(缓存)：{q[:24]}...")
            continue
        items = search_full(q, 10, 0)   # 一次检索取完整排序 top10
        scores = [float(it.get("score", 0.0)) for it in items]
        bits, raw = judge_relevance(q, items)
        if bits is None or len(bits) != len(items):
            print(f"[WARN] 问题{i} LLM判定失败，回退全1  raw={raw}")
            llm_fail += 1
            bits = [1] * len(items)
        per_q[q] = [[s, int(b)] for s, b in zip(scores, bits)]
        with open(cache_file, "w", encoding="utf-8") as f:
            json.dump(per_q, f, ensure_ascii=False, indent=1)
        print(f"[{i}/{len(QUESTIONS)}] {q[:24]}...  top10(有用数={sum(bits)})  scores={[round(s,3) for s in scores]}", flush=True)
        time.sleep(1)

    # 2) 枚举组合
    def metrics(combo):
        k, t = combo
        recall_num = 0
        prec_sum = 0
        n = len(per_q)
        for q, sbs in per_q.items():
            kept = [(s, b) for (s, b) in sbs if s >= t][:k]
            hits = [b for (s, b) in kept]
            if sum(hits) > 0:
                recall_num += 1
            prec_sum += (sum(hits) / len(hits)) if hits else 0.0
        recall = recall_num / n
        precision = prec_sum / n
        f1 = (2 * recall * precision / (recall + precision)) if (recall + precision) else 0.0
        return {"recall": recall, "precision": precision, "f1": f1}

    rows = []
    for k in TOPKS:
        for t in THRESHOLDS:
            m = metrics((k, t))
            m["topK"] = k
            m["threshold"] = t
            m["score"] = round(m["recall"] * 0.7 + m["precision"] * 0.3, 4)
            rows.append(m)

    rows.sort(key=lambda r: (-r["recall"], -r["precision"], r["topK"], -r["threshold"]))
    best = rows[0]

    out = {
        "说明": "使用本地大模型(优雅 latency~18s/次)逐问题批量判定 top10 片段相关性，再离线枚举组合评分。recall=至少一条有用片段命中；precision=返回片段中有用的比例；score=0.7*recall+0.3*precision。",
        "硬件提示": "最优组合依赖本机嵌入模型/算力决定的相似度分布，不同机器结果可能不同。",
        "llm判定失败条数": llm_fail,
        "最优组合": {"topK": best["topK"], "similarityThreshold": best["threshold"]},
        "最优指标": {"recall": best["recall"], "precision": best["precision"], "f1": best["f1"], "score": best["score"]},
        "全部组合": rows,
        "问题数": len(QUESTIONS),
    }

    with open(result_file, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("\n======== 最优组合 ========")
    print(json.dumps({k: v for k, v in out["最优组合"].items()}, ensure_ascii=False))
    print("recall=%.3f precision=%.3f f1=%.3f score=%.3f" % (
        best["recall"], best["precision"], best["f1"], best["score"]))
    print("全部组合:")
    print("{:>5} {:>8} {:>8} {:>9} {:>9}".format("topK", "thr", "recall", "prec", "score"))
    for r in rows:
        print("{:>5} {:>8} {:>8.3f} {:>9.3f} {:>9.3f}".format(
            r["topK"], r["threshold"], r["recall"], r["precision"], r["score"]))
    print(f"\n结果已写入 {result_file}")


if __name__ == "__main__":
    main()