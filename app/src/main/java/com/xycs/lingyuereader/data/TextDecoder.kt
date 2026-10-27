package com.xycs.lingyuereader.data

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
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
                strictDecode(bytes, gb18030).let { gb -> big5Instead(bytes, gb) ?: gb }
            } catch (_: CharacterCodingException) {
                val utf8 = lenientDecode(bytes, Charsets.UTF_8)
                val utf8Bad = utf8.count { it == '\uFFFD' }
                if (utf8Bad * 100 <= utf8.length) utf8
                else lenientDecode(bytes, gb18030).takeIf { gb -> gb.count { it == '\uFFFD' } < utf8Bad } ?: utf8
            }
        }
        return validate(text, "文本编码无法识别或文件已损坏")
    }

    /**
     * 大文件按行流式解码：先整份扫一遍定编码（不保留解码结果），再逐行交给 [block]。
     * 定编码和坏字节的规则与 [decode] 相同，只是不必同时持有原始字节和整本文字。
     * 无 BOM 的 UTF-16 与 Big5 只看开头一段样本。
     */
    fun <T> readLines(file: File, block: (Sequence<String>) -> T): T {
        val head = file.inputStream().use { input ->
            val buffer = ByteArray(SAMPLE_BYTES)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            buffer.copyOf(size)
        }
        val known = when {
            head.startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
            head.startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
            head.startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
            file.length() % 2 == 0L -> bomlessUtf16(head.copyOf(head.size - head.size % 2))?.let { it to 0 }
            else -> null
        }
        val (charset, offset) = known ?: when {
            decodes(file, Charsets.UTF_8) -> Charsets.UTF_8
            decodes(file, gb18030) -> big5Sample(head) ?: gb18030
            else -> {
                val (utf8Bad, utf8Length) = damage(file, Charsets.UTF_8)
                if (utf8Bad * 100 <= utf8Length) Charsets.UTF_8
                else gb18030.takeIf { damage(file, it).first < utf8Bad } ?: Charsets.UTF_8
            }
        } to 0
        val error = if (known != null) "${charset.name()} 文本编码损坏" else "文本编码无法识别或文件已损坏"
        return BufferedReader(reader(file, charset, offset, CodingErrorAction.REPLACE), 64 * 1024).use { reader ->
            var bad = 0L
            var length = 0L
            var padding = 0L
            var readable = false
            val lines = generateSequence { reader.readLine() }.map { line ->
                val cleaned = StringBuilder(line.length)
                for (c in line) {
                    // 末尾的 NUL、Ctrl-Z 填充不算损坏；后面还有字时才把它们算进去。
                    if (c == '\u0000' || c == '\u001A') { padding++; continue }
                    bad += padding
                    length += padding
                    padding = 0
                    length++
                    if ((c < ' ' && c != '\t' && c != '\u000C') || c in '\u007F'..'\u009F') { bad++; continue }
                    if (c == '\uFFFD') bad++ else if (!c.isWhitespace()) readable = true
                    cleaned.append(c)
                }
                length++
                cleaned.toString()
            }
            val result = block(lines)
            require(bad * 100 <= length) { if (bad == 0L) error else "$error（$bad 处损坏）" }
            require(bad == 0L || readable) { error }
            result
        }
    }

    private fun reader(file: File, charset: Charset, offset: Int, action: CodingErrorAction): InputStreamReader {
        val input: InputStream = file.inputStream().buffered(64 * 1024)
        var skipped = 0L
        while (skipped < offset) {
            val count = input.skip(offset - skipped)
            if (count <= 0) break
            skipped += count
        }
        return InputStreamReader(input, charset.newDecoder().onMalformedInput(action).onUnmappableCharacter(action))
    }

    /** 整份严格解码一遍，只看能不能通过，不保留结果。 */
    private fun decodes(file: File, charset: Charset): Boolean = try {
        reader(file, charset, 0, CodingErrorAction.REPORT).use { reader ->
            val buffer = CharArray(16 * 1024)
            while (reader.read(buffer) >= 0) Unit
        }
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /** 宽容解码一遍，返回坏字数和总字数。 */
    private fun damage(file: File, charset: Charset): Pair<Int, Long> =
        reader(file, charset, 0, CodingErrorAction.REPLACE).use { reader ->
            val buffer = CharArray(16 * 1024)
            var bad = 0
            var length = 0L
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                length += count
                for (i in 0 until count) if (buffer[i] == '\uFFFD') bad++
            }
            bad to length
        }

    /** 与 [big5Instead] 同一判断，只用开头样本比较。 */
    private fun big5Sample(head: ByteArray): Charset? {
        val charset = big5 ?: return null
        val b5 = runCatching { lenientDecode(head, charset) }.getOrNull() ?: return null
        val gb = runCatching { lenientDecode(head, gb18030) }.getOrNull() ?: return null
        val b5Score = commonScore(b5)
        return charset.takeIf { b5Score > commonScore(gb.take(b5.length)) * 2 && b5Score >= 20 }
    }

    private const val SAMPLE_BYTES = 64 * 1024

    private val big5: Charset? = listOf("Big5-HKSCS", "Big5").firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() }

    /**
     * 繁体书常用 Big5 编码，而 Big5 的字节在 GB18030 里几乎总能“解码成功”，只是成了一堆生僻字和符号。
     * 两种都解得通时，比一比哪种解出的常用字多（繁简常用字都算）。按 Big5 读明显更像正常中文时返回它。
     */
    private fun big5Instead(bytes: ByteArray, gb: String): String? {
        val charset = big5 ?: return null
        val sample = bytes.copyOf(minOf(bytes.size, 64 * 1024))
        val b5 = runCatching { lenientDecode(sample, charset) }.getOrNull() ?: return null
        val gbScore = commonScore(gb.take(b5.length))
        val b5Score = commonScore(b5)
        if (b5Score <= gbScore * 2 || b5Score < 20) return null
        return runCatching { strictDecode(bytes, charset) }.getOrNull() ?: lenientDecode(bytes, charset)
    }

    /** 常用字（简体和繁体写法都在内）出现的次数。 */
    private fun commonScore(text: String): Int = text.count { it in COMMON }

    private val COMMON: Set<Char> = (
        "的一是不了在人有我他这個个们們中来來上大为為和国國地到以说說时時要就出会會可也你对對生能而子那得于於着著下自之年过過发發后後作里裡用道行所然家种種事成方多经經么麼去法学學如都同现現当當没沒动動面起看定天分还還进進好小部其些主样樣理心她本前开開但因只从從想实實日" +
            "者意无無力它与與长長把机機十民第公此已工使情明性知全三又关關点點正业業外将將两兩高间間由问問很最重并並物手应應战戰向头頭文体體政美相见見被利什二等产產或新己制身果加西月话話合回特代内內信表化老给給世位次度门門任常先海通教儿兒原东東声聲提立及比员員解水名真论論处處走义義各入几幾口认認条條平系气氣题題活更别別打女变變四神总總何电電数數安少报報才结結反受目太量再感建务務做接必场場件计計管期市直德资資命山金指许許统統区區保至队隊形社便空决決治展马馬科司五基眼书書非则則听聽白却界达達光放强強即像难難且权權思王象完设設式色路记記南品住告类類求据據程北边邊死张張该該交规規万萬取拉格望觉覺术術领領共确確传傳师師观觀清今切院让讓识識候带帶导導争爭运運笑飞飛风風步改收根干造言联聯持组組每济濟车車亲親极極林服快办辦议議往元英士证證近失转轉夫令准始怎呢存未远遠叫台单單影具罗羅字爱愛击擊流备備兵连連调調深商算质質团團集百需价價花党黨华華城石级級整府离離况況" +
            "，。“”「」？！"
        ).toSet()

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
