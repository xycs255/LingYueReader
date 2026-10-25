package com.xycs.lingyuereader.ai

import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.sqrt

/** 缓存失败必须向读者报告；静默退回会在下一问重复发送可能收费的向量请求。 */
class VectorCacheException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 一本书的原文向量缓存。旧版 LYVE 可读；新版 LYVF 只追加新段落，避免每批重写整本书。
 * 每批先写入并 fsync，再把向量加入内存；失败时保留已经提交的旧批次。
 */
class VectorCache(private val file: File?) {
    private var model: String = ""
    private val vectors = HashMap<Long, FloatArray>()
    private var loaded = false
    private var appendable = false
    private var validLength = 0L
    private var storedFloats = 0L
    private var storedEntries = 0

    @Synchronized
    fun load(forModel: String): Map<Long, FloatArray> {
        if (loaded && model == forModel) return HashMap(vectors)
        val parsed = HashMap<Long, FloatArray>()
        var parsedAppendable = false
        var parsedLength = 0L
        var floats = 0L
        var entries = 0
        val f = file
        if (f != null && f.exists()) {
            try {
                RandomAccessFile(f, "r").use { input ->
                    if (input.length() > MAX_FILE_BYTES) throw VectorCacheException("缓存文件过大")
                    val magic = input.readInt()
                    if (magic != LEGACY_MAGIC && magic != MAGIC) throw VectorCacheException("缓存格式无法识别")
                    if (input.readUTF() != forModel) return@use
                    if (magic == LEGACY_MAGIC) {
                        val count = input.readInt()
                        if (count !in 0..MAX_ENTRIES) throw VectorCacheException("缓存条目数异常")
                        repeat(count) {
                            val entry = readEntry(input, MAX_FLOATS - floats) ?: throw VectorCacheException("旧缓存不完整")
                            parsed[entry.first] = entry.second
                            floats += entry.second.size
                        }
                        entries = count
                        if (input.filePointer != input.length()) throw VectorCacheException("旧缓存末尾有无效数据")
                    } else {
                        parsedAppendable = true
                        parsedLength = input.filePointer
                        while (input.filePointer < input.length()) {
                            if (entries >= MAX_ENTRIES) throw VectorCacheException("缓存条目数异常")
                            val entry = readEntry(input, MAX_FLOATS - floats) ?: break // 进程死在追加中途：尾部尚未提交
                            parsed[entry.first] = entry.second
                            floats += entry.second.size
                            entries++
                            parsedLength = input.filePointer
                        }
                    }
                }
            } catch (e: VectorCacheException) {
                throw e
            } catch (e: IOException) {
                throw VectorCacheException("读取失败：${e.message ?: e.javaClass.simpleName}", e)
            }
        }
        vectors.clear()
        vectors.putAll(parsed)
        appendable = parsedAppendable
        validLength = parsedLength
        storedFloats = floats
        storedEntries = entries
        model = forModel
        loaded = true
        return HashMap(vectors)
    }

    private fun readEntry(input: RandomAccessFile, remainingFloats: Long): Pair<Long, FloatArray>? {
        return try {
            val key = input.readLong()
            val dimension = input.readInt()
            if (dimension !in 1..MAX_DIMENSION) throw VectorCacheException("向量维度异常")
            if (dimension > remainingFloats) throw VectorCacheException("缓存向量总量过大")
            if (dimension.toLong() * 4 > input.length() - input.filePointer) null
            else key to FloatArray(dimension) { input.readFloat() }
        } catch (_: EOFException) {
            null
        }
    }

    @Synchronized
    fun put(entries: Map<Long, FloatArray>) {
        if (!loaded) throw VectorCacheException("缓存尚未载入")
        val fresh = entries.filterKeys { it !in vectors }
        if (fresh.isEmpty()) return
        if (fresh.values.any { it.size !in 1..MAX_DIMENSION }) throw VectorCacheException("向量维度异常")
        val newFloats = fresh.values.sumOf { it.size.toLong() }
        val wasAppendable = appendable
        val currentFloats = if (wasAppendable) storedFloats else vectors.values.sumOf { it.size.toLong() }
        val currentEntries = if (wasAppendable) storedEntries else vectors.size
        if (currentEntries.toLong() + fresh.size > MAX_ENTRIES || currentFloats + newFloats > MAX_FLOATS) throw VectorCacheException("缓存容量已满")
        val projectedBytes = 6L + model.length * 3L + (currentEntries.toLong() + fresh.size) * 12L + (currentFloats + newFloats) * 4L
        if (projectedBytes > MAX_FILE_BYTES || (appendable && validLength + fresh.size * 12L + newFloats * 4L > MAX_FILE_BYTES)) {
            throw VectorCacheException("缓存文件将超过大小上限")
        }
        val f = file
        if (f != null) {
            try {
                if (f.parentFile?.isDirectory == false && f.parentFile?.mkdirs() != true) throw IOException("无法创建缓存目录")
                if (appendable) append(f, fresh) else replace(f, vectors + fresh)
            } catch (e: IOException) {
                throw VectorCacheException("保存失败：${e.message ?: e.javaClass.simpleName}", e)
            }
        }
        vectors.putAll(fresh)
        storedFloats = currentFloats + newFloats
        storedEntries = currentEntries + fresh.size
    }

    private fun append(f: File, entries: Map<Long, FloatArray>) {
        RandomAccessFile(f, "rw").use { out ->
            if (out.length() < validLength) throw IOException("缓存文件在读取后被修改")
            out.setLength(validLength) // 去掉上次进程中断时的半条尾记录
            out.seek(validLength)
            try {
                entries.forEach { (key, value) -> writeEntry(out, key, value) }
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
                DataOutputStream(stream).use { out ->
                    out.writeInt(MAGIC)
                    out.writeUTF(model)
                    all.forEach { (key, value) ->
                        out.writeLong(key)
                        out.writeInt(value.size)
                        value.forEach(out::writeFloat)
                    }
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

    private fun writeEntry(out: RandomAccessFile, key: Long, value: FloatArray) {
        out.writeLong(key)
        out.writeInt(value.size)
        value.forEach(out::writeFloat)
    }

    companion object {
        const val FILE = "ai-vectors.bin"
        private const val LEGACY_MAGIC = 0x4C595645 // "LYVE"
        private const val MAGIC = 0x4C595646 // "LYVF"
        private const val MAX_DIMENSION = 8192
        /** 至多约 20 MB 浮点数据；加上记录开销和副本仍适合普通手机。 */
        private const val MAX_FLOATS = 5_000_000L
        private const val MAX_ENTRIES = 8_000
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024

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
