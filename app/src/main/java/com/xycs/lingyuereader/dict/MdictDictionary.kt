package com.xycs.lingyuereader.dict

import java.io.File
import java.util.Locale

/** 导入词典查到的一条：词条名和 HTML 释义。 */
data class MdictEntry(val key: String, val html: String)

/**
 * 一本导入的 MDict 词典。各家制作工具对关键词的排序、大小写处理不一，直接在原文件里二分不可靠，
 * 所以导入时把全部关键词扫一遍，按我们自己的规则（[normalize]）排序写成索引文件，查词只查索引。
 */
class MdictDictionary(
    private val mdx: MdictFile,
    private val index: SortedLines,
    private val resources: List<Pair<MdictFile, SortedLines>>,
    /** 与词典同目录的样式、脚本等文件。 */
    private val looseFiles: Map<String, File>,
) {
    val title: String get() = mdx.title

    fun lookup(word: String, maxEntries: Int = 8): List<MdictEntry> {
        val out = mutableListOf<MdictEntry>()
        val seen = mutableSetOf<String>()
        fun resolve(query: String, depth: Int) {
            for (row in index.matches(normalize(query))) {
                if (out.size >= maxEntries || row.size < 4) return
                val text = mdx.text(row[2].toLong(), row[3].toInt())
                // 词条可能只是“@@@LINK=另一个词”，跳过去（防止循环）。
                val link = text.trim().takeIf { it.startsWith(LINK) }?.removePrefix(LINK)?.trim()
                if (link != null) {
                    if (depth < 5 && seen.add(link)) resolve(link, depth + 1)
                } else if (seen.add("${row[2]}")) {
                    out += MdictEntry(row[1], text)
                }
            }
        }
        resolve(word, 0)
        return out
    }

    fun contains(word: String): Boolean = index.contains(normalize(word))

    /** 释义里引用的图片、样式等：先找 .mdd，再找同目录的文件。 */
    fun resource(path: String): ByteArray? {
        val key = resourceKey(path)
        for ((mdd, idx) in resources) {
            val row = idx.matches(key).firstOrNull() ?: continue
            return mdd.record(row[2].toLong(), row[3].toInt())
        }
        return looseFiles[path.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT)]?.readBytes()
    }

    companion object {
        private const val LINK = "@@@LINK="

        fun normalize(key: String): String = key.trim().lowercase(Locale.ROOT)

        fun resourceKey(path: String): String {
            val p = path.replace('/', '\\').lowercase(Locale.ROOT).trimStart('.')
            return if (p.startsWith("\\")) p else "\\$p"
        }

        /** 扫描词典全部关键词，写成按 [normalize] 排序的索引：规范键\t原词条\t偏移\t长度。 */
        fun buildIndex(file: MdictFile, out: File, isResource: Boolean) {
            val rows = ArrayList<String>(maxOf(16, file.entryCount.toInt()))
            file.forEachKey { key, offset, size ->
                val clean = key.replace('\t', ' ').replace('\n', ' ')
                val norm = if (isResource) resourceKey(clean) else normalize(clean)
                if (norm.isNotEmpty()) rows += "$norm\t$clean\t$offset\t$size"
            }
            rows.sortWith(compareBy { it.substringBefore('\t') })
            val tmp = File(out.parentFile, out.name + ".tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w -> rows.forEach { w.write(it); w.write("\n") } }
            if (!tmp.renameTo(out)) {
                out.delete()
                tmp.renameTo(out)
            }
        }

        /** 打开一本已建好索引的词典。 */
        fun open(mdx: File, mdxIndex: File, mdds: List<Pair<File, File>>, loose: List<File>): MdictDictionary =
            MdictDictionary(
                MdictFile(mdx),
                SortedLines(mdxIndex),
                mdds.map { (f, idx) -> MdictFile(f, isMdd = true) to SortedLines(idx) },
                loose.associateBy { it.name.lowercase(Locale.ROOT) },
            )
    }
}
