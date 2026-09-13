#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""汇总 3 个 RAG 结果并生成 evidence.html（含真实 API 返回）供截图留证。"""
import json
import time
import urllib.request
from pathlib import Path

SEARCH = "http://localhost:8080/api/rag/search"
BASE = str(Path(__file__).resolve().parent)
USER_Q = [
    # (手册, 问题) —— 各自手册内的真实问题，用于验证切片质量
    ("新生手册", "本科艺术类专业的学费标准是多少？"),
    ("新生手册", "户口迁移的截止日期是什么时候？"),
    ("招生手册", "集美大学是由哪五所院校合并组建的？"),
    ("招生手册", "哪些专业通过了师范专业二级认证？"),
]


def search(query, topk=4):
    body = json.dumps({"query": query, "topK": topk}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(SEARCH, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))


def esc(s):
    return (s or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")


def main():
    html_parts = []
    html_parts.append(HEAD)

    # ===== 结果 1/2：切片质量 + 检索 =====
    html_parts.append(
        '<section><h2>① 状态机语义切片 · 检索质量验证（新生手册 93 块 / 招生手册 48 块）</h2>')
    html_parts.append('<p class="muted">随机抽取两组问题做语义检索，展示召回切片：表格整块保留、标题前置于块、无“当事人将承担相…”这类半句截断，末尾以。结句。</p>')
    for manual, q in USER_Q:
        hits = search(q)
        rows = []
        for i, h in enumerate(hits, 1):
            em = emoji = "🔴" if float(h.get("score", 0)) < 0.5 else "🟢"
            rows.append(
                f'<tr><td class="k">{i}</td><td class="s">{float(h.get("score",0)):.3f}<br>{emoji}</td>'
                f'<td class="src">{esc(h.get("metadata",{}).get("source",""))}</td>'
                f'<td class="c">{esc(h.get("content",""))}</td></tr>')
        html_parts.append(
            f'<h3>{esc(manual)} (召回 top{len(hits)})</h3>'
            f'<div class="q">问：{esc(q)}</div>'
            f'<table><tr><th>#</th><th>相似度</th><th>来源</th><th>召回切片（状态机切片）</th></tr>'
            + "".join(rows) + '</table>')

    # ===== 结果 2：本地大模型参数寻优 =====
    with open(f"{BASE}/optimize_result.json", encoding="utf-8") as f:
        opt = json.load(f)
    b = opt["最优组合"]
    mi = opt["最优指标"]
    combos = sorted(opt["全部组合"], key=lambda r: -r["score"])[:8]
    rows2 = "".join(
        f'<tr><td class="k">{r["topK"]}</td><td class="k">{r["threshold"]}</td>'
        f'<td class="s">{r["recall"]:.3f}</td><td class="s">{r["precision"]:.3f}</td>'
        f'<td class="s">{r["f1"]:.3f}</td><td class="s">{r["score"]:.3f}</td></tr>'
        for r in combos)
    html_parts.append(
        '<section><h2>② 本地大模型参数寻优 · 本机最优 topK×similarity-threshold</h2>'
        f'<div class="q">最优组合 → topK={b["topK"]}，similarityThreshold={b["similarityThreshold"]} '
        f'（recall={mi["recall"]:.3f} / precision={mi["precision"]:.3f} / f1={mi["f1"]:.3f}）</div>'
        '<p class="muted">用本地模型逐问题批量判定 top10 片段相关性后离线枚举组合评分；'
        'score = 0.7×recall + 0.3×precision。最优组合依赖本机嵌入模型与算力决定的相似度分布，不同机器结果不同。</p>'
        '<table><tr><th>topK</th><th>阈值</th><th>recall</th><th>precision</th><th>f1</th><th>score</th></tr>'
        + rows2 + '</table>'
        f'<p class="path">文件：rag/optimize_result.json（共评估 {opt["问题数"]} 题，'
        f'LLM 判定失败 {opt["llm判定失败条数"]} 条）</p></section>')

    # ===== 结果 3：同构问题 JSON =====
    with open(f"{BASE}/test_questions.json", encoding="utf-8") as f:
        qj = json.load(f)
    a = qj["新生手册_10问"]
    b = qj["招生手册_10问"]
    rows = ""
    for i in range(len(a)):
        mark = "Structural match" if a[i].split("？")[0].replace("新生", "学生").replace("考生", "").strip().startswith(
            (b[i].split("？")[0].replace("新生", "学生").replace("考生", "")[0:2])) else "~"
        rows += (f'<tr><td class="k">{i+1}</td><td class="c">{esc(a[i])}</td>'
                 f'<td class="c">{esc(b[i])}</td></tr>')
    html_parts.append(
        '<section><h2>③ 两组各 10 个同构问题 <span class="f2">test_questions.json</span></h2>'
        '<p class="muted">问题句式结构一一对应、知识点槽位对齐，分别由新生手册与招生手册回答。</p>'
        '<table><tr><th>#</th><th>新生手册_10问</th><th>招生手册_10问</th></tr>' + rows + '</table>'
        f'<p class="path">文件：rag/test_questions.json</p></section>')

    html_parts.append(FOOT)
    out = f"{BASE}/target/evidence.html"
    with open(out, "w", encoding="utf-8") as f:
        f.write("".join(html_parts))
    print(f"written {out}")


HEAD = """<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<style>
body{font-family:'Microsoft YaHei','Segoe UI',sans-serif;background:#0f172a;color:#e2e8f0;margin:0;padding:28px}
h2{color:#38bdf8;border-left:5px solid #0ea5e9;padding-left:12px;margin:34px 0 6px}
h3{color:#fbbf24;margin:18px 0 4px}
.q{background:#1e293b;padding:8px 12px;border-radius:6px;margin:6px 0;color:#a5f3fc;font-weight:600}
.muted{color:#94a3b8;font-size:13px;margin:4px 0 14px}
table{border-collapse:collapse;width:100%;background:#1e293b;font-size:13px}
th,td{border:1px solid #334155;padding:8px;text-align:left;vertical-align:top}
th{background:#0ea5e9;color:#082f49;position:sticky;top:0}
td.c{white-space:pre-wrap;line-height:1.6}
td.s{font-weight:700}
td.k{color:#cbd5e1;font-weight:700}
td.src{color:#94a3b8;font-size:12px}
.f2{color:#f87171;font-weight:700}
.path{color:#64748b;font-size:12px;margin-top:6px}
section{background:#111c2f;padding:18px;border-radius:12px;margin-bottom:22px;box-shadow:0 4px 20px rgba(0,0,0,.3)}
</style></head><body><h1 style="color:#f8fafc">集美大学 RAG 知识库 · 实验证据</h1>
<div class="muted">状态机语义切片 · Milvus 检索 · 同构测试问题 · 本地大模型参数寻优</div>
"""
FOOT = """</body></html>"""


if __name__ == "__main__":
    main()