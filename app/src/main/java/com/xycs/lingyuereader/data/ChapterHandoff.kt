package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** 交接卡里的一个在场人物：在做什么、什么状态（受伤、情绪等）。看不出来的空着。 */
data class HandoffPerson(val name: String, val doing: String = "", val state: String = "") {
    /** 写成一行：“林月：守在门口；左臂带伤”。编辑框里一行一人，读回来见 [Handoffs.person]。 */
    fun line(): String = buildString {
        append(name)
        val rest = listOf(doing, state).map { it.trim() }.filter { it.isNotEmpty() }
        if (rest.isNotEmpty()) append("：").append(rest.joinToString("；"))
    }
}

/** 交接卡是怎么来的：本机从结尾抽的、AI 整理的、作者改过的（作者改过的不被自动重新整理盖掉）。 */
enum class HandoffOrigin(val label: String) { LOCAL("本机从结尾抽的"), AI("AI 整理的"), AUTHOR("你改过的") }

/**
 * 一章的交接卡：这一章结尾时的状态，给下一章开头接住用。按章 id 存（移动章节不受影响），
 * 连同整理时正文的指纹（[hash]）和章的修改时间（[textAt]），用来判断正文后来改过没有。
 * [hook] 是章末钩子：细纲卡里有就以细纲卡为准（见 [Handoffs.withCardHook]）。
 */
data class ChapterHandoff(
    val scene: String = "",
    val people: List<HandoffPerson> = emptyList(),
    val mood: String = "",
    /** 没说完的话、没解决的悬念或冲突，最多 [Handoffs.MAX_OPEN] 条。 */
    val open: List<String> = emptyList(),
    val hook: String = "",
    val origin: HandoffOrigin = HandoffOrigin.LOCAL,
    val hash: String = "",
    val textAt: Long = 0,
    val at: Long = 0,
) {
    val edited: Boolean get() = origin == HandoffOrigin.AUTHOR

    /** 什么都没抽出来（本机抽不到人名、地点时）。 */
    val isEmpty: Boolean get() = scene.isBlank() && people.isEmpty() && mood.isBlank() && open.isEmpty() && hook.isBlank()

    /**
     * 写进请求、界面上看的几行；[withHook] 为假时不写钩子（任务卡里“上一章停在”已经写了同一个钩子）。空的项不写。
     */
    fun lines(withHook: Boolean = true): List<String> = buildList {
        if (scene.isNotBlank()) add("场景：" + scene.trim())
        if (people.isNotEmpty()) add("在场人物：" + people.joinToString("；") { it.line() })
        if (mood.isNotBlank()) add("情绪基调：" + mood.trim())
        open.filter { it.isNotBlank() }.forEach { add("没了结的：" + it.trim()) }
        if (withHook && hook.isNotBlank()) add("章末钩子：" + hook.trim())
    }

    fun toJson(): JSONObject = JSONObject()
        .put("scene", scene).put("mood", mood).put("hook", hook)
        .put("people", JSONArray().apply { people.forEach { put(JSONObject().put("name", it.name).put("doing", it.doing).put("state", it.state)) } })
        .put("open", JSONArray(open))
        .put("origin", origin.name).put("hash", hash).put("textAt", textAt).put("at", at)
}

/** 交接卡在请求里、界面上怎么用，怎么在本机抽、怎么读 AI 回的 JSON，怎么判断过期。 */
object Handoffs {
    /** 没说完的话、悬念最多几条。 */
    const val MAX_OPEN = 3

    /** 请 AI 整理时只给上一章最后多少字。 */
    const val AI_CHARS = 3_000

    /** 本机抽的时候看最后几段、最多多少字。 */
    private const val LOCAL_PARAGRAPHS = 3
    private const val LOCAL_CHARS = 1_500

    /** 正文的指纹：整理时记下，之后正文改过（指纹不同）就算过期。 */
    fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * 存着的交接卡跟正文还对得上吗：章的修改时间没变就不读正文、不算指纹（几百章的作品也不每次重算）；
     * 变了才按 [text] 现算指纹比一比（只改了状态、章名，正文没变的照样算没过期）。
     */
    fun fresh(card: ChapterHandoff, updatedAt: Long, text: () -> String): Boolean =
        (card.textAt != 0L && card.textAt == updatedAt) || card.hash == hash(text())

    /** 章末钩子：细纲卡里写了就以细纲卡为准。 */
    fun withCardHook(card: ChapterHandoff, cardHook: String): ChapterHandoff =
        if (cardHook.isBlank()) card else card.copy(hook = cardHook.trim())

    /**
     * 本机从结尾几段里抽：在场的人（[names] 是人物档案、账本里的人名，按最后出现的先后排），最后出现的地点（[places]，
     * 账本里记过的“位置”），结尾那段里没答上的问话。拿不准的不填：人物在做什么、什么状态，情绪基调都空着，等 AI 或作者补。
     */
    fun local(text: String, names: Collection<String>, places: Collection<String>, hook: String = ""): ChapterHandoff {
        val paragraphs = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val tail = paragraphs.takeLast(LOCAL_PARAGRAPHS).joinToString("\n").takeLast(LOCAL_CHARS)
        if (tail.isBlank()) return ChapterHandoff(hook = hook.trim())
        // 名字短的可能是长名字的一部分（“林”和“林月”）：长的先认，认过的位置短的不再算。
        val people = names.map { it.trim() }.filter { it.length >= 2 }.distinct().sortedByDescending { it.length }
            .fold(emptyList<Pair<String, Int>>()) { found, name ->
                val at = tail.lastIndexOf(name)
                if (at < 0 || found.any { (other, i) -> name in other && at in i until i + other.length }) found else found + (name to at)
            }.sortedByDescending { it.second }.map { HandoffPerson(it.first) }
        val scene = places.map { it.trim().trimEnd('。') }.filter { it.length >= 2 }.distinct()
            .mapNotNull { p -> tail.lastIndexOf(p).takeIf { it >= 0 }?.let { p to it } }
            .maxByOrNull { it.second }?.first.orEmpty()
        val last = paragraphs.last()
        val question = Regex("[“「]([^”」]{2,60}[？?])[”」]").findAll(last).lastOrNull()?.groupValues?.get(1)
        return ChapterHandoff(scene = scene, people = people, open = listOfNotNull(question?.let { "没答上的问话：“$it”" }), hook = hook.trim())
    }

    /** AI 回的不是约定的 JSON：照实报错，这次照常写、不带交接卡。 */
    class ParseError(message: String) : IllegalStateException(message)

    /** 读 AI 回的 JSON（见 [com.xycs.lingyuereader.ai.WritePrompts.handoff]）。读不出抛 [ParseError]。 */
    fun parse(answer: String): ChapterHandoff {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        val o = if (start in 0 until end) runCatching { JSONObject(answer.substring(start, end + 1)) }.getOrNull() else null
        if (o == null || listOf("scene", "people", "mood", "open", "hook").none { o.has(it) }) throw ParseError("AI 回的不是约定的 JSON，没能整理出交接卡")
        return fromJson(o).copy(origin = HandoffOrigin.AI)
    }

    fun fromJson(o: JSONObject): ChapterHandoff {
        fun text(key: String) = o.optString(key).trim().takeIf { it != "null" }.orEmpty()
        val people = o.optJSONArray("people")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val p = a.optJSONObject(i)
                if (p != null) {
                    val name = p.optString("name").trim()
                    if (name.isEmpty()) null else HandoffPerson(name, p.optString("doing").trim(), p.optString("state").trim())
                } else a.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::person)
            }
        }.orEmpty()
        val open = o.optJSONArray("open")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }.orEmpty()
            .filter { it.isNotEmpty() }.distinct().take(MAX_OPEN)
        return ChapterHandoff(
            scene = text("scene"), people = people, mood = text("mood"), open = open, hook = text("hook"),
            origin = HandoffOrigin.entries.firstOrNull { it.name == o.optString("origin") } ?: HandoffOrigin.LOCAL,
            hash = o.optString("hash"), textAt = o.optLong("textAt", 0), at = o.optLong("at", 0),
        )
    }

    /** 编辑框里的一行人物读回来：“名字：在做什么；状态”，只写了名字也行。 */
    fun person(line: String): HandoffPerson? {
        val t = line.trim().trimStart('-', '·', '•').trim()
        if (t.isEmpty()) return null
        val name = t.substringBefore('：').substringBefore(':').trim()
        if (name.isEmpty()) return null
        val rest = if (t.length > name.length) t.substring(name.length).trimStart('：', ':').trim() else ""
        val parts = rest.split('；', ';').map { it.trim() }.filter { it.isNotEmpty() }
        return HandoffPerson(name, parts.getOrNull(0).orEmpty(), parts.drop(1).joinToString("；"))
    }

    /** 整部作品的交接卡（章 id → 卡）存成一个文件。读不出的章跳过。 */
    fun toJson(all: Map<String, ChapterHandoff>): JSONObject = JSONObject().apply { all.toSortedMap().forEach { (id, h) -> put(id, h.toJson()) } }

    fun fromJsonAll(o: JSONObject): Map<String, ChapterHandoff> =
        o.keys().asSequence().mapNotNull { id -> o.optJSONObject(id)?.let { id to fromJson(it) } }.toMap()
}
