import re, subprocess, tempfile, os, glob
files = sorted(glob.glob(r"D:\java\zishuati\DomainPlane\docs\analysis\*.html"))
ok = True
for f in files:
    src = open(f, encoding="utf-8").read()
    try:
        assert src.startswith("<!doctype html>") and src.rstrip().endswith("</html>"), "doctype/tail"
        assert src.count("<html") == 1 and src.count("</html>") == 1, "html count"
        opens = len(re.findall(r"<script>", src)); closes = len(re.findall(r"</script>", src))
        assert opens == closes == 1, "script mismatch %d/%d" % (opens, closes)
        m = re.search(r"<script>(.*?)</script>", src, re.S)
        with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8") as t:
            t.write(m.group(1)); js = t.name
        r = subprocess.run(["node", "--check", js], capture_output=True, text=True)
        os.unlink(js)
        assert r.returncode == 0, "JS error -> %s" % r.stderr
        # 额外：确认没有未渲染的 markdown 残留
        leftovers = [p for p in ("| --- ", "<!-- @", "```") if p in src]
        print("%s: OK  sections=%d  callout=%d clever=%d layers=%d flow=%d timeline=%d len=%dKB  leftovers=%s"
              % (os.path.basename(f), src.count("<section id="), src.count('class="callout'),
                 src.count('class="cv"'), src.count('class="layers"'), src.count('class="flow"'),
                 src.count('class="master-tl"'), len(src)//1024, leftovers))
    except AssertionError as e:
        ok = False
        print("%s: FAIL %s" % (os.path.basename(f), e))
print("ALL CHECKS PASSED" if ok else "CHECKS FAILED")
