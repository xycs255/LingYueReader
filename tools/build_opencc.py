#!/usr/bin/env python3
"""由 OpenCC 的词典生成灵阅的繁简转换表。

用法：python3 tools/build_opencc.py <OpenCC data/dictionary 目录> app/src/main/assets/dict

只保留转换前后长度（按 UTF-16）相同的条目：这样转换不会改变文字长度，书签、批注、选区的位置都不用重算。
输出 s2t.tsv.gz（简→繁）与 t2s.tsv.gz（繁→简），每行“原文\t转换后”，单字与词组混在一起。
OpenCC（https://github.com/BYVoid/OpenCC）以 Apache License 2.0 发布。
"""
import gzip
import os
import sys


def load(path, table):
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            key, _, values = line.rstrip("\n").partition("\t")
            value = values.split(" ")[0] if values else ""
            # 比较 UTF-16 长度（Kotlin 字符串按 UTF-16 计），扩展区汉字占两个单位。
            # 词组即使转换前后相同（如“皇后”）也要留着：它挡住了逐字转换（“后”→“後”）。
            if not key or not value or len(key.encode("utf-16-le")) != len(value.encode("utf-16-le")):
                continue
            if key != value or len(key.encode("utf-16-le")) > 2:
                table.setdefault(key, value)


def build(src, out, name, files):
    table = {}
    for f in files:
        load(os.path.join(src, f), table)
    with gzip.open(os.path.join(out, name), "wt", encoding="utf-8", compresslevel=9) as w:
        for k in sorted(table):
            w.write(f"{k}\t{table[k]}\n")
    print(name, len(table))


if __name__ == "__main__":
    src, out = sys.argv[1], sys.argv[2]
    build(src, out, "s2t.tsv.gz", ["STPhrases.txt", "STCharacters.txt"])
    build(src, out, "t2s.tsv.gz", ["TSPhrases.txt", "TSCharacters.txt"])
