package com.xycs.lingyuereader.dict

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.Inflater

/**
 * MDict 词典文件（.mdx 正文、.mdd 资源）的读取。支持 1.2 / 2.0 版，块压缩为无、LZO、zlib，
 * 以及加密的关键词索引（Encrypted=2）。需要注册码的正文加密（Encrypted=1）不支持。
 *
 * 文件结构：头部（UTF-16 的 XML 属性）→ 关键词区（索引信息 + 若干关键词块）→ 正文区（块信息 + 若干正文块）。
 * 关键词块里每条是“正文偏移 + 以 0 结尾的词条”，正文按偏移从解压后的正文块里截取。
 */
class MdictFile(private val file: File, private val isMdd: Boolean = false) {

    class Unsupported(message: String) : Exception(message)

    val title: String
    val description: String
    val charset: Charset
    private val utf16: Boolean
    private val version: Double
    private val encrypted: Int
    private val numberWidth: Int
    private val keySectionStart: Long

    /** 关键词块：在文件里的位置和大小。 */
    private class BlockInfo(val fileOffset: Long, val compressedSize: Int, val decompressedSize: Int, val decompressedOffset: Long)

    private val keyBlocks: List<BlockInfo>
    private val recordBlocks: List<BlockInfo>
    val entryCount: Long
    private val recordsTotal: Long

    init {
        RandomAccessFile(file, "r").use { raf ->
            val headerSize = raf.readInt()
            val headerBytes = ByteArray(headerSize).also { raf.readFully(it) }
            raf.skipBytes(4) // adler32（小端）
            val headerText = if (headerBytes.size >= 2 && headerBytes[headerBytes.size - 1] == 0.toByte() && headerBytes[headerBytes.size - 2] == 0.toByte()) {
                String(headerBytes, 0, headerBytes.size - 2, Charsets.UTF_16LE)
            } else {
                String(headerBytes, Charsets.UTF_16LE).trimEnd('\u0000')
            }
            val attrs = Regex("""(\w+)="((?:.|\n)*?)"""").findAll(headerText).associate { it.groupValues[1] to it.groupValues[2] }
            version = attrs["GeneratedByEngineVersion"]?.toDoubleOrNull() ?: 2.0
            if (version >= 3.0) throw Unsupported("暂不支持 MDict 3.0 格式")
            encrypted = when (val e = attrs["Encrypted"]) {
                null, "No", "" -> 0
                "Yes" -> 1
                else -> e.toIntOrNull() ?: 0
            }
            if (encrypted and 1 != 0) throw Unsupported("这本词典的正文加密了（需要注册码），无法读取")
            val encodingName = if (isMdd) "UTF-16" else attrs["Encoding"]?.uppercase().orEmpty().ifEmpty { "UTF-8" }
            charset = when (encodingName) {
                "UTF-16", "UTF16" -> Charsets.UTF_16LE
                "GBK", "GB2312", "GB18030" -> Charset.forName("GB18030")
                "BIG5", "BIG-5" -> Charset.forName("Big5")
                else -> runCatching { Charset.forName(encodingName) }.getOrDefault(Charsets.UTF_8)
            }
            utf16 = charset == Charsets.UTF_16LE
            title = unescape(attrs["Title"].orEmpty()).trim().takeIf { it.isNotEmpty() && it != "Title (No HTML code allowed)" }
                ?: file.nameWithoutExtension
            description = unescape(attrs["Description"].orEmpty()).trim()
            numberWidth = if (version >= 2.0) 8 else 4
            keySectionStart = raf.filePointer

            // 关键词区
            val numKeyBlocks = readNumber(raf)
            entryCount = readNumber(raf)
            if (version >= 2.0) readNumber(raf) // 索引信息解压后大小
            val keyInfoSize = readNumber(raf)
            val keyBlocksSize = readNumber(raf)
            if (version >= 2.0) raf.skipBytes(4) // 以上数字的 adler32
            val keyInfoRaw = ByteArray(keyInfoSize.toInt()).also { raf.readFully(it) }
            val keyInfo = decodeKeyInfo(keyInfoRaw)
            var pos = raf.filePointer
            var decompressedAt = 0L
            keyBlocks = parseKeyInfo(keyInfo).map { (c, d) ->
                BlockInfo(pos, c, d, decompressedAt).also {
                    pos += c
                    decompressedAt += d
                }
            }
            if (keyBlocks.size.toLong() != numKeyBlocks) throw Unsupported("关键词索引损坏")
            raf.seek(keySectionStart + (if (version >= 2.0) 44 else 16) + keyInfoSize + keyBlocksSize)

            // 正文区
            val numRecordBlocks = readNumber(raf)
            readNumber(raf) // 条目数
            readNumber(raf) // 块信息大小
            readNumber(raf) // 正文块总大小
            val sizes = (0 until numRecordBlocks).map { readNumber(raf).toInt() to readNumber(raf).toInt() }
            pos = raf.filePointer
            decompressedAt = 0L
            recordBlocks = sizes.map { (c, d) ->
                BlockInfo(pos, c, d, decompressedAt).also {
                    pos += c
                    decompressedAt += d
                }
            }
            recordsTotal = decompressedAt
        }
    }

    private fun readNumber(raf: RandomAccessFile): Long = if (numberWidth == 8) raf.readLong() else raf.readInt().toLong() and 0xffffffffL

    private fun decodeKeyInfo(raw: ByteArray): ByteArray {
        if (version < 2.0) return raw
        var data = raw
        if (encrypted and 2 != 0) {
            val key = Ripemd128.digest(raw.copyOfRange(4, 8) + byteArrayOf(0x95.toByte(), 0x36, 0, 0))
            data = raw.copyOfRange(0, 8) + fastDecrypt(raw.copyOfRange(8, raw.size), key)
        }
        return decodeBlock(data, -1)
    }

    /** 索引信息：每块的条目数、首尾关键词（跳过）、压缩与解压后大小。 */
    private fun parseKeyInfo(info: ByteArray): List<Pair<Int, Int>> {
        val buf = ByteBuffer.wrap(info).order(ByteOrder.BIG_ENDIAN)
        val sizeWidth = if (version >= 2.0) 2 else 1
        val term = if (version >= 2.0) 1 else 0
        val unit = if (utf16) 2 else 1
        val out = mutableListOf<Pair<Int, Int>>()
        while (buf.remaining() > 0) {
            if (numberWidth == 8) buf.long else buf.int
            repeat(2) {
                val n = if (sizeWidth == 2) buf.short.toInt() and 0xffff else buf.get().toInt() and 0xff
                buf.position(buf.position() + (n + term) * unit)
            }
            val compressed = if (numberWidth == 8) buf.long.toInt() else buf.int
            val decompressed = if (numberWidth == 8) buf.long.toInt() else buf.int
            out += compressed to decompressed
        }
        return out
    }

    private fun readBlock(raf: RandomAccessFile, block: BlockInfo): ByteArray {
        val raw = ByteArray(block.compressedSize)
        raf.seek(block.fileOffset)
        raf.readFully(raw)
        return decodeBlock(raw, block.decompressedSize)
    }

    /** 块：4 字节压缩方式（小端）+ 4 字节 adler32 + 数据。 */
    private fun decodeBlock(raw: ByteArray, decompressedSize: Int): ByteArray {
        val type = (raw[0].toInt() and 0xff) or ((raw[1].toInt() and 0xff) shl 8)
        val data = raw.copyOfRange(8, raw.size)
        val out = when (type and 0xf) {
            0 -> data
            1 -> Lzo.decompress(data, if (decompressedSize >= 0) decompressedSize else data.size * 8)
            2 -> inflate(data, decompressedSize)
            else -> throw Unsupported("不支持的压缩方式 $type")
        }
        // 不校验 adler32：1.x/2.0 的参考实现也不校验，有些流传的词典校验值本身就不对。
        return out
    }

    private fun inflate(data: ByteArray, size: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(if (size > 0) size else data.size * 4)
        val buffer = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    /** 逐条列出全部关键词及其正文位置（偏移、长度）。建索引时用。 */
    fun forEachKey(action: (key: String, offset: Long, size: Int) -> Unit) {
        RandomAccessFile(file, "r").use { raf ->
            var pending: Pair<String, Long>? = null
            for (block in keyBlocks) {
                val data = readBlock(raf, block)
                var i = 0
                while (i < data.size) {
                    val offset = if (numberWidth == 8) {
                        ByteBuffer.wrap(data, i, 8).order(ByteOrder.BIG_ENDIAN).long
                    } else {
                        ByteBuffer.wrap(data, i, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
                    }
                    i += numberWidth
                    var end = i
                    if (utf16) {
                        while (end + 1 < data.size && !(data[end] == 0.toByte() && data[end + 1] == 0.toByte())) end += 2
                    } else {
                        while (end < data.size && data[end] != 0.toByte()) end++
                    }
                    val key = String(data, i, end - i, charset).trim()
                    i = end + if (utf16) 2 else 1
                    pending?.let { (k, o) -> action(k, o, (offset - o).toInt()) }
                    pending = key to offset
                }
            }
            pending?.let { (k, o) -> action(k, o, (recordsTotal - o).toInt()) }
        }
    }

    /** 取出 [offset] 起 [size] 字节的正文（可能跨块）。 */
    fun record(offset: Long, size: Int): ByteArray {
        val out = ByteArrayOutputStream(size)
        RandomAccessFile(file, "r").use { raf ->
            var at = offset
            val end = offset + size
            var index = recordBlocks.binarySearchBy(at) { it.decompressedOffset }.let { if (it < 0) -it - 2 else it }
            while (at < end && index in recordBlocks.indices) {
                val block = recordBlocks[index]
                val data = cachedBlock(raf, block)
                val from = (at - block.decompressedOffset).toInt()
                val to = minOf(data.size, (end - block.decompressedOffset).toInt())
                out.write(data, from, to - from)
                at = block.decompressedOffset + to
                index++
            }
        }
        return out.toByteArray()
    }

    /** 正文以文字给出（去掉结尾的 0）。 */
    fun text(offset: Long, size: Int): String = String(record(offset, size), charset).trimEnd('\u0000')

    // 同一个块常被连续查几次（翻相关词条、跟链接），留住最近一块。
    private var lastBlock: BlockInfo? = null
    private var lastData: ByteArray? = null

    @Synchronized
    private fun cachedBlock(raf: RandomAccessFile, block: BlockInfo): ByteArray {
        if (lastBlock === block) return lastData!!
        return readBlock(raf, block).also {
            lastBlock = block
            lastData = it
        }
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    companion object {
        private fun fastDecrypt(data: ByteArray, key: ByteArray): ByteArray {
            val out = data.copyOf()
            var previous = 0x36
            for (i in out.indices) {
                val b = out[i].toInt() and 0xff
                var t = ((b shr 4) or (b shl 4)) and 0xff
                t = t xor previous xor (i and 0xff) xor (key[i % key.size].toInt() and 0xff)
                previous = b
                out[i] = t.toByte()
            }
            return out
        }
    }
}

/** LZO1X 解压（MDict 1.x 的老词典常用）。 */
object Lzo {
    fun decompress(src: ByteArray, expectedSize: Int): ByteArray {
        val out = ByteArrayOutputStream(expectedSize)
        var dst = ByteArray(maxOf(expectedSize, 16))
        var op = 0
        var ip = 0

        fun ensure(n: Int) {
            if (op + n > dst.size) dst = dst.copyOf(maxOf(dst.size * 2, op + n))
        }
        fun literal(n: Int) {
            ensure(n)
            System.arraycopy(src, ip, dst, op, n)
            ip += n
            op += n
        }
        fun copyMatch(distance: Int, n: Int) {
            if (distance <= 0 || distance > op) throw MdictFile.Unsupported("LZO 数据损坏")
            ensure(n)
            var from = op - distance
            repeat(n) { dst[op++] = dst[from++] }
        }
        fun u(i: Int) = src[i].toInt() and 0xff
        fun extend(base: Int): Int {
            var t = 0
            while (u(ip) == 0) {
                t += 255
                ip++
            }
            return t + base + u(ip++)
        }

        var t: Int
        var state: Int // 0：读指令；1：首段字面量之后；2：匹配；3：匹配后的尾随字面量
        if (u(ip) > 17) {
            t = u(ip++) - 17
            if (t < 4) {
                literal(t)
                t = u(ip++)
                state = 2
            } else {
                literal(t)
                state = 1
                t = 0
            }
        } else {
            state = 0
            t = 0
        }
        loop@ while (true) {
            when (state) {
                0 -> {
                    t = u(ip++)
                    if (t >= 16) {
                        state = 2
                        continue@loop
                    }
                    if (t == 0) t = extend(15)
                    literal(t + 3)
                    state = 1
                }
                1 -> {
                    t = u(ip++)
                    if (t >= 16) {
                        state = 2
                        continue@loop
                    }
                    copyMatch(1 + 0x0800 + (t shr 2) + (u(ip++) shl 2), 3)
                    state = 3
                }
                2 -> {
                    when {
                        t >= 64 -> {
                            val distance = 1 + ((t shr 2) and 7) + (u(ip++) shl 3)
                            copyMatch(distance, (t shr 5) - 1 + 2)
                        }
                        t >= 32 -> {
                            var len = t and 31
                            if (len == 0) len = extend(31)
                            val distance = 1 + ((u(ip) shr 2) + (u(ip + 1) shl 6))
                            ip += 2
                            copyMatch(distance, len + 2)
                        }
                        t >= 16 -> {
                            var distance = (t and 8) shl 11
                            var len = t and 7
                            if (len == 0) len = extend(7)
                            distance += (u(ip) shr 2) + (u(ip + 1) shl 6)
                            ip += 2
                            if (distance == 0) break@loop // 结束标记
                            copyMatch(distance + 0x4000, len + 2)
                        }
                        else -> {
                            copyMatch(1 + (t shr 2) + (u(ip++) shl 2), 2)
                        }
                    }
                    state = 3
                }
                3 -> {
                    t = u(ip - 2) and 3
                    if (t == 0) {
                        state = 0
                    } else {
                        literal(t)
                        t = u(ip++)
                        state = 2
                    }
                }
            }
        }
        out.write(dst, 0, op)
        return out.toByteArray()
    }
}

/** RIPEMD-128，用于解开 MDict 加密的关键词索引。 */
object Ripemd128 {
    private val R = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
        3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
        1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
    )
    private val RP = intArrayOf(
        5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
        6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
        15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
        8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
    )
    private val S = intArrayOf(
        11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
        7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
        11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
        11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
    )
    private val SP = intArrayOf(
        8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
        9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
        9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
        15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
    )
    private val K = intArrayOf(0x00000000, 0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC.toInt())
    private val KP = intArrayOf(0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x00000000)

    private fun f(j: Int, x: Int, y: Int, z: Int): Int = when (j) {
        0 -> x xor y xor z
        1 -> (x and y) or (x.inv() and z)
        2 -> (x or y.inv()) xor z
        else -> (x and z) or (y and z.inv())
    }

    fun digest(message: ByteArray): ByteArray {
        val bitLength = message.size.toLong() * 8
        val padded = ByteArrayOutputStream().apply {
            write(message)
            write(0x80)
            while (size() % 64 != 56) write(0)
            for (i in 0 until 8) write(((bitLength ushr (8 * i)) and 0xff).toInt())
        }.toByteArray()
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        val x = IntArray(16)
        for (block in 0 until padded.size / 64) {
            for (i in 0 until 16) {
                val o = block * 64 + i * 4
                x[i] = (padded[o].toInt() and 0xff) or ((padded[o + 1].toInt() and 0xff) shl 8) or
                    ((padded[o + 2].toInt() and 0xff) shl 16) or ((padded[o + 3].toInt() and 0xff) shl 24)
            }
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var ap = h0
            var bp = h1
            var cp = h2
            var dp = h3
            for (j in 0 until 64) {
                val round = j / 16
                var t = Integer.rotateLeft(a + f(round, b, c, d) + x[R[j]] + K[round], S[j])
                a = d; d = c; c = b; b = t
                t = Integer.rotateLeft(ap + f(3 - round, bp, cp, dp) + x[RP[j]] + KP[round], SP[j])
                ap = dp; dp = cp; cp = bp; bp = t
            }
            val t = h1 + c + dp
            h1 = h2 + d + ap
            h2 = h3 + a + bp
            h3 = h0 + b + cp
            h0 = t
        }
        val out = ByteArray(16)
        intArrayOf(h0, h1, h2, h3).forEachIndexed { i, h ->
            for (k in 0 until 4) out[i * 4 + k] = (h ushr (8 * k)).toByte()
        }
        return out
    }
}
