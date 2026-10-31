package com.xycs.lingyuereader.ui

import androidx.compose.ui.graphics.Color

/*
 * 编辑器正文里标记的样子。以前每处都铺一块深米色底（规则 20%、建议 14%），同一个禁用词每行都有，整页像补丁，打断读稿。
 * 现在底色压到很淡，主要靠基线下的划线分：写作建议五类各一种线形（[suggestLine]），本机按规则、AI 腔、黑名单标的是实线。
 * 线形是静止的，墨水屏灰阶、减少动效、色弱下照样分得开。
 */

/** 标记底色的透明度：浅色 7%，深色 10%（深色底上同样的透明度看着更淡）。 */
internal const val MARK_FILL_LIGHT = 0.07f
internal const val MARK_FILL_DARK = 0.10f

/** 同一条规则在一章里第几处以后只画线、不铺底。 */
internal const val MARK_FILL_REPEATS = 3

/** 本机按规则、AI 腔、黑名单标出来的一处用实线，和写作建议的五种线形都不一样。 */
internal val ruleMarkLine = SuggestLine.SOLID

/** 一处标记的底色：[color] 的淡色；[nth] 是同一条规则的第几处，超过 [MARK_FILL_REPEATS] 就不铺底。 */
internal fun markFill(color: Color, dark: Boolean, nth: Int = 1): Color =
    if (nth > MARK_FILL_REPEATS) Color.Transparent else color.copy(alpha = if (dark) MARK_FILL_DARK else MARK_FILL_LIGHT)

/** 规则标记的划线色：提醒色稍淡一点，比写作建议的线安静。 */
internal fun markLineColor(color: Color): Color = color.copy(alpha = 0.85f)
