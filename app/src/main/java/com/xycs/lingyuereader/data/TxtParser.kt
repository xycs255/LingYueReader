package com.xycs.lingyuereader.data

object TxtParser {

    /** 认不出章名时按此长度切段，避免整本书挤在一章里。 */
    private const val SECTION_CHARS = 6000

    fun parse(text: String, isTitle: (String) -> Boolean = ChapterTitles::isTxtChapterTitle): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var title: String? = null
        var blocks = mutableListOf<Block>()

        fun flush() {
            val t = title
            if (t == null) {
                // 第一章之前的文字（简介、作者的话）有内容才保留。
                if (blocks.any { it.plainText.isNotBlank() }) chapters += Chapter("开篇", blocks)
            } else if (blocks.isNotEmpty()) {
                chapters += ChapterTitles.chapter(t, blocks)
            }
            // 没有正文的章名（如“第一卷 某某”）直接略过。
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim().trim('　').trim()
            if (line.isEmpty()) continue
            if (isTitle(line)) {
                flush()
                title = line.replace(Regex("""[\s　]+"""), " ")
                blocks = mutableListOf()
            } else {
                blocks += Block.Para(line)
            }
        }
        flush()

        if (chapters.size <= 1 && (chapters.firstOrNull()?.charCount ?: 0) > SECTION_CHARS * 2) {
            return split(chapters.single().blocks)
        }
        // 某一章特别长（几十万字，多半是后面的章名格式没认出来）：拆成几节，免得整章一起加载、排版。
        val oversized = SECTION_CHARS * 20
        if (chapters.any { it.charCount > oversized }) {
            return chapters.flatMap { c ->
                if (c.charCount <= oversized) listOf(c)
                else split(c.blocks).mapIndexed { i, section -> Chapter("${c.title}（${i + 1}）", section.blocks, c.number, c.part * 1000 + i + 1) }
            }
        }
        return chapters
    }

    private fun split(blocks: List<Block>): List<Chapter> {
        val sections = mutableListOf<Chapter>()
        var current = mutableListOf<Block>()
        var chars = 0
        for (b in blocks) {
            current += b
            chars += b.plainText.length
            if (chars >= SECTION_CHARS) {
                sections += Chapter("第 ${sections.size + 1} 节", current)
                current = mutableListOf()
                chars = 0
            }
        }
        if (current.isNotEmpty()) sections += Chapter("第 ${sections.size + 1} 节", current)
        return sections
    }
}
