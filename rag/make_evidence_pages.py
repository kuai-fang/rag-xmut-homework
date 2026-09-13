#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成两份路径无关的独立证据页（便于整页截图）：
1. evidence_page_slice.html —— ① 状态机切片·检索质量（真实 API 返回，按行显示相对来源文件名）
2. evidence_page_json.html  —— ③ 两组各 10 个同构问题对照
页面内只用相对文件名（rag/docs/jmu*.txt），不出现本机绝对路径。
"""
import json
import urllib.request
from pathlib import Path

BASE = str(Path(__file__).resolve().parent)
SEARCH = "http://localhost:8080/api/rag/search"

HEAD = """<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<style>
body{font-family:'Microsoft YaHei','Segoe UI',sans-serif;background:#0f172a;color:#e2e8f0;margin:0;padding:24px}
h2{color:#38bdf8;border-left:5px solid #0ea5e9;padding-left:12px;margin:24px 0 8px}
h3{color:#fbbf24;margin:6px 0}
.q{background:#1e293b;padding:8px 12px;border-radius:6px;margin:6px 0;color:#a5f3fc;font-weight:600}
.muted{color:#94a3b8;font-size:13px;margin:4px 0 10px}
table{border-collapse:collapse;width:100%;background:#1e293b;font-size:13px}
th,td{border:1px solid #334155;padding:7px;text-align:left;vertical-align:top}
th{background:#0ea5e9;color:#082f49}
td.c{white-space:pre-wrap;line-height:1.6}
td.s{font-weight:700}
td.k{color:#cbd5e1;font-weight:700}
td.src{color:#94a3b8;font-size:12px}
section{background:#111c2f;padding:16px;border-radius:12px;margin-bottom:18px;box-shadow:0 4px 20px rgba(0,0,0,.3)}
.path{color:#64748b;font-size:12px;margin-top:6px}
</style></head><body>"""


def esc(s):
    return (s or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

def src_short(p):
    # 仅保留 rag/docs/jmu*.txt 形式的相对来源名
    s = p or ""
    return "rag/docs/" + s.split("/")[-1] if "/" in s or "\\" in s else s

def search(query, topk=4):
    body = json.dumps({"query": query, "topK": topk}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(SEARCH, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))


def build_slice():
    qs = [
        ("新生手册", "本科艺术类专业的学费标准是多少？"),
        ("新生手册", "户口迁移的截止日期是什么时候？"),
        ("招生手册", "集美大学是由哪五所院校合并组建的？"),
        ("招生手册", "哪些专业通过了师范专业二级认证？"),
    ]
    parts = [HEAD, '<h1 style="color:#f8fafc">① 状态机语义切片 · 检索质量验证</h1>']
    for manual, q in qs:
        hits = search(q)
        rows = ""
        for i, h in enumerate(hits, 1):
            rows += (f'<tr><td class="k">{i}</td><td class="s">{float(h.get("score",0)):.3f}</td>'
                     f'<td class="src">{esc(src_short(h.get("metadata",{}).get("source","")))}</td>'
                     f'<td class="c">{esc(h.get("content",""))}</td></tr>')
        parts.append(
            f'<section><h3>{esc(manual)}（召回 top{len(hits)}）</h3>'
            f'<div class="q">问：{esc(q)}</div>'
            f'<table><tr><th>#</th><th>相似度</th><th>来源</th><th>召回切片（状态机切片）</th></tr>{rows}</table></section>')
    parts.append("</body></html>")
    (Path(BASE) / "evidence_page_slice.html").write_text("".join(parts), encoding="utf-8")


def build_json():
    qj = json.loads((Path(BASE) / "test_questions.json").read_text(encoding="utf-8"))
    a = qj["新生手册_10问"]
    b = qj["招生手册_10问"]
    rows = "".join(
        f'<tr><td class="k">{i+1}</td><td class="c">{esc(a[i])}</td><td class="c">{esc(b[i])}</td></tr>'
        for i in range(len(a)))
    html = (HEAD +
            '<h1 style="color:#f8fafc">③ 两组各 10 个同构问题 · test_questions.json</h1>'
            '<p class="muted">问题句式结构一一对应、知识点槽位对齐，分别由新生手册与招生手册回答。</p>'
            '<table><tr><th>#</th><th>新生手册_10问</th><th>招生手册_10问</th></tr>' + rows + '</table>'
            '<p class="path">文件：rag/test_questions.json</p></body></html>')
    (Path(BASE) / "evidence_page_json.html").write_text(html, encoding="utf-8")


if __name__ == "__main__":
    build_json()
    build_slice()
    print("written evidence_page_slice.html / evidence_page_json.html")