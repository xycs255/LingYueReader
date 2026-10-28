package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

/**
 * 故事账本：故事走到哪了。设定包里作者写的文档（总纲、硬性设定、写作规范……）基本不变，是“宪法”；
 * 账本记的是随章推进、会变的东西——人物此刻的状态、发生过的事、谁知道了什么、埋了没收的伏笔、故事里的时间。
 * 每一条带“从第几章起”（按设定算的章号，见 [chapterNo]），写某一章时只取“到这一章为止”的那一份（[LedgerSnapshot]）。
 */
enum class LedgerKind(val label: String, val hint: String) {
    STATE("人物状态", "人物此刻在哪、身体怎样、带着什么、想要什么、和谁什么关系；同一方面以最新的一条为准"),
    EVENT("发生的事", "已经发生、会影响后文的事，世界的变化（谁死了、哪座城破了、东西到了谁手里）"),
    KNOW("知情", "谁从第几章起知道了什么、消息从哪来；谁瞒着谁、误会了什么"),
    PROMISE("伏笔", "埋下的伏笔、悬而未决的问题、约好的事：第几章埋、计划第几章收、收了没有"),
    TIME("时间线", "这一章在故事里是什么时候、离上一章过了多久"),
}

/** 一条是怎么来的：从设定包导入、写完一章整理出来的、作者自己加的。 */
enum class LedgerOrigin(val label: String) { IMPORT("从设定包导入"), SYNC("整理正文"), MANUAL("手动添加") }

/** 人物状态常用的几个方面（同一人物同一方面，新的一条顶替旧的）。 */
val LEDGER_ASPECTS = listOf("位置", "身体", "随身", "目标", "关系", "情绪", "身份")

data class LedgerEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: LedgerKind,
    /** 内容：一句话的事实。 */
    val text: String,
    /** 人物状态、知情是“谁”；其余可空（写了就按人物归类、按出场挑选）。 */
    val subject: String = "",
    /** 人物状态的哪一方面（[LEDGER_ASPECTS] 或作者自己写的）；空着的不顶替别的。 */
    val aspect: String = "",
    /** 从第几章起成立（伏笔是第几章埋下）；空着是一开始就成立。 */
    val from: Int? = null,
    /** 只知道从第几卷起时（察觉表里写“第二卷”的）；[from] 有值时不看它。 */
    val volume: Int? = null,
    /** 从第几章起不再成立（比如伤好了、人离开了）；空着是一直成立。 */
    val until: Int? = null,
    /** 伏笔：计划第几章收。 */
    val payoff: Int? = null,
    /** 伏笔：收了没有。 */
    val done: Boolean = false,
    /** 伏笔：第几章收的；收了但不知道哪章的空着。 */
    val closed: Int? = null,
    /** 知情：消息从哪来。 */
    val via: String = "",
    /** 只有作者知道的（结局、后文走向、对手的底牌）：只给 AI 把握方向，不能写进正文。 */
    val secret: Boolean = false,
    /** 依据：正文里的原句，或导入时设定包里的原文。 */
    val quote: String = "",
    /** 整理出这一条的那一章（id）；导入、手动加的为空。 */
    val chapterId: String = "",
    val origin: LedgerOrigin = LedgerOrigin.MANUAL,
    val updatedAt: Long = 0,
) {
    /** 到第 [chapter] 章（第 [volume] 卷）为止成立了没有。 */
    fun reached(chapter: Int, volume: Int? = null): Boolean =
        from?.let { it <= chapter } ?: this.volume?.let { v -> volume == null || v <= volume } ?: true

    /** 到第 [chapter] 章还成立：已经成立、还没失效。 */
    fun validAt(chapter: Int, volume: Int? = null): Boolean = reached(chapter, volume) && (until == null || until > chapter)

    /** 伏笔在第 [chapter] 章还开着：已经埋下、还没收（收在后面章的，这一章还算开着）。 */
    fun openAt(chapter: Int, volume: Int? = null): Boolean {
        if (kind != LedgerKind.PROMISE || !reached(chapter, volume)) return false
        if (!done) return true
        val at = closed ?: return false
        return at > chapter
    }

    /**
     * 用来认重复：类别、谁、哪一方面、内容（去掉空白和标点差异）。人物状态、时间还要算上从第几章起：
     * 先“在渡口”、去了京城、后来又“在渡口”，是新的一条。只有作者知道的另算：“林远不知道这件事”和“林远知道了这件事”是两条。
     */
    val key: String
        get() = kind.name + "|" + subject.trim() + "|" + aspect.trim() + "|" + ChapterSyncs.normalize(text) +
            (if (kind == LedgerKind.STATE || kind == LedgerKind.TIME) "|" + (from ?: -1) else "") + if (secret) "|secret" else ""

    /** “第 12 章起”“第 3 章埋，计划第 9 章收”这类说明。 */
    val whenLabel: String
        get() {
            val parts = mutableListOf<String>()
            when (kind) {
                LedgerKind.PROMISE -> {
                    from?.let { parts += "第 $it 章埋" } ?: volume?.let { parts += "第 $it 卷埋" }
                    if (done) parts += closed?.let { "第 $it 章收" } ?: "已收" else payoff?.let { parts += "计划第 $it 章收" }
                }
                else -> {
                    from?.let { parts += "第 $it 章起" } ?: volume?.let { parts += "第 $it 卷起" }
                    until?.let { parts += "第 $it 章止" }
                }
            }
            return parts.joinToString("，")
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id).put("kind", kind.name).put("text", text)
        if (subject.isNotEmpty()) put("subject", subject)
        if (aspect.isNotEmpty()) put("aspect", aspect)
        from?.let { put("from", it) }
        volume?.let { put("volume", it) }
        until?.let { put("until", it) }
        payoff?.let { put("payoff", it) }
        if (done) put("done", true)
        closed?.let { put("closed", it) }
        if (via.isNotEmpty()) put("via", via)
        if (secret) put("secret", true)
        if (quote.isNotEmpty()) put("quote", quote)
        if (chapterId.isNotEmpty()) put("chapterId", chapterId)
        put("origin", origin.name).put("updatedAt", updatedAt)
    }

    companion object {
        private val fields = setOf(
            "id", "kind", "text", "subject", "aspect", "from", "volume", "until", "payoff", "done", "closed",
            "via", "secret", "quote", "chapterId", "origin", "updatedAt",
        )

        private fun JSONObject.checkFields() {
            val names = keys()
            while (names.hasNext()) {
                val name = names.next()
                require(name in fields) { "故事账本含有无法识别的字段：$name" }
            }
        }

        private fun JSONObject.string(key: String): String {
            val value = opt(key)
            require(value == null || value == JSONObject.NULL || value is String) { "故事账本 $key 不是文字" }
            return (value as? String).orEmpty()
        }

        private fun JSONObject.integer(key: String): BigDecimal? {
            if (!has(key) || isNull(key)) return null
            val value = opt(key)
            require(value is Number || value is String) { "故事账本 $key 不是整数" }
            return runCatching { BigDecimal(value.toString()) }.getOrElse {
                throw IllegalArgumentException("故事账本 $key 不是整数", it)
            }
        }

        private fun JSONObject.int(key: String): Int? = integer(key)?.let { value ->
            val number = runCatching { value.intValueExact() }.getOrNull()
            require(number != null && number >= 0) { "故事账本 $key 不是非负整数" }
            number
        }

        private fun JSONObject.long(key: String): Long? = integer(key)?.let { value ->
            val number = runCatching { value.longValueExact() }.getOrNull()
            require(number != null && number >= 0) { "故事账本 $key 不是非负整数" }
            number
        }

        private fun JSONObject.boolean(key: String): Boolean {
            if (!has(key) || isNull(key)) return false
            return when (val value = opt(key)) {
                is Boolean -> value
                is String -> when {
                    value.equals("true", ignoreCase = true) -> true
                    value.equals("false", ignoreCase = true) -> false
                    else -> throw IllegalArgumentException("故事账本 $key 不是布尔值")
                }
                else -> throw IllegalArgumentException("故事账本 $key 不是布尔值")
            }
        }

        /** 类别或正文无效时返回 null；其余已存在却损坏的字段抛错，避免静默丢失。 */
        fun fromJson(o: JSONObject): LedgerEntry? {
            o.checkFields()
            val kind = (o.opt("kind") as? String)?.let { runCatching { LedgerKind.valueOf(it) }.getOrNull() } ?: return null
            val text = (o.opt("text") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val origin = if (!o.has("origin") || o.isNull("origin")) LedgerOrigin.MANUAL else
                runCatching { LedgerOrigin.valueOf(o.string("origin")) }.getOrElse {
                    throw IllegalArgumentException("故事账本 origin 无效", it)
                }
            return LedgerEntry(
                id = o.string("id").ifEmpty { UUID.randomUUID().toString() },
                kind = kind,
                text = text,
                subject = o.string("subject").trim(),
                aspect = o.string("aspect").trim(),
                from = o.int("from"),
                volume = o.int("volume"),
                until = o.int("until"),
                payoff = o.int("payoff"),
                done = o.boolean("done"),
                closed = o.int("closed"),
                via = o.string("via").trim(),
                secret = o.boolean("secret"),
                quote = o.string("quote"),
                chapterId = o.string("chapterId"),
                origin = origin,
                updatedAt = o.long("updatedAt") ?: 0,
            )
        }
    }
}

/** 对账本的一次改动；一批改动要么全写进去，要么都不写（[StoryLedger.apply]）。 */
sealed interface LedgerOp {
    /** 加一条；和已有的重复（[LedgerEntry.key] 相同）时不加。 */
    data class Add(val entry: LedgerEntry) : LedgerOp
    /**
     * 换掉 [entry].id 那一条。[expected] 是改之前看到的那一条（界面上的、撤销时刚写进去的）：
     * 账本里那一条已经不是这个样子（别处改过、删了）就整批拒绝（[LedgerConflict]），不拿旧的覆盖后来的修改。
     */
    data class Update(val entry: LedgerEntry, val expected: LedgerEntry? = null) : LedgerOp
    /** 伏笔收了：第 [chapter] 章（不知道哪章为空）。 */
    data class Close(val id: String, val chapter: Int?, val quote: String = "", val chapterId: String = "") : LedgerOp
    /** 删掉一条；[expected] 同 [Update]。 */
    data class Remove(val id: String, val expected: LedgerEntry? = null) : LedgerOp
}

/** 要改、要删的那一条已经被改过（或删了）：这一批都不写，界面刷新后再改。 */
class LedgerConflict(val id: String) : IllegalStateException("这条已经被改过，请刷新后再改")

/** 账本的一个历史版本：改之前的样子、什么时候、因为什么改的。 */
data class LedgerVersion(val at: Long, val label: String, val count: Int)

object StoryLedger {
    /** 账本文件的格式版本。 */
    const val FORMAT = 1

    fun toJson(entries: List<LedgerEntry>): JSONObject =
        JSONObject().put("format", FORMAT).put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })

    /** 读账本；任一条无法读取时整份报错，由调用方另存原文件，避免下一次保存静默丢掉坏条目。 */
    fun parse(raw: String): List<LedgerEntry> {
        val o = JSONObject(raw)
        if (o.has("format")) {
            require(runCatching { BigDecimal(o.get("format").toString()).intValueExact() == FORMAT }.getOrDefault(false)) {
                "故事账本格式版本不受支持"
            }
        }
        val a = o.getJSONArray("entries")
        return (0 until a.length()).map { index ->
            requireNotNull(LedgerEntry.fromJson(a.getJSONObject(index))) { "故事账本第 ${index + 1} 条无效" }
        }
    }

    /**
     * 把一批改动用在 [entries] 上，返回新的账本和实际加了几条（重复的不加）。[now] 记进改到的条目。
     * 找不到 id 的改动跳过（作者在别处删了那一条）；带着改之前样子的改动（[LedgerOp.Update.expected]、[LedgerOp.Remove.expected]）
     * 对不上账本里现在的那一条时抛 [LedgerConflict]，整批不算。
     */
    fun apply(entries: List<LedgerEntry>, ops: List<LedgerOp>, now: Long): Pair<List<LedgerEntry>, Int> {
        val out = entries.toMutableList()
        val keys = out.map { it.key }.toMutableSet()
        var added = 0
        ops.forEach { op ->
            when (op) {
                is LedgerOp.Add -> {
                    val e = op.entry.copy(text = op.entry.text.trim(), subject = op.entry.subject.trim(), aspect = op.entry.aspect.trim(), updatedAt = now)
                    if (e.text.isEmpty() || e.key in keys || out.any { it.id == e.id }) return@forEach
                    out += e
                    keys += e.key
                    added++
                }
                is LedgerOp.Update -> {
                    val i = out.indexOfFirst { it.id == op.entry.id }
                    if (op.expected != null && out.getOrNull(i) != op.expected) throw LedgerConflict(op.entry.id)
                    if (i >= 0 && op.entry.text.isNotBlank()) {
                        keys -= out[i].key
                        out[i] = op.entry.copy(text = op.entry.text.trim(), subject = op.entry.subject.trim(), aspect = op.entry.aspect.trim(), updatedAt = now)
                        keys += out[i].key
                    }
                }
                is LedgerOp.Close -> {
                    val i = out.indexOfFirst { it.id == op.id && it.kind == LedgerKind.PROMISE }
                    if (i >= 0) out[i] = out[i].copy(
                        done = true, closed = op.chapter,
                        quote = op.quote.ifEmpty { out[i].quote }, chapterId = op.chapterId.ifEmpty { out[i].chapterId }, updatedAt = now,
                    )
                }
                is LedgerOp.Remove -> {
                    val i = out.indexOfFirst { it.id == op.id }
                    if (op.expected != null && out.getOrNull(i) != op.expected) throw LedgerConflict(op.id)
                    if (i >= 0) keys -= out.removeAt(i).key
                }
            }
        }
        return out to added
    }

    /** 到第 [chapter] 章（第 [volume] 卷）为止的账本；[present] 是这一章出场的人物（名字、别名），为空时不按人物筛。 */
    fun snapshot(entries: List<LedgerEntry>, chapter: Int, volume: Int? = null, present: Collection<String> = emptyList()): LedgerSnapshot {
        val names = present.filter { it.isNotBlank() }.toSet()
        fun concerns(e: LedgerEntry) = names.isEmpty() || e.subject.isBlank() || e.subject in names || names.any { it.length >= 2 && it in e.text }
        // 人物状态：同一人物同一方面取最新的一条（从第几章起最大的；一样的取后改的）；没写方面的各算各的。
        val states = entries.filter { it.kind == LedgerKind.STATE && it.validAt(chapter, volume) && !it.secret }
            .groupBy { if (it.aspect.isBlank()) it.id else it.subject + "|" + it.aspect }
            .values.map { group -> group.maxWith(compareBy<LedgerEntry>({ it.from ?: -1 }, { it.updatedAt })) }
            .filter { names.isEmpty() || it.subject in names || it.subject.isBlank() }
            .sortedWith(compareBy({ it.subject }, { LEDGER_ASPECTS.indexOf(it.aspect).let { i -> if (i < 0) 99 else i } }))
        val events = entries.filter { it.kind == LedgerKind.EVENT && it.validAt(chapter, volume) && !it.secret }
            .sortedByDescending { it.from ?: -1 }
        val knowAll = entries.filter { it.kind == LedgerKind.KNOW && !it.secret && concerns(it) }
        val known = knowAll.filter { it.validAt(chapter, volume) }.sortedByDescending { it.from ?: -1 }
        val notYet = knowAll.filter { !it.reached(chapter, volume) }.sortedBy { it.from ?: Int.MAX_VALUE }
        val open = entries.filter { it.openAt(chapter, volume) && !it.secret }.sortedBy { it.from ?: -1 }
        val due = open.filter { it.payoff == chapter }
        val overdue = open.filter { p -> p.payoff != null && p.payoff < chapter }
        // 时间：这一章写了的；没写就用前面最近的一条。
        val times = entries.filter { it.kind == LedgerKind.TIME && it.reached(chapter, volume) }
        val time = times.filter { it.from == chapter }.ifEmpty { listOfNotNull(times.maxByOrNull { it.from ?: -1 }) }
        // 只有作者知道的到了“第几章止”（人物在那一章得知了）就不再是秘密。
        val secrets = entries.filter { it.secret && it.validAt(chapter, volume) && concerns(it) }
        return LedgerSnapshot(chapter, states, events, known, notYet, open, due, overdue, time, secrets)
    }
}

/** 到某一章为止的账本：写这一章时带给 AI，也在账本页“翻到第几章”时看。 */
data class LedgerSnapshot(
    val chapter: Int,
    val states: List<LedgerEntry>,
    val events: List<LedgerEntry>,
    val known: List<LedgerEntry>,
    val notYet: List<LedgerEntry>,
    val open: List<LedgerEntry>,
    val due: List<LedgerEntry>,
    val overdue: List<LedgerEntry>,
    val time: List<LedgerEntry>,
    val secrets: List<LedgerEntry>,
) {
    val isEmpty: Boolean get() = states.isEmpty() && events.isEmpty() && known.isEmpty() && notYet.isEmpty() && open.isEmpty() && time.isEmpty() && secrets.isEmpty()

    /**
     * 拼成给 AI 的文字，最多 [limit] 字：时间、人物状态、计划这一章收的伏笔放前面（最要紧），
     * 发生过的事、知情、开着的伏笔按离这一章近的先放，放不下的省略。为空返回 null。
     * [prose] 为真时是写正文用的：后面才发生的事照样列着（好做铺垫），但不说第几章，只说“后面才会知道”“后面才收”，
     * 并告诉模型这一章能做到哪一步——留个引子、轻轻提一笔，不说破、不收。
     */
    fun render(limit: Int = 4_000, prose: Boolean = false): String? {
        if (isEmpty) return null
        val out = StringBuilder("【故事账本：到第 $chapter 章为止的故事状态（以它为准；和设定包冲突时以账本为准，因为它记的是最新进展）】\n")
        var left = limit - out.length
        fun section(title: String, lines: List<String>) {
            if (lines.isEmpty() || left < 80) return
            val head = "\n$title\n"
            val body = StringBuilder()
            var skipped = 0
            lines.forEach { l ->
                val line = "- " + l.take(240) + "\n"
                if (left - head.length - body.length - line.length < 0) skipped++ else body.append(line)
            }
            if (body.isEmpty()) return
            if (skipped > 0) body.append("（还有 $skipped 条放不下，没列）\n")
            out.append(head).append(body)
            left -= head.length + body.length
        }
        fun at(e: LedgerEntry) = e.whenLabel.let { if (it.isEmpty()) "" else "（$it）" }
        section("故事里的时间：", time.map { it.text + at(it) })
        section("人物此刻的状态：", states.map { e ->
            (if (e.subject.isNotBlank()) e.subject + (if (e.aspect.isNotBlank()) "·" + e.aspect else "") + "：" else "") + e.text + at(e)
        })
        section("计划在这一章收的伏笔：", due.map { it.text + at(it) })
        section("该收没收的伏笔（计划的章已经过了）：", overdue.map { it.text + at(it) })
        section("已经发生、会影响后文的事：", events.map { it.text + at(it) })
        section("谁知道什么（到这一章为止）：", known.map { e ->
            (if (e.subject.isNotBlank()) e.subject + "：" else "") + e.text + at(e) + (if (e.via.isNotBlank()) "，消息来自" + e.via else "")
        })
        val waiting = open - due.toSet() - overdue.toSet()
        if (prose) {
            // 只说哪章埋的（已经写过的事），不说计划哪章收：说了就是告诉模型后面第几章发生什么。
            section("埋下了还没收的伏笔：", waiting.map { e ->
                e.text + (e.from?.let { "（第 $it 章埋）" } ?: e.volume?.let { "（第 $it 卷埋）" }).orEmpty() + "——这条伏笔后面才收，这一章可以轻轻提一笔，别收。"
            })
            section("人物现在还不知道的事：", notYet.map { e ->
                val who = e.subject.ifBlank { "人物" }
                e.text.trimEnd('。') + "——这件事${who}现在还不知道，后面才会知道。这一章别让${who}知道或说破，可以留个引子。"
            })
        } else {
            section("埋下了还没收的伏笔（别忘了，也别提前揭开）：", waiting.map { it.text + at(it) })
            section("到这一章人物还不知道的事（不要让人物提前知道或说破）：", notYet.map { e ->
                (if (e.subject.isNotBlank()) e.subject + "要到" else "要到") + (e.from?.let { "第 $it 章" } ?: e.volume?.let { "第 $it 卷" } ?: "后面") + "才知道：" + e.text
            })
        }
        section("只有作者知道的（只用来把握方向，不能写进正文，旁白也不能说破）：", secrets.map { e ->
            (if (e.subject.isNotBlank()) e.subject + "——" else "") + e.text
        })
        return out.toString().trim()
    }
}
