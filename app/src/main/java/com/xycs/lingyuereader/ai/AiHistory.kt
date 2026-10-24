package com.xycs.lingyuereader.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 对话记录里的一条：哪种对话、标题（选中的文字或第一个问题）、最后更新时间。 */
data class AiHistoryItem(val id: String, val title: String, val kind: String, val updatedAt: Long, val turns: Int)

/**
 * 按书保存的 AI 对话（划选后问 AI、问这本书）：存在书的目录里（ai-chats.json），删书时一起删。
 * 只存答完了的轮次；前情提要另有缓存，不进对话记录。最多留 [LIMIT] 条，旧的先删。
 */
class AiHistory(private val file: () -> File?) {

    fun list(): List<AiHistoryItem> = read().map { o ->
        val turns = o.optJSONArray("turns") ?: JSONArray()
        AiHistoryItem(o.getString("id"), o.optString("title"), o.optString("kind"), o.optLong("updatedAt"), turns.length())
    }.sortedByDescending { it.updatedAt }

    fun load(id: String): AiSession? = read().firstOrNull { it.optString("id") == id }?.let(::decode)

    /** 存一段对话（同一个 id 覆盖）；没有答完的轮次时不存。 */
    fun save(session: AiSession) {
        if (session.task is AiTask.Recap) return
        val done = session.turns.filter { !it.loading && it.error == null }
        if (done.isEmpty()) return
        val all = read().filterNot { it.optString("id") == session.id } + encode(session.copy(turns = done))
        write(all.sortedByDescending { it.optLong("updatedAt") }.take(LIMIT))
    }

    fun remove(id: String) = write(read().filterNot { it.optString("id") == id })

    fun clear() = write(emptyList())

    private fun read(): List<JSONObject> = runCatching {
        val a = JSONArray(file()!!.readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun write(list: List<JSONObject>) {
        val f = file() ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONArray(list).toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    companion object {
        const val LIMIT = 50
        const val FILE = "ai-chats.json"

        internal fun encode(s: AiSession): JSONObject {
            val o = JSONObject().put("id", s.id).put("updatedAt", System.currentTimeMillis())
            when (val t = s.task) {
                is AiTask.Selection -> o.put("kind", "selection").put("title", t.text.take(60)).put("text", t.text).put("context", t.context)
                    .put("chapterTitle", t.chapterTitle).put("chapter", t.chapter)
                    .put("pieces", JSONArray().apply { t.pieces.forEach { put(JSONArray().put(it.block).put(it.start).put(it.end)) } })
                is AiTask.AskBook -> o.put("kind", "book").put("title", s.turns.firstOrNull()?.question.orEmpty().take(60))
                    .put("chapter", t.pos.chapter).put("block", t.pos.block)
                is AiTask.Recap -> o.put("kind", "recap")
            }
            o.put("turns", JSONArray().apply {
                s.turns.forEach { turn ->
                    put(
                        JSONObject().put("q", turn.question).put("a", turn.answer).put("replay", turn.replay)
                            .put("sources", JSONArray().apply {
                                turn.sources.forEach { src ->
                                    put(JSONObject().put("i", src.index).put("c", src.chapter).put("t", src.chapterTitle).put("b", src.block).put("x", src.text))
                                }
                            }),
                    )
                }
            })
            return o
        }

        internal fun decode(o: JSONObject): AiSession? = runCatching {
            val task: AiTask = when (o.getString("kind")) {
                "selection" -> AiTask.Selection(
                    o.getString("text"), o.optString("context"), o.optString("chapterTitle"), o.optInt("chapter", -1),
                    o.optJSONArray("pieces")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> NoteRange(p.getInt(0), p.getInt(1), p.getInt(2)) } } }.orEmpty(),
                )
                "book" -> AiTask.AskBook(ReadPos(o.getInt("chapter"), o.getInt("block")))
                else -> return@runCatching null
            }
            val turns = o.getJSONArray("turns")
            AiSession(
                task,
                (0 until turns.length()).map { i ->
                    val t = turns.getJSONObject(i)
                    val sources = t.optJSONArray("sources") ?: JSONArray()
                    AiTurn(
                        question = t.getString("q"),
                        answer = t.getString("a"),
                        sources = (0 until sources.length()).map { k ->
                            sources.getJSONObject(k).let { s -> AiSource(s.getInt("i"), s.getInt("c"), s.getString("t"), s.getInt("b"), s.getString("x")) }
                        },
                        loading = false,
                        replay = t.optString("replay").ifEmpty { t.getString("q") },
                    )
                },
                id = o.getString("id"),
            )
        }.getOrNull()
    }
}
