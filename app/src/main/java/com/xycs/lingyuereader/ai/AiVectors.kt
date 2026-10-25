package com.xycs.lingyuereader.ai

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.sqrt

/** 缓存失败必须向读者报告；静默退回会在下一问重复发送可能收费的向量请求。 */
class VectorCacheException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 一本书的原文向量缓存。旧版 LYVE 可读；新版 LYVF 只追加新段落，避免每批重写整本书。
 * 每批先写入并 fsync，再把向量加入内存；失败时保留已经提交的旧批次。
 * 文件损坏、或是旧版留下的超过上限的大缓存：改名留作备份，按空缓存继续（向量可以重建，不能让这本书从此问不了）。
 * 存满时淘汰最早存的段落。
 */
class VectorCache(private val file: File?) {
    private var model: String = ""
    /** 按存入先后排：满了先淘汰最早的。 */
    private val vectors = LinkedHashMap<Long, FloatArray>()
    private var loaded = false
    private var appendable = false
    private var validLength = 0L
    private var storedFloats = 0L

    /** 解析出的缓存：向量（按文件里的先后）、能否接着追加、有效长度、浮点总数。 */
    private class Parsed(
        val vectors: LinkedHashMap<Long, FloatArray> = LinkedHashMap(),
        val appendable: Boolean = false,
        val length: Long = 0L,
        val floats: Long = 0L,
    )

    /** 文件不能用（损坏或超过上限）：改名备份后按空缓存继续。 */
    private class Unusable : Exception()

    @Synchronized
    fun load(forModel: String): Map<Long, FloatArray> {
        if (loaded && model == forModel) return LinkedHashMap(vectors)
        val f = file
        val parsed = if (f != null && f.exists()) read(f, forModel) else Parsed()
        vectors.clear()
        vectors.putAll(parsed.vectors)
        appendable = parsed.appendable
        validLength = parsed.length
        storedFloats = parsed.floats
        model = forModel
        loaded = true
        return LinkedHashMap(vectors)
    }

    /** 一次读进内存再解析（逐个 readFloat 读二十来兆要好几秒）。 */
    private fun read(f: File, forModel: String): Parsed {
        if (f.length() > MAX_FILE_BYTES) return setAside(f)
        val bytes = try {
            f.readBytes()
        } catch (e: IOException) {
            throw VectorCacheException("读取失败：${e.message ?: e.javaClass.simpleName}", e)
        }
        return try {
            parse(bytes, forModel)
        } catch (_: Unusable) {
            setAside(f)
        }
    }

    private fun parse(bytes: ByteArray, forModel: String): Parsed {
        val buf = ByteBuffer.wrap(bytes) // 和 DataOutputStream 一样是大端
        if (buf.remaining() < 6) throw Unusable()
        val magic = buf.int
        if (magic != LEGACY_MAGIC && magic != MAGIC) throw Unusable()
        val nameBytes = buf.short.toInt() and 0xFFFF
        if (nameBytes > buf.remaining()) throw Unusable()
        val name = try {
            DataInputStream(ByteArrayInputStream(bytes, 4, 2 + nameBytes)).readUTF()
        } catch (_: IOException) {
            throw Unusable()
        }
        buf.position(6 + nameBytes)
        // 换了模型：旧向量作废，下次保存时整份重写。
        if (name != forModel) return Parsed()
        val parsed = LinkedHashMap<Long, FloatArray>()
        var floats = 0L
        if (magic == LEGACY_MAGIC) {
            if (buf.remaining() < 4) throw Unusable()
            val count = buf.int
            if (count !in 0..MAX_ENTRIES) throw Unusable()
            repeat(count) {
                val entry = readEntry(buf, MAX_FLOATS - floats) ?: throw Unusable()
                parsed[entry.first] = entry.second
                floats += entry.second.size
            }
            if (buf.hasRemaining()) throw Unusable()
            return Parsed(parsed, appendable = false, length = 0L, floats = floats)
        }
        var length = buf.position().toLong()
        var entries = 0
        while (buf.hasRemaining()) {
            if (entries >= MAX_ENTRIES) throw Unusable()
            val entry = readEntry(buf, MAX_FLOATS - floats) ?: break // 进程死在追加中途：尾部尚未提交
            parsed[entry.first] = entry.second
            floats += entry.second.size
            entries++
            length = buf.position().toLong()
        }
        return Parsed(parsed, appendable = true, length = length, floats = floats)
    }

    /** 读一条；剩下的字节不够一条时返回 null（尾部没写完）。维度异常、总量超限时整个文件不能用。 */
    private fun readEntry(buf: ByteBuffer, remainingFloats: Long): Pair<Long, FloatArray>? {
        if (buf.remaining() < 12) return null
        val key = buf.long
        val dimension = buf.int
        if (dimension !in 1..MAX_DIMENSION) throw Unusable()
        if (dimension > remainingFloats) throw Unusable()
        if (dimension.toLong() * 4 > buf.remaining()) return null
        val value = FloatArray(dimension)
        buf.asFloatBuffer().get(value)
        buf.position(buf.position() + dimension * 4)
        return key to value
    }

    /** 读不了的缓存改名留作备份（只留最近一份），按空缓存继续；改名也失败时才报错。 */
    private fun setAside(f: File): Parsed {
        try {
            Files.move(f.toPath(), File(f.parentFile, f.name + ".damaged").toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw VectorCacheException("缓存文件损坏，也无法移开：${e.message ?: e.javaClass.simpleName}", e)
        }
        return Parsed()
    }

    @Synchronized
    fun put(entries: Map<Long, FloatArray>) {
        if (!loaded) throw VectorCacheException("缓存尚未载入")
        val fresh = entries.filterKeys { it !in vectors }
        if (fresh.isEmpty()) return
        if (fresh.values.any { it.size !in 1..MAX_DIMENSION }) throw VectorCacheException("向量维度异常")
        val newFloats = fresh.values.sumOf { it.size.toLong() }
        if (fresh.size > KEEP_ENTRIES || newFloats > KEEP_FLOATS) throw VectorCacheException("一批向量过多")
        val full = vectors.size.toLong() + fresh.size > MAX_ENTRIES || storedFloats + newFloats > MAX_FLOATS
        // 存满了：从最早存的淘汰起，留到上限的四分之三再整份重写，免得之后每批都要重写整份文件。
        val kept = if (full) newest(KEEP_ENTRIES - fresh.size, KEEP_FLOATS - newFloats) else vectors
        val f = file
        if (f != null) {
            try {
                if (f.parentFile?.isDirectory == false && f.parentFile?.mkdirs() != true) throw IOException("无法创建缓存目录")
                if (appendable && !full) append(f, fresh) else replace(f, LinkedHashMap(kept).apply { putAll(fresh) })
            } catch (e: IOException) {
                throw VectorCacheException("保存失败：${e.message ?: e.javaClass.simpleName}", e)
            }
        }
        if (kept !== vectors) {
            vectors.clear()
            vectors.putAll(kept)
        }
        vectors.putAll(fresh)
        storedFloats = vectors.values.sumOf { it.size.toLong() }
    }

    /** 最近存的那些段落，条数和浮点数都不超过给定的量；按原来的先后排。 */
    private fun newest(maxEntries: Int, maxFloats: Long): LinkedHashMap<Long, FloatArray> {
        val all = vectors.entries.toList()
        var floats = 0L
        var from = all.size
        while (from > 0 && all.size - from < maxEntries && floats + all[from - 1].value.size <= maxFloats) {
            from--
            floats += all[from].value.size
        }
        return LinkedHashMap<Long, FloatArray>().apply { for (i in from until all.size) put(all[i].key, all[i].value) }
    }

    private fun append(f: File, entries: Map<Long, FloatArray>) {
        RandomAccessFile(f, "rw").use { out ->
            if (out.length() < validLength) throw IOException("缓存文件在读取后被修改")
            out.setLength(validLength) // 去掉上次进程中断时的半条尾记录
            out.seek(validLength)
            try {
                out.write(encode(entries)) // 一批一次写完
                out.fd.sync()
                validLength = out.filePointer
            } catch (e: IOException) {
                runCatching { out.setLength(validLength); out.fd.sync() }
                throw e
            }
        }
    }

    private fun replace(f: File, all: Map<Long, FloatArray>) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        try {
            FileOutputStream(tmp).use { stream ->
                DataOutputStream(BufferedOutputStream(stream, 1 shl 16)).use { out ->
                    out.writeInt(MAGIC)
                    out.writeUTF(model)
                    all.entries.chunked(256).forEach { chunk -> out.write(encode(chunk.associate { it.key to it.value })) }
                    out.flush()
                    stream.fd.sync()
                }
            }
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            appendable = true
            validLength = f.length()
        } finally {
            tmp.delete()
        }
    }

    /** 几条记录拼成一段字节，一次写出：键、维度、各分量（大端，和 DataOutputStream 一致）。 */
    private fun encode(entries: Map<Long, FloatArray>): ByteArray {
        val buf = ByteBuffer.allocate(entries.values.sumOf { 12 + it.size * 4 })
        entries.forEach { (key, value) ->
            buf.putLong(key)
            buf.putInt(value.size)
            buf.asFloatBuffer().put(value)
            buf.position(buf.position() + value.size * 4)
        }
        return buf.array()
    }

    companion object {
        const val FILE = "ai-vectors.bin"
        private const val LEGACY_MAGIC = 0x4C595645 // "LYVE"
        private const val MAGIC = 0x4C595646 // "LYVF"
        private const val MAX_DIMENSION = 8192
        /** 至多约 20 MB 浮点数据；加上记录开销和副本仍适合普通手机。 */
        private const val MAX_FLOATS = 5_000_000L
        private const val MAX_ENTRIES = 8_000
        /** 存满时淘汰到这么多（上限的四分之三）。 */
        private const val KEEP_FLOATS = MAX_FLOATS * 3 / 4
        private const val KEEP_ENTRIES = MAX_ENTRIES * 3 / 4
        /** 不超上限的缓存文件最大多长：文件头、全部记录，加上一条没写完的尾记录。更长的必定超限，不读进内存。 */
        private const val MAX_FILE_BYTES = 6L + 65_535 + MAX_ENTRIES * 12L + MAX_FLOATS * 4 + 12 + MAX_DIMENSION * 4L

        /** 段落文字的 64 位指纹（FNV-1a）。 */
        fun key(text: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (ch in text) {
                h = h xor ch.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            return if (na == 0.0 || nb == 0.0) 0f else (dot / sqrt(na * nb)).toFloat()
        }

        /** 倒数排名融合：几种排序里都靠前的段落排在前面。 */
        fun fuse(vararg rankings: List<Int>, k: Int = 60): List<Int> {
            val score = HashMap<Int, Double>()
            rankings.forEach { list -> list.forEachIndexed { rank, i -> score[i] = (score[i] ?: 0.0) + 1.0 / (k + rank + 1) } }
            return score.entries.sortedByDescending { it.value }.map { it.key }
        }
    }
}
