package com.xycs.lingyuereader.data

import java.io.ByteArrayInputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * 解析结果的磁盘缓存。文件布局：
 * ```
 * 魔数 | 索引位置(long) | 各章正文数据…… | 索引（书名、作者、简介、封面、来源指纹、每章标题/序号/字数/位置/长度）
 * ```
 * 打开书时只读索引，章节正文用到时才按位置读出。来源文件变了（指纹不同）或格式升级时缓存作废。
 */
object BookCache {
    private const val MAGIC = 0x4C594331 // "LYC1"

    /** 解析规则或缓存格式改动时加一，让旧缓存全部重建。 */
    const val VERSION = 2

    fun write(file: File, content: BookContent, fingerprint: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            raf.writeInt(MAGIC)
            raf.writeLong(0L) // 索引位置，最后回填
            val offsets = LongArray(content.chapters.size)
            val lengths = IntArray(content.chapters.size)
            content.chapters.forEachIndexed { i, chapter ->
                val bytes = encodeBlocks(chapter.blocks)
                offsets[i] = raf.filePointer
                lengths[i] = bytes.size
                raf.write(bytes)
            }
            val indexAt = raf.filePointer
            val index = java.io.ByteArrayOutputStream()
            DataOutputStream(index).use { out ->
                out.writeInt(VERSION)
                out.writeStr(fingerprint)
                out.writeStr(content.title)
                out.writeStr(content.author)
                out.writeStr(content.description)
                out.writeStr(content.cover)
                out.writeInt(content.chapters.size)
                content.chapters.forEachIndexed { i, c ->
                    out.writeStr(c.title)
                    out.writeInt(c.number ?: -1)
                    out.writeInt(c.part)
                    out.writeInt(c.charCount)
                    out.writeLong(offsets[i])
                    out.writeInt(lengths[i])
                }
            }
            raf.write(index.toByteArray())
            raf.seek(4)
            raf.writeLong(indexAt)
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** 读出索引；缓存不存在、损坏、版本或指纹不符时返回空。章节正文在用到时才从文件读取。 */
    fun read(file: File, fingerprint: String): BookContent? {
        if (!file.isFile) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.readInt() != MAGIC) return null
                val indexAt = raf.readLong()
                raf.seek(indexAt)
                val bytes = ByteArray((raf.length() - indexAt).toInt())
                raf.readFully(bytes)
                val input = DataInputStream(ByteArrayInputStream(bytes))
                if (input.readInt() != VERSION) return null
                if (input.readStr() != fingerprint) return null
                val title = input.readStr()
                val author = input.readStr()
                val description = input.readStr()
                val cover = input.readStr()
                val count = input.readInt()
                val chapters = (0 until count).map {
                    val t = input.readStr()!!
                    val number = input.readInt().takeIf { n -> n >= 0 }
                    val part = input.readInt()
                    val chars = input.readInt()
                    val offset = input.readLong()
                    val length = input.readInt()
                    Chapter(t, number, part, chars) { readBlocks(file, offset, length) }
                }
                BookContent(title, author, chapters, description, cover)
            }
        }.getOrNull()
    }

    private fun readBlocks(file: File, offset: Long, length: Int): List<Block> {
        val bytes = ByteArray(length)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(bytes)
        }
        return decodeBlocks(DataInputStream(ByteArrayInputStream(bytes)))
    }

    private fun encodeBlocks(blocks: List<Block>): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(blocks.size)
            blocks.forEach { b ->
                when (b) {
                    is Block.Para -> { out.writeByte(0); out.writeStr(b.text); out.writeBoolean(b.prose) }
                    is Block.Heading -> { out.writeByte(1); out.writeInt(b.level); out.writeStr(b.text) }
                    is Block.Quote -> { out.writeByte(2); out.writeStr(b.text) }
                    is Block.ListItem -> { out.writeByte(3); out.writeStr(b.marker); out.writeStr(b.text); out.writeInt(b.indent) }
                    is Block.Table -> {
                        out.writeByte(4)
                        out.writeInt(b.rows.size)
                        b.rows.forEach { row -> out.writeInt(row.size); row.forEach { out.writeStr(it) } }
                    }
                    Block.Rule -> out.writeByte(5)
                    Block.SceneBreak -> out.writeByte(6)
                    is Block.Image -> { out.writeByte(7); out.writeStr(b.src); out.writeStr(b.alt) }
                }
            }
        }
        return buffer.toByteArray()
    }

    private fun decodeBlocks(input: DataInput): List<Block> = List(input.readInt()) {
        when (val type = input.readByte().toInt()) {
            0 -> Block.Para(input.readStr()!!, input.readBoolean())
            1 -> Block.Heading(input.readInt(), input.readStr()!!)
            2 -> Block.Quote(input.readStr()!!)
            3 -> Block.ListItem(input.readStr()!!, input.readStr()!!, input.readInt())
            4 -> Block.Table(List(input.readInt()) { List(input.readInt()) { input.readStr()!! } })
            5 -> Block.Rule
            6 -> Block.SceneBreak
            7 -> Block.Image(input.readStr()!!, input.readStr()!!)
            else -> error("未知的块类型 $type")
        }
    }

    // writeUTF 限 64KB，长段落会超，所以自己写长度 + UTF-8 字节。
    private fun DataOutput.writeStr(s: String?) {
        if (s == null) {
            writeInt(-1)
            return
        }
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInput.readStr(): String? {
        val n = readInt()
        if (n < 0) return null
        val bytes = ByteArray(n)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
