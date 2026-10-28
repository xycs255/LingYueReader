package com.xycs.lingyuereader.data

/**
 * 测内存用的大设定包（全是虚构内容）：把 prose-fixture 的设定扩写、重复，凑出一份五十多万字、带“来源文件：”分节的合订本，
 * 再加几十份普通文档和二十多章定稿。总字数一百二十万上下，接近作者真机上的量级。
 */
object LargePack {
    private val names = listOf(
        "工作说明" to PackKind.INSTRUCTION, "正文创作提示词" to PackKind.INSTRUCTION,
        "章节审读提示词" to PackKind.INSTRUCTION, "章节修订提示词" to PackKind.INSTRUCTION,
        "总纲" to PackKind.OUTLINE, "不变量" to PackKind.INVARIANT,
        "第一卷细纲" to PackKind.VOLUME, "第二卷细纲" to PackKind.VOLUME, "世界设定" to PackKind.CANON,
        "人物档案" to PackKind.CHARACTER, "察觉表" to PackKind.AWARENESS,
        "伏笔表" to PackKind.FORESHADOW, "写作规范" to PackKind.STYLE,
        "称谓与语气" to PackKind.VOICE, "检查规则" to PackKind.RULES,
    )

    fun res(path: String): String =
        requireNotNull(LargePack::class.java.getResourceAsStream("/prose-fixture/$path")).use { it.readBytes().decodeToString() }

    /** 合订本：每一轮把几份长资料换个编号再抄一遍，正文前加一行版次，免得被当成同一份去重。 */
    fun compilation(minChars: Int = 520_000): String = buildString {
        val bodies = listOf("人物档案", "世界设定", "伏笔表", "察觉表", "总纲").map { it to res("pack/$it.md") }
        var round = 0
        var index = 0
        while (length < minChars) {
            round++
            bodies.forEach { (name, text) ->
                index++
                append("════════════\n【").append(index.toString().padStart(2, '0')).append("】").append(name).append(round).append('\n')
                append("来源文件：资料").append(round).append('/').append(name).append(round).append(".md\n════════════\n\n")
                append("（第 ").append(round).append(" 轮整理，老周口述、杜娘记）\n")
                append(text.replace("林月", if (round % 2 == 0) "林月" else "林小月")).append("\n\n")
            }
        }
    }

    /** 普通文档：人物、世界、资料各若干份，每份一万五千字上下。 */
    fun ordinary(count: Int = 40): List<Triple<String, PackKind, String>> {
        val base = listOf(res("pack/人物档案.md"), res("pack/世界设定.md"), res("ch1.txt"), res("ch2.txt"))
        val kinds = listOf(PackKind.CHARACTER, PackKind.CANON, PackKind.REFERENCE, PackKind.REFERENCE)
        return (1..count).map { i ->
            val text = buildString {
                append("# 附录").append(i).append("：顾长风手记\n\n")
                var k = i
                while (length < 15_000) { append("## 第").append(k).append("节\n").append(base[k % base.size].take(4_000)).append("\n\n"); k++ }
            }
            Triple("附录$i", kinds[i % kinds.size], text)
        }
    }

    /** 建一部带大设定包的作品，返回作品 id 和要写的那一章 id。前面 [finals] 章是定稿。 */
    fun build(store: WorkStore, finals: Int = 24): Pair<String, String> {
        val w = store.create("渡灯")
        names.forEach { (name, kind) -> store.addPackDoc(w.id, kind, name, res("pack/$name.md")) }
        store.addPackDoc(w.id, PackKind.REFERENCE, "设定合订本", compilation())
        ordinary().forEach { (title, kind, text) -> store.addPackDoc(w.id, kind, title, text) }
        val volume = store.get(w.id)!!.volumes[0].id
        val prose = listOf(res("ch1.txt"), res("ch2.txt"))
        var chapterId = w.chapters[0].id
        for (n in 1..finals + 1) {
            if (n > 1) chapterId = store.addChapter(w.id, volume)!!.second.id
            store.renameChapter(w.id, chapterId, "第${n}章 雾渡$n")
            if (n <= finals) {
                store.saveText(w.id, chapterId, prose[n % 2].trim(), count = false)
                store.saveCard(w.id, chapterId, ChapterCard(summary = "第 $n 章：林月在渡口打听老周。"))
                store.setStatus(w.id, chapterId, ChapterStatus.FINAL)
            }
        }
        store.saveCard(w.id, chapterId, ChapterCard(facts = "杜娘转述老周约林月卯时渡口船上见。", pov = "林月", hook = "窗下脚步声。",
            beats = "1. 顾长风在茶馆问话（900字）\n2. 杜娘交出灯笼提杆（900字）\n3. 沈默在船上等（900字）\n4. 林月上船（900字）"))
        return w.id to chapterId
    }

    fun totalChars(store: WorkStore, workId: String): Int = store.pack(workId).sumOf { store.packText(workId, it.id).length }
}
