#!/usr/bin/env python3
"""生成灵阅内置字典、词典数据（app/src/main/assets/dict/）。

数据来源（下载后作为参数传入）：
  - Unihan.zip、CJKRadicals.txt：https://www.unicode.org/Public/UCD/latest/ucd/
    许可：Unicode License v3（https://www.unicode.org/license.txt）
  - cedict_1_0_ts_utf-8_mdbg.zip：https://www.mdbg.net/chinese/dictionary?page=cc-cedict
    许可：CC BY-SA 4.0；转换后的 cedict.tsv.gz 仍按 CC BY-SA 4.0 发布

用法：python3 tools/build_dict.py Unihan.zip CJKRadicals.txt cedict.zip app/src/main/assets/dict

输出：
  unihan.tsv.gz  字\t读音(空格分隔，第一个为常用读音)\t部首\t部首外笔画\t总笔画\t繁体\t简体\t英文释义
  cedict.tsv.gz  简体\t繁体\t拼音(数字调)\t英文释义(用 / 分隔)，按简体的 UTF-16 编码排序（与 Kotlin 字符串比较一致）
"""
import gzip
import io
import re
import sys
import zipfile


def chars_of(field: str):
    return [chr(int(m, 16)) for m in re.findall(r"U\+([0-9A-F]+)", field)]


def build_unihan(unihan_zip: str, radicals_txt: str, out: str):
    radicals = {}
    for line in open(radicals_txt, encoding="utf-8"):
        line = line.split("#")[0].strip()
        if not line:
            continue
        num, _radical, unified = [p.strip() for p in line.split(";")]
        if unified:
            radicals[num] = chr(int(unified, 16))

    props = {}
    wanted = {"kMandarin", "kHanyuPinyin", "kXHC1983", "kTGHZ2013", "kRSUnicode", "kTotalStrokes",
              "kDefinition", "kSimplifiedVariant", "kTraditionalVariant"}
    with zipfile.ZipFile(unihan_zip) as z:
        for name in z.namelist():
            for line in io.TextIOWrapper(z.open(name), encoding="utf-8"):
                if line.startswith("#") or not line.strip():
                    continue
                code, key, value = line.rstrip("\n").split("\t", 2)
                if key in wanted:
                    props.setdefault(code, {})[key] = value

    rows = []
    for code, p in props.items():
        ch = chr(int(code[2:], 16))
        readings = []
        readings += p.get("kMandarin", "").split()
        for k in ("kHanyuPinyin", "kXHC1983", "kTGHZ2013"):
            for item in p.get(k, "").split():
                readings += item.split(":", 1)[-1].split(",")
        readings = list(dict.fromkeys(r for r in readings if r))
        definition = p.get("kDefinition", "")
        if not readings and not definition:
            continue
        radical = strokes_extra = ""
        rs = p.get("kRSUnicode", "").split()
        if rs:
            num, extra = rs[0].split(".")
            radical = radicals.get(num, radicals.get(num.rstrip("'").rstrip('"'), ""))
            strokes_extra = extra
        total = p.get("kTotalStrokes", "").split()
        trad = "".join(c for c in chars_of(p.get("kTraditionalVariant", "")) if c != ch)
        simp = "".join(c for c in chars_of(p.get("kSimplifiedVariant", "")) if c != ch)
        rows.append("\t".join([ch, " ".join(readings), radical, strokes_extra, total[0] if total else "",
                               trad, simp, definition.replace("\t", " ")]))
    rows.sort(key=lambda r: r.split("\t", 1)[0].encode("utf-16-be"))
    with gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as f:
        f.write("\n".join(rows) + "\n")
    return len(rows)


def build_cedict(cedict_zip: str, out: str):
    pattern = re.compile(r"^(\S+) (\S+) \[([^\]]*)\] /(.*)/$")
    rows = []
    with zipfile.ZipFile(cedict_zip) as z:
        name = [n for n in z.namelist() if n.endswith(".u8")][0]
        for line in io.TextIOWrapper(z.open(name), encoding="utf-8"):
            if line.startswith("#"):
                continue
            m = pattern.match(line.rstrip("\n").rstrip("\r"))
            if not m:
                continue
            trad, simp, pinyin, defs = m.groups()
            rows.append((simp, trad, pinyin, defs.replace("\t", " ")))
    rows.sort(key=lambda r: (r[0].encode("utf-16-be"), r[2]))
    with gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as f:
        for r in rows:
            f.write("\t".join(r) + "\n")
    return len(rows)


if __name__ == "__main__":
    unihan_zip, radicals_txt, cedict_zip, out_dir = sys.argv[1:5]
    print("unihan", build_unihan(unihan_zip, radicals_txt, f"{out_dir}/unihan.tsv.gz"))
    print("cedict", build_cedict(cedict_zip, f"{out_dir}/cedict.tsv.gz"))
