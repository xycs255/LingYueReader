package com.xycs.lingyuereader.data

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 一次写正文的篇幅：约 [target] 字，区间 [low]–[high]，[high] 就是上限。
 * 只说“约 800 字”时模型常常写成一千五，所以任务卡里把区间和上限都写明（[card]）；
 * 流式写的时候本机也按上限把关（[cutoff]），超得太多就在句末收住。
 * [range] 为真是作者自己写的区间（“3500 到 6000 字”）：上限就是区间上限，不再按目标放宽。
 */
data class ProseBudget(val target: Int, val low: Int, val high: Int, val han: Boolean = false, val range: Boolean = false) {
    /** 本机收住的阈值：实际写出来的超过上限的 [CUT] 倍，就在下一个句末停下。 */
    val cutoff: Int get() = (high * CUT).roundToInt()

    /**
     * 任务卡里的篇幅：“约 800 字（600–1000 字，最多不超过 1000 字；宁短勿长，这一拍的事写完就收，不要为了凑字加戏）”。
     * [what] 是这次写的范围（“这一拍的事”“这一章的事”）。
     */
    fun card(what: String): String {
        val u = if (han) "个汉字" else "字"
        return "约 $target $u（$low–$high $u，最多不超过 $high $u；宁短勿长，${what}写完就收，不要为了凑字加戏）"
    }

    companion object {
        /** 没写区间时，下限、上限取目标的几倍。 */
        const val LOW = 0.75
        const val HIGH = 1.25
        /** 本机收住：超过上限的几倍。 */
        const val CUT = 1.3
        /** 拆节拍时各拍加起来最多是全章目标的几倍（全章是区间时以区间上限为准）。 */
        const val BEATS_OVER = 1.1

        private const val NUM = "[0-9０-９零〇一二两三四五六七八九十百千万]+"
        private val number = Regex(NUM)
        private val rangeMark = Regex("$NUM\\s*(?:[—－–\\-~～至到])\\s*$NUM")
        private val atMost = Regex("不超过|至多|最多|以内|以下")
        private val atLeast = Regex("不少于|至少|不低于|以上|起步")

        /** 一个数字（目标）的篇幅：下限、上限按 [LOW]、[HIGH] 算，取整到 50 字。 */
        fun of(target: Int, han: Boolean = false): ProseBudget {
            val t = target.coerceAtLeast(50)
            val low = (ceil(t * LOW / 50) * 50).toInt().coerceAtMost(t)
            val high = (floor(t * HIGH / 50) * 50).toInt().coerceAtLeast(t)
            return ProseBudget(t, low, high, han)
        }

        /**
         * 从全章篇幅的写法里读出篇幅：“3500 到 6000 字”（区间，目标取中间）、“不少于 3000 字”“不超过 4000 字”“约 3000 字”。
         * 没有字数（“依本章细纲……”）、数字小得不像一章的为空。
         */
        fun parse(length: String): ProseBudget? {
            if ('字' !in length) return null
            val han = "汉字" in length
            val values = number.findAll(length).mapNotNull { CheckRules.cn(it.value.replace('〇', '零')) }.filter { it in 100..1_000_000 }.toList()
            if (values.isEmpty()) return null
            if (values.size >= 2 && rangeMark.containsMatchIn(length)) {
                val (a, b) = if (values[0] <= values[1]) values[0] to values[1] else values[1] to values[0]
                return ProseBudget(round50((a + b) / 2.0), a, b, han, range = true)
            }
            val v = values[0]
            return when {
                atMost.containsMatchIn(length) -> ProseBudget(round50(v * 0.8), round50(v * 0.6), v, han, range = true)
                atLeast.containsMatchIn(length) -> ProseBudget(v, v, (floor(v * HIGH / 50) * 50).toInt(), han)
                else -> of(v, han)
            }
        }

        /**
         * 写到本章结尾时剩下的篇幅：全章目标减去已写的 [written] 字，上限也减去已写的；剩不到 300 字为空（不再给数字催它凑字）。
         */
        fun remaining(chapter: ProseBudget, written: Int): ProseBudget? {
            val left = (chapter.target - written) / 100 * 100
            if (left < 300) return null
            // 上限往下、下限往上取整到 50 字，卡上不出现“3493–5993 字”这种零碎数字。
            val high = ((chapter.high - written) / 50 * 50).coerceAtLeast(left)
            val low = ((chapter.low - written + 49) / 50 * 50).coerceAtLeast(round50(left * 0.5)).coerceAtMost(left)
            return ProseBudget(left, low, high, chapter.han, chapter.range)
        }

        private fun round50(v: Double): Int = ((v / 50).roundToInt() * 50).coerceAtLeast(50)
    }
}
