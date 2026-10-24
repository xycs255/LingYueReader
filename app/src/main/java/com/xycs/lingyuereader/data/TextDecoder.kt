package com.xycs.lingyuereader.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 中文文本先识别 BOM 和常见的无 BOM UTF-16，再严格尝试 UTF-8、GB18030（兼容 GBK）定编码。
 *
 * 编码定下后对个别坏字节宽容：网上流传的 TXT 常有截断的末字节、旧 DOS 文件末尾的 Ctrl-Z、夹杂的控制字符，
 * 这些书原本能读，不能因为一两个字节整本打不开。坏字节显示为 U+FFFD，控制字符去掉。
 * 只有坏得多（超过百分之一）或根本没有正文时才报错，这时多半不是文本文件或编码猜错了。
 */
object TextDecoder {
    private val gb18030 = Charset.forName("GB18030")

    fun decode(bytes: ByteArray): String {
        val known = when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
            bytes.startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
            bytes.startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
            else -> bomlessUtf16(bytes)?.let { it to 0 }
        }
        if (known != null) {
            val (charset, offset) = known
            return validate(lenientDecode(bytes, charset, offset), "${charset.name()} 文本编码损坏")
        }

        // 先严格试 UTF-8、再试 GB18030 定编码。都有坏字节时：UTF-8 只坏了零星几处就按 UTF-8
        // （GB18030 几乎什么字节都能拼出字来，拿它的坏字数比不可靠），否则取坏得少的那种。
        val text = try {
            strictDecode(bytes, Charsets.UTF_8)
        } catch (_: CharacterCodingException) {
            try {
                strictDecode(bytes, gb18030)
            } catch (_: CharacterCodingException) {
                val utf8 = lenientDecode(bytes, Charsets.UTF_8)
                val utf8Bad = utf8.count { it == '\uFFFD' }
                if (utf8Bad * 100 <= utf8.length) utf8
                else lenientDecode(bytes, gb18030).takeIf { gb -> gb.count { it == '\uFFFD' } < utf8Bad } ?: utf8
            }
        }
        return validate(text, "文本编码无法识别或文件已损坏")
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset, offset: Int = 0): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()

    private fun lenientDecode(bytes: ByteArray, charset: Charset, offset: Int = 0): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()

    /**
     * 去掉控制字符（换行、回车、制表、换页除外）。末尾的 NUL、Ctrl-Z 填充不算损坏；
     * 其余控制字符和 U+FFFD 超过全文百分之一，或去掉后没剩下可读的字，就当作不是文本、报 [error]。
     */
    private fun validate(text: String, error: String): String {
        val body = text.trimEnd('\u0000', '\u001A')
        var bad = 0
        val cleaned = StringBuilder(body.length)
        for (c in body) {
            if ((c < ' ' && c != '\n' && c != '\r' && c != '\t' && c != '\u000C') || c in '\u007F'..'\u009F') {
                bad++
                continue
            }
            if (c == '\uFFFD') bad++
            cleaned.append(c)
        }
        require(bad * 100 <= body.length) { if (bad == 0) error else "$error（$bad 处损坏）" }
        require(bad == 0 || cleaned.any { !it.isWhitespace() && it != '\uFFFD' }) { error }
        return cleaned.toString()
    }

    /** 按双字节边界寻找换行与空格；普通中文 UTF-16 的汉字字节本身很少是零。 */
    private fun bomlessUtf16(bytes: ByteArray): Charset? {
        if (bytes.size < 8 || bytes.size % 2 != 0 || bytes.count { it == 0.toByte() } < 2) return null
        var little = 0
        var big = 0
        for (i in bytes.indices step 2) {
            val first = bytes[i].toInt() and 0xFF
            val second = bytes[i + 1].toInt() and 0xFF
            if ((second == 0 && first.isAsciiSpacing()) || (first == 0 && second == 0x30)) little++
            if ((first == 0 && second.isAsciiSpacing()) || (first == 0x30 && second == 0)) big++
        }
        return when {
            little >= 2 && little >= big * 2 + 1 -> Charsets.UTF_16LE
            big >= 2 && big >= little * 2 + 1 -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }

    private fun Int.isAsciiSpacing(): Boolean = this == 9 || this == 10 || this == 13 || this == 32
}
