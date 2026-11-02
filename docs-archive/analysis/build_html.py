#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 analysis/*.md 渲染成自包含 HTML（复用 deep-project-analysis 的报告骨架）。

支持的行内语法：**粗体**、`行内代码`
支持的块语法：## 章节 / ### / #### / 表格 / ```lang fname 代码 / 有序无序列表 / > 引用
组件指令（写在块前一行，渲染后不可见）：

  <!-- meta ... -->          文档头信息（title/kicker/subtitle/chip/conclusion）
  <!-- @callout ok 标题 -->   下一段引用渲染成 callout（ok/warn/info/red）
  <!-- @layers -->           下一张表（徽章|层名|说明|文件,文件）渲染成分层图
  <!-- @flow -->             下一张表（序|标题|说明|层）渲染成流程图
  <!-- @timeline -->         下一张表（日期|commit|徽章|标题|描述）渲染成时间线
  <!-- @clever -->           后续 ### 小节渲染成可折叠巧思卡（末段 > 为什么必然 ...）

用法：python build_html.py        # 渲染同目录下所有 .md
"""
import html
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TEMPLATE = r"C:\Users\zhang'xiao'chen\.workbuddy\skills\deep-project-analysis\assets\report-template.html"


def load_shell():
    src = open(TEMPLATE, encoding="utf-8").read()
    style = src[src.index("<style>"):src.index("</style>") + len("</style>")]
    script = src[src.index("<script>"):src.index("</script>") + len("</script>")]
    return style, script


def inline(text):
    """先转义，再做行内标记。"""
    out = html.escape(text, quote=False)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    out = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", out)
    return out


def cells(row):
    return [c.strip() for c in row.strip().strip("|").split("|")]


def is_table_sep(row):
    return re.fullmatch(r"\|[\s\-:|]+\|", row.strip()) is not None


def render_table(rows):
    head, body = rows[0], rows[2:]
    buf = ["<table>", "<thead><tr>"]
    buf += ["<th>%s</th>" % inline(c) for c in cells(head)]
    buf.append("</tr></thead><tbody>")
    for r in body:
        buf.append("<tr>" + "".join("<td>%s</td>" % inline(c) for c in cells(r)) + "</tr>")
    buf.append("</tbody></table>")
    return "\n".join(buf)


def render_layers(rows):
    body = rows[2:]
    classes = ["l-base", "l-domain", "l-agent"]
    badges = ["badge-base", "badge-domain", "badge-agent"]
    buf = ['<div class="layers">']
    for i, r in enumerate(body):
        c = cells(r)
        badge, name, desc = c[0], c[1], c[2]
        files = c[3] if len(c) > 3 else ""
        cls = classes[i % 3]
        bcls = badges[i % 3]
        buf.append('<div class="layer %s"><div class="lname"><span class="badge %s">%s</span>%s</div>'
                   % (cls, bcls, inline(badge), inline(name)))
        buf.append('<div class="ldesc">%s</div>' % inline(desc))
        if files:
            chips = "".join("<span>%s</span>" % inline(f.strip()) for f in files.split(","))
            buf.append('<div class="lfiles">%s</div>' % chips)
        buf.append("</div>")
    buf.append("</div>")
    return "\n".join(buf)


def render_flow(rows):
    body = rows[2:]
    colors = ["var(--green)", "var(--amber)", "var(--blue)", "var(--green-dark)"]
    buf = ['<div class="flow">']
    for i, r in enumerate(body):
        c = cells(r)
        idx, title, desc = c[0], c[1], c[2]
        side = c[3] if len(c) > 3 else ""
        buf.append('<div class="step"><span class="idx" style="background:%s">%s</span>'
                   '<div><div class="st">%s</div><div class="sd">%s</div></div><span class="side">%s</span></div>'
                   % (colors[i % 4], inline(idx), inline(title), inline(desc), inline(side)))
        if i < len(body) - 1:
            buf.append('<div class="join">▼</div>')
    buf.append("</div>")
    return "\n".join(buf)


def render_timeline(rows):
    body = rows[2:]
    marks = "abcdefgh"
    buf = ['<div class="master-tl">']
    for i, r in enumerate(body):
        c = cells(r)
        date, commit, badge, title, desc = c[0], c[1], c[2], c[3], c[4]
        buf.append('<div class="mt"><div class="dot"></div><div class="ph">'
                   '<span class="badge b-%s">%s</span><span class="date">%s</span>'
                   '<span class="commit">%s</span><span class="title">%s</span></div>'
                   '<div class="desc">%s</div></div>'
                   % (marks[i % 8], inline(badge), inline(date), inline(commit), inline(title), inline(desc)))
    buf.append("</div>")
    return "\n".join(buf)


def render_code(lang, fname, code):
    bar = ('<div class="bar"><span class="dot"></span><span class="dot"></span>'
           '<span class="dot"></span><span class="fname">%s</span></div>' % html.escape(fname or lang))
    return ('<div class="codebox">%s<pre><code data-lang="%s">%s</code></pre></div>'
            % (bar, html.escape(lang), html.escape(code, quote=False)))


def render_callout(kind, title, paragraphs):
    body = "".join("<p>%s</p>" % inline(p) for p in paragraphs)
    return '<div class="callout %s"><span class="t">%s</span>%s</div>' % (kind, inline(title), body)


def render_clever(items):
    colors = ["var(--green)", "var(--amber)", "var(--blue)", "var(--green-dark)", "#356a91"]
    buf = ['<div class="clever">']
    for i, (title, body) in enumerate(items):
        # body: list of html blocks; last 「为什么必然」 段落单独提出来
        why = ""
        blocks = []
        for b in body:
            if b.startswith("@@why"):
                why = b[5:].strip()
            else:
                blocks.append(b)
        fname = ""
        if "｜" in title:
            title, fname = [x.strip() for x in title.split("｜", 1)]
        buf.append('<div class="cv"><div class="cv-h">'
                   '<span class="cv-n" style="background:%s">%d</span>'
                   '<span class="cv-t">%s</span><span class="cv-f">%s</span>'
                   '<span class="toggle">+</span></div><div class="cv-b">'
                   % (colors[i % 5], i + 1, inline(title), inline(fname)))
        buf.extend(blocks)
        if why:
            buf.append('<div class="why"><b>为什么必然：</b>%s</div>' % inline(why))
        buf.append("</div></div>")
    buf.append("</div>")
    return "\n".join(buf)


def parse_meta(lines):
    meta = {}
    for line in lines:
        if ":" in line:
            k, v = line.split(":", 1)
            meta[k.strip()] = v.strip()
    return meta


def convert(md, meta):
    lines = md.split("\n")
    # 去掉开头的 meta 注释与一级标题
    body_lines = []
    in_meta = False
    for line in lines:
        if line.strip().startswith("<!-- meta"):
            in_meta = True
            continue
        if in_meta:
            if "-->" in line:
                in_meta = False
            continue
        body_lines.append(line)

    sections = []
    cur = None
    hint = None
    clever_mode = False
    clever_items = []

    i = 0
    n = len(body_lines)
    while i < n:
        raw = body_lines[i]
        line = raw.rstrip()
        stripped = line.strip()

        if stripped.startswith("<!-- @"):
            hint = stripped[6:].rstrip("-> ").strip()
            i += 1
            continue

        if stripped.startswith("# ") and not stripped.startswith("## "):
            i += 1
            continue

        if stripped.startswith("## "):
            if cur is not None:
                if clever_mode and clever_items:
                    cur["html"].append(render_clever(clever_items))
                    clever_items = []
                sections.append(cur)
            clever_mode = hint == "clever"
            hint = None if hint == "clever" else hint
            cur = {"title": stripped[3:].strip(), "html": []}
            i += 1
            continue

        if cur is None:
            i += 1
            continue

        if stripped == "":
            i += 1
            continue

        # ---- clever 模式下的 ### 小节 ----
        if (clever_mode or hint == "clever") and stripped.startswith("### "):
            clever_mode = True
            hint = None
            item_title = stripped[4:].strip()
            item_blocks = []
            i += 1
            while i < n:
                s2 = body_lines[i].strip()
                if s2.startswith("### ") or s2.startswith("## "):
                    break
                if s2 == "":
                    i += 1
                    continue
                if s2.startswith("```"):
                    lang, fname, code, i = read_code(body_lines, i)
                    item_blocks.append(render_code(lang, fname, code))
                    continue
                if s2.startswith(">"):
                    quote = []
                    while i < n and body_lines[i].strip().startswith(">"):
                        quote.append(body_lines[i].strip()[1:].strip())
                        i += 1
                    text = " ".join(quote)
                    if text.startswith("为什么必然"):
                        why = text
                        for sep in ("：", ":"):
                            if sep in why:
                                why = why.split(sep, 1)[1]
                                break
                        item_blocks.append("@@why" + why.strip())
                    else:
                        item_blocks.append('<div class="why">%s</div>' % inline(text))
                    continue
                # 普通段落
                para = []
                while i < n and body_lines[i].strip() != "" and not body_lines[i].strip().startswith(("### ", "## ", "```", ">")):
                    para.append(body_lines[i].strip())
                    i += 1
                item_blocks.append("<p>%s</p>" % inline(" ".join(para)))
            clever_items.append((item_title, item_blocks))
            continue

        # ---- 代码块 ----
        if stripped.startswith("```"):
            lang, fname, code, i = read_code(body_lines, i)
            cur["html"].append(render_code(lang, fname, code))
            continue

        # ---- 表格 ----
        if stripped.startswith("|"):
            rows = []
            while i < n and body_lines[i].strip().startswith("|"):
                rows.append(body_lines[i].strip())
                i += 1
            if len(rows) >= 2 and is_table_sep(rows[1]):
                if hint == "layers":
                    cur["html"].append(render_layers(rows))
                elif hint == "flow":
                    cur["html"].append(render_flow(rows))
                elif hint == "timeline":
                    cur["html"].append(render_timeline(rows))
                else:
                    cur["html"].append(render_table(rows))
                hint = None
            continue

        # ---- 引用 ----
        if stripped.startswith(">"):
            quote = []
            while i < n and body_lines[i].strip().startswith(">"):
                quote.append(body_lines[i].strip()[1:].strip())
                i += 1
            if hint and hint.startswith("callout"):
                parts = hint.split()
                kind = parts[1] if len(parts) > 1 else "info"
                ctitle = " ".join(parts[2:]) or "提示"
                cur["html"].append(render_callout(kind, ctitle, [q for q in quote if q]))
                hint = None
            else:
                cur["html"].append('<div class="lead">%s</div>' % inline(" ".join(quote)))
            continue

        # ---- 标题 ----
        if stripped.startswith("#### "):
            cur["html"].append("<h4>%s</h4>" % inline(stripped[5:].strip()))
            i += 1
            continue
        if stripped.startswith("### "):
            cur["html"].append("<h3>%s</h3>" % inline(stripped[4:].strip()))
            i += 1
            continue

        # ---- 列表 ----
        if re.match(r"^[-*] ", stripped) or re.match(r"^\d+\. ", stripped):
            ordered = bool(re.match(r"^\d+\. ", stripped))
            items = []
            while i < n:
                s2 = body_lines[i].strip()
                m = re.match(r"^\d+\. (.+)$", s2) if ordered else re.match(r"^[-*] (.+)$", s2)
                if not m:
                    break
                items.append(m.group(1))
                i += 1
            tag = "ol" if ordered else "ul"
            cur["html"].append("<%s>%s</%s>" % (tag, "".join("<li>%s</li>" % inline(x) for x in items), tag))
            continue

        # ---- 段落 ----
        para = []
        while i < n:
            s2 = body_lines[i].strip()
            if s2 == "" or s2.startswith(("#", "|", "```", ">", "<!--")) or re.match(r"^[-*] ", s2) or re.match(r"^\d+\. ", s2):
                break
            para.append(s2)
            i += 1
        if para:
            cur["html"].append("<p>%s</p>" % inline(" ".join(para)))

    if cur is not None:
        if clever_mode and clever_items:
            cur["html"].append(render_clever(clever_items))
        sections.append(cur)

    # 组装 sections
    out = []
    for idx, s in enumerate(sections, start=1):
        out.append('<section id="s%d">' % idx)
        out.append('<h2><span class="sec-no">%d</span>%s</h2>' % (idx, inline(s["title"])))
        out.extend(s["html"])
        out.append("</section>")
    return "\n".join(out), len(sections)


def read_code(lines, i):
    header = lines[i].strip()[3:].strip()
    parts = header.split()
    lang = parts[0] if parts else "text"
    fname = parts[1] if len(parts) > 1 else ""
    i += 1
    code = []
    while i < len(lines) and not lines[i].strip().startswith("```"):
        code.append(lines[i])
        i += 1
    return lang, fname, "\n".join(code), i + 1


def build(md_path, style, script, footer_links):
    md = open(md_path, encoding="utf-8").read()
    m = re.search(r"<!-- meta(.*?)-->", md, re.S)
    meta = parse_meta(m.group(1).split("\n")) if m else {}
    title = meta.get("title", os.path.basename(md_path))
    content, count = convert(md, meta)

    chips = []
    for k in ("chip1", "chip2", "chip3"):
        if k in meta:
            chips.append('<span class="chip">%s</span>' % inline(meta[k]))
    brand = meta.get("brand", "DP")
    doc = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%s</title>
%s
</head>
<body>
<div class="layout">
  <aside class="toc">
    <div class="brand">
      <div class="brand-mark">%s</div>
      <div><strong>%s</strong><span>%s</span></div>
    </div>
    <nav id="tocNav"></nav>
  </aside>
  <main>
    <header class="hero">
      <div class="wrap">
        <div class="kicker">%s</div>
        <h1>%s</h1>
        <p>%s</p>
        <div class="meta">%s</div>
        <div class="conclusion"><b>结论先行：</b>%s</div>
      </div>
    </header>
    <div class="content">
%s
      <footer style="margin-top:70px;padding-top:18px;border-top:1px solid var(--line);color:var(--muted);font-size:12.5px;display:flex;justify-content:space-between;flex-wrap:wrap;gap:8px">
        <span>%s · 生成于 %s</span>
        <span>%s</span>
      </footer>
    </div>
  </main>
</div>
%s
</body>
</html>
""" % (
        html.escape(title), style, html.escape(brand),
        html.escape(meta.get("short", title)), html.escape(meta.get("subtitle", "")),
        html.escape(meta.get("kicker", "Analysis")), inline(title),
        inline(meta.get("subtitle", "")), "".join(chips),
        inline(meta.get("conclusion", "")), content,
        html.escape(title), html.escape(os.path.basename(md_path).replace(".md", "")),
        footer_links, script,
    )
    out_path = md_path[:-3] + ".html"
    open(out_path, "w", encoding="utf-8").write(doc)
    return out_path, count


def main():
    style, script = load_shell()
    mds = sorted(f for f in os.listdir(HERE) if f.endswith(".md") and not f.startswith("_"))
    links = " · ".join('<a href="%s.html">%s</a>' % (f[:-3], f[:-3]) for f in mds)
    for f in mds:
        path, count = build(os.path.join(HERE, f), style, script, links)
        print("built %s (%d sections)" % (path, count))


if __name__ == "__main__":
    main()
