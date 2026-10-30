package com.xycs.lingyuereader.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 分层前情里的“故事至今”：一份不超过 [StoryDigests.STORY_SENTENCES] 句的全书梗概，一行一句。
 * [throughId] / [throughNo] 记它写到哪一章为止（含这一章）：写这一章和以前的章时不用它，免得把后面的事带进前情。
 * [edited] 为真是作者改过的，收尾时不再自动更新（作者点“改回自动”才接着更新）。
 */
data class StoryDigest(
    val text: String,
    val throughId: String = "",
    val throughNo: Int = 0,
    val edited: Boolean = false,
    val at: Long = 0,
    /** 过期了：写到的某一章定稿后又改过（[FinalCascade]）。下次收尾从头整理一遍；整理好之前照旧用这一份。 */
    val stale: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("text", text).put("throughId", throughId).put("throughNo", throughNo)
        .put("edited", edited).put("at", at).apply { if (stale) put("stale", true) }
}

/**
 * 一卷的卷摘要：只从这一卷各章的摘要汇总（不读正文），300 到 600 字。[chapters] 是汇总时用了哪几章（章 id），
 * 这一卷又多了几章摘要、写完了，再汇总一次。[edited] 同 [StoryDigest.edited]。
 */
data class VolumeDigest(
    val text: String,
    val chapters: List<String> = emptyList(),
    val edited: Boolean = false,
    val at: Long = 0,
    /** 过期了：这一卷里某一章定稿后又改过（[FinalCascade]）。下次这一卷有章收尾时重新汇总。 */
    val stale: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("text", text).put("chapters", JSONArray(chapters)).put("edited", edited).put("at", at)
        .apply { if (stale) put("stale", true) }
}

/** 一部作品的分层前情：故事至今和各卷的卷摘要（卷 id → 摘要）。存在作品目录的 digests.json 里。 */
data class Digests(val story: StoryDigest? = null, val volumes: Map<String, VolumeDigest> = emptyMap()) {
    val isEmpty: Boolean get() = story == null && volumes.isEmpty()
}

/** 写某一章时用得上的前情层：故事至今（只到本章之前）、往卷的卷摘要（按卷的先后）。 */
data class PastLayers(val story: String = "", val volumes: List<VolumeLayer> = emptyList()) {
    val isEmpty: Boolean get() = story.isBlank() && volumes.isEmpty()
}

/** 往卷的一份卷摘要：哪一卷（id、卷名）、内容。 */
data class VolumeLayer(val volumeId: String, val title: String, val text: String)

/** 整理前情这一次请求要做什么：更新故事至今（[story]）、汇总哪一卷（[volumeId]），和要给 AI 的材料。 */
data class DigestJob(
    val story: Boolean,
    val oldStory: String,
    /** 故事至今要并进去的：（章名，摘要），按目录先后。 */
    val newChapters: List<Pair<String, String>>,
    /** 还没有故事至今时，前面各卷的卷摘要（卷名，摘要），给 AI 从头写一份。 */
    val pastVolumes: List<Pair<String, String>> = emptyList(),
    val volumeId: String? = null,
    val volumeTitle: String = "",
    /** 这一卷各章的摘要：（章名，摘要）。 */
    val volumeChapters: List<Pair<String, String>> = emptyList(),
    /** 这一卷汇总用了哪几章（章 id）。 */
    val volumeChapterIds: List<String> = emptyList(),
    /** 故事至今这次写到哪一章（章 id）；空着是收尾的这一章。过期重整时可能是更后面的章（不让梗概倒退）。 */
    val throughId: String = "",
) {
    val isEmpty: Boolean get() = !story && volumeId == null
}

/** 分层前情：存取、什么时候该整理、请 AI 整理时的材料和怎么读回答。 */
object StoryDigests {
    /** 故事至今最多几句：二十句的梗概够撑起几百章的主干，又只有几百字（借 RecurrentGPT 有界摘要的思路）。 */
    const val STORY_SENTENCES = 20

    /**
     * 一卷里有几章写了摘要就先汇总一次卷摘要，之后每多这么多章再汇总一次；一卷写完（每章都有摘要）时也汇总。
     * 一卷常见二三十章：十章一次，一卷汇总两三次，每次输入只是几千字的章摘要，花费很小，卷摘要也不至于太旧。
     */
    const val VOLUME_EVERY = 10

    /**
     * 有摘要的章到了这么多才开始整理故事至今：前情本来就带最近 [ChapterBriefs.RECENT_SUMMARIES] 章的摘要，
     * 章数没超过它时梗概只是把这几章再说一遍，白花一次请求。
     */
    const val STORY_MIN = ChapterBriefs.RECENT_SUMMARIES + 1

    /** 一卷写完时至少有几章摘要才汇总：一两章的卷，卷摘要和章摘要差不多长，不必。 */
    const val VOLUME_MIN = 3

    /** 卷摘要、故事至今在前情里各最多多少字（作者改长了也只带这么多）。 */
    const val VOLUME_MAX = 800
    const val STORY_MAX = 1_000

    /** 一次整理最多带多少字的章摘要：故事至今缺了很多章（比如刚升级）时只带最近的。 */
    const val INPUT_CHARS = 12_000

    fun toJson(d: Digests): JSONObject = JSONObject().apply {
        d.story?.let { put("story", it.toJson()) }
        put("volumes", JSONObject().apply { d.volumes.forEach { (id, v) -> put(id, v.toJson()) } })
    }

    /** 读 digests.json；旧数据没有这份为空，缺的字段读成默认值，空的卷摘要不要。 */
    fun fromJson(o: JSONObject): Digests {
        val story = o.optJSONObject("story")?.let { s ->
            StoryDigest(s.optString("text"), s.optString("throughId"), s.optInt("throughNo"), s.optBoolean("edited"), s.optLong("at"), s.optBoolean("stale"))
        }?.takeIf { it.text.isNotBlank() }
        val volumes = o.optJSONObject("volumes")?.let { m ->
            m.keys().asSequence().mapNotNull { id ->
                val v = m.optJSONObject(id) ?: return@mapNotNull null
                val chapters = v.optJSONArray("chapters")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
                VolumeDigest(v.optString("text"), chapters, v.optBoolean("edited"), v.optLong("at"), v.optBoolean("stale")).takeIf { it.text.isNotBlank() }?.let { id to it }
            }.toMap()
        }.orEmpty()
        return Digests(story, volumes)
    }

    /**
     * 写 [chapterId] 时用得上的前情层（只用本章之前的，防剧透）：
     * - 故事至今：写到的那一章在本章之前才用；写到的那章被删了，按记下的章号比；
     * - 往卷（目录里排在本章所在卷前面的卷）的卷摘要；
     * [confirmedOnly] 为真（项目包流程只认定稿）时，故事至今写到的那章、卷摘要用到的各章都得还是定稿，不然不用。
     * 没有可用的层为空，前情照旧只用章摘要。
     */
    fun layers(work: Work, chapterId: String, number: Int, digests: Digests, confirmedOnly: Boolean = false): PastLayers? {
        if (digests.isEmpty) return null
        val index = work.chapters.indexOfFirst { it.id == chapterId }
        if (index < 0) return null
        fun final(id: String) = work.chapter(id)?.status == ChapterStatus.FINAL
        val story = digests.story?.takeIf { s ->
            val at = work.chapters.indexOfFirst { it.id == s.throughId }
            val before = if (at >= 0) at < index else s.throughNo in 1 until number
            before && (!confirmedOnly || (at >= 0 && final(s.throughId)))
        }?.text?.trim().orEmpty()
        val vi = work.volumes.indexOfFirst { v -> v.chapters.any { it.id == chapterId } }
        val volumes = work.volumes.take(maxOf(vi, 0)).mapNotNull { v ->
            val d = digests.volumes[v.id] ?: return@mapNotNull null
            if (confirmedOnly && d.chapters.any { !final(it) }) return@mapNotNull null
            VolumeLayer(v.id, v.title, d.text.trim())
        }
        return PastLayers(story, volumes).takeIf { !it.isEmpty }
    }

    /**
     * 收尾时（[chapterId] 刚写好摘要 [summary]）这一次要整理什么：
     * - 故事至今：作者改过的不动；已经写到本章或更后面（回头重收旧章）的不动，免得梗概倒退；有摘要的章不到 [STORY_MIN] 章时
     *   还不用；否则把上次写到的那章之后、到本章为止有摘要的各章并进去（上次没成的这次一起补上）。
     * - 卷摘要：本章所在的卷，作者改过的不动；这一卷每章都有摘要（写完了，至少 [VOLUME_MIN] 章）而上次汇总时还没全，或者有摘要的章到了
     *   [VOLUME_EVERY] 章、比上次汇总多了 [VOLUME_EVERY] 章，就汇总一次。只用写了摘要的章。
     * [summaries] 是各章细纲卡里的摘要（章 id → 摘要），本章用 [summary]。
     */
    fun plan(work: Work, chapterId: String, summary: String, summaries: Map<String, String>, digests: Digests): DigestJob {
        val index = work.chapters.indexOfFirst { it.id == chapterId }
        if (index < 0 || summary.isBlank()) return DigestJob(false, "", emptyList())
        fun summaryOf(c: WorkChapter) = if (c.id == chapterId) summary.trim() else summaries[c.id].orEmpty().trim()
        val stored = digests.story
        val throughAt = stored?.let { s -> work.chapters.indexOfFirst { it.id == s.throughId } } ?: -1
        // 过期了（写到的章定稿后又改过）：从头整理一遍，写到原来写到的和这一章里靠后的那一章，梗概不倒退。
        val rebuild = stored?.stale == true && !stored.edited
        val old = stored.takeIf { !rebuild }
        val end = if (rebuild) maxOf(index, throughAt) else index
        val summarized = work.chapters.take(end + 1).count { summaryOf(it).isNotEmpty() }
        val wantStory = stored?.edited != true && !(old != null && throughAt >= index) && (old != null || rebuild || summarized >= STORY_MIN)
        val start = if (old != null && throughAt >= 0) throughAt + 1 else 0
        val vi = work.volumes.indexOfFirst { v -> v.chapters.any { it.id == chapterId } }
        val newChapters = if (!wantStory) emptyList() else
            capped(work.chapters.subList(start, end + 1).map { it.title to summaryOf(it) }.filter { it.second.isNotEmpty() })
        val pastVolumes = if (!wantStory || old != null) emptyList()
            else work.volumes.take(maxOf(vi, 0)).mapNotNull { v -> digests.volumes[v.id]?.let { v.title to it.text } }
        val volume = work.volumes.getOrNull(vi)
        var volumeJob: Triple<Volume, List<WorkChapter>, Boolean>? = null
        if (volume != null && digests.volumes[volume.id]?.edited != true) {
            val with = volume.chapters.filter { summaryOf(it).isNotEmpty() }
            val complete = volume.chapters.isNotEmpty() && with.size == volume.chapters.size
            val done = digests.volumes[volume.id]?.chapters.orEmpty()
            val due = when {
                with.isEmpty() -> false
                digests.volumes[volume.id]?.stale == true -> true
                complete && with.size >= VOLUME_MIN -> done.toSet() != with.map { it.id }.toSet()
                with.size < VOLUME_EVERY -> false
                else -> with.size - done.size >= VOLUME_EVERY
            }
            if (due) volumeJob = Triple(volume, with, complete)
        }
        return DigestJob(
            story = wantStory && newChapters.isNotEmpty(),
            oldStory = if (wantStory) old?.text.orEmpty() else "",
            newChapters = newChapters,
            pastVolumes = pastVolumes,
            volumeId = volumeJob?.first?.id,
            volumeTitle = volumeJob?.first?.title.orEmpty(),
            volumeChapters = volumeJob?.second?.let { list -> capped(list.map { it.title to summaryOf(it) }) }.orEmpty(),
            volumeChapterIds = volumeJob?.second?.map { it.id }.orEmpty(),
            throughId = work.chapters[end].id,
        )
    }

    /** 章摘要合起来超过 [INPUT_CHARS] 字时只留最近的几章。 */
    private fun capped(list: List<Pair<String, String>>): List<Pair<String, String>> {
        var total = 0
        return list.asReversed().takeWhile { (t, s) -> total += t.length + s.length; total <= INPUT_CHARS }.asReversed()
            .ifEmpty { list.takeLast(1) }
    }

    /** AI 回的不是约定的 JSON：照实报错，不拦收尾，下次收尾再补。 */
    class ParseError(message: String) : IllegalStateException(message)

    /**
     * 读 AI 回的 JSON（见 [com.xycs.lingyuereader.ai.WritePrompts.digest]）：`story` 是一句一项的数组（写成一整段的也认，按句号拆），
     * 最多 [STORY_SENTENCES] 句；`volume` 是一段文字。要了的那项读不出就抛 [ParseError]。
     */
    fun parse(answer: String, wantStory: Boolean, wantVolume: Boolean): Pair<String?, String?> {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        val o = (if (start in 0 until end) runCatching { JSONObject(answer.substring(start, end + 1)) }.getOrNull() else null)
            ?: throw ParseError("AI 回的不是约定的 JSON，前情没整理成")
        val story = if (!wantStory) null else {
            val raw = o.opt("story")
            val lines = when (raw) {
                is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
                is String -> raw.split(Regex("(?<=[。！？!?])|\\n"))
                else -> emptyList()
            }.map { it.trim().removePrefix("-").trim() }.filter { it.isNotEmpty() && it != "null" }
            if (lines.isEmpty()) throw ParseError("AI 没写出故事至今，前情没整理成")
            lines.take(STORY_SENTENCES).joinToString("\n")
        }
        val volume = if (!wantVolume) null else {
            o.optString("volume").trim().takeIf { it.isNotEmpty() && it != "null" } ?: throw ParseError("AI 没写出卷摘要，前情没整理成")
        }
        return story to volume
    }
}
