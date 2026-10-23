#!/usr/bin/env python3
"""生成测试用的小字体（tools 之外不分发）：方块字形覆盖 A–Z、a–z 和几个汉字。

用法：python3 tools/make_test_font.py app/src/test/resources/testfonts/TestBlocks.ttf
需要 fontTools。注意不要放在测试资源的 fonts/ 目录：Robolectric 从类路径的 fonts/ 读系统字体。
字形全是自己画的方块，没有版权问题。
"""
import sys

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen


def box(width, inset):
    pen = TTGlyphPen(None)
    pen.moveTo((inset, 0))
    pen.lineTo((inset, 700))
    pen.lineTo((width - inset, 700))
    pen.lineTo((width - inset, 0))
    pen.closePath()
    return pen.glyph()


chars = [chr(c) for c in range(ord("A"), ord("Z") + 1)] + [chr(c) for c in range(ord("a"), ord("z") + 1)] + list("测试字体灵阅")
names = [".notdef", "space"] + [f"g{ord(c):04X}" for c in chars]
fb = FontBuilder(1000, isTTF=True)
fb.setupGlyphOrder(names)
fb.setupCharacterMap({32: "space", **{ord(c): f"g{ord(c):04X}" for c in chars}})
glyphs = {".notdef": box(600, 50), "space": TTGlyphPen(None).glyph()}
glyphs.update({f"g{ord(c):04X}": box(1000 if ord(c) > 127 else 600, 60) for c in chars})
fb.setupGlyf(glyphs)
fb.setupHorizontalMetrics({n: ((1000 if n.startswith("g") and int(n[1:], 16) > 127 else 600), 0) for n in names})
fb.setupHorizontalHeader(ascent=800, descent=-200)
fb.setupNameTable({"familyName": "TestBlocks", "styleName": "Regular"})
fb.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
fb.setupPost()
fb.save(sys.argv[1])
