package com.xycs.lingyuereader.ai

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * 一本书的原文向量缓存（书目录里的 ai-vectors.bin）：按段落文字的指纹存，换了向量模型就作废。
 * 读到新内容时只补新段落。
 */
class VectorCache(private val file: File?) {
    private var model: String = ""
    private val vectors = HashMap<Long, FloatArray>()
    private var loaded = false

    @Synchronized
    fun load(forModel: String): Map<Long, FloatArray> {
        if (!loaded || model != forModel) {
            vectors.clear()
            model = forModel
            runCatching {
                DataInputStream(file!!.inputStream().buffered()).use { input ->
                    if (input.readInt() != MAGIC) return@use
                    if (input.readUTF() != forModel) return@use
                    repeat(input.readInt()) {
                        val key = input.readLong()
                        val v = FloatArray(input.readInt()) { input.readFloat() }
                        vectors[key] = v
                    }
                }
            }
            loaded = true
        }
        return HashMap(vectors)
    }

    @Synchronized
    fun put(entries: Map<Long, FloatArray>) {
        vectors.putAll(entries)
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeUTF(model)
                out.writeInt(vectors.size)
                vectors.forEach { (k, v) ->
                    out.writeLong(k)
                    out.writeInt(v.size)
                    v.forEach(out::writeFloat)
                }
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    companion object {
        const val FILE = "ai-vectors.bin"
        private const val MAGIC = 0x4C595645 // "LYVE"

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
