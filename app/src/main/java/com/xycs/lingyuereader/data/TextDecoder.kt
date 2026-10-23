package com.xycs.lingyuereader.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 中文文本先识别 BOM 和常见的无 BOM UTF-16，再严格尝试 UTF-8、GB18030（兼容 GBK）。 */
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
            val text = try {
                strictDecode(bytes, charset, offset)
            } catch (_: CharacterCodingException) {
                throw IllegalArgumentException("${charset.name()} 文本编码损坏")
            }
            return validate(text)
        }

        // UTF-8 必须严格校验，否则坏字节会被 U+FFFD 悄悄替换。
        val text = try {
            strictDecode(bytes, Charsets.UTF_8)
        } catch (_: CharacterCodingException) {
            try {
                strictDecode(bytes, gb18030)
            } catch (_: CharacterCodingException) {
                throw IllegalArgumentException("文本编码无法识别或文件已损坏")
            }
        }
        return validate(text)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset, offset: Int = 0): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()

    private fun validate(text: String): String {
        require('\uFFFD' !in text) { "文本含有损坏字符" }
        require(text.none { (it < ' ' && it != '\n' && it != '\r' && it != '\t' && it != '\u000C') || it in '\u007F'..'\u009F' }) {
            "文本含有无法显示的控制字符，可能不是纯文本文件"
        }
        return text
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
