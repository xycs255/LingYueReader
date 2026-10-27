package com.xycs.lingyuereader.data

import com.xycs.lingyuereader.ai.AiToolCall
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReviewEvidenceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val task = ChapterWritingTask(
        title = "第二章 雨夜", chapter = 2, volume = 1, instruction = "", targetLength = "依细纲",
        previous = "", previousLabel = "承接前文", card = ChapterCard(facts = "主角在雨夜交出钥匙"),
        sourceNames = emptyList(), warnings = emptyList(), blocking = emptyList(), project = true,
        fingerprint = "snapshot-1",
    )

    @Test fun taskSourceExcludesInferredHandoffAndUnconfirmedContext() {
        val brief = task.copy(
            instruction = "对白必须保留人物自己的语气",
            previousLabel = "以细纲结果承接；前章没有可核验的定稿正文",
            context = "未定草稿写主角已经烧掉了地图。",
            previous = "用户已定前章写主角把地图交给朋友。",
        )
        val source = ReviewEvidence.task(brief)
        val evidence = ReviewEvidence(listOf(source))
        assertTrue(evidence.locates(source.ref, "主角在雨夜交出钥匙"))
        assertTrue(evidence.locates(source.ref, "用户已定前章写主角把地图交给朋友"))
        assertTrue(evidence.locates(source.ref, "对白必须保留人物自己的语气", "CRAFT"))
        assertFalse(evidence.locates(source.ref, "主角在雨夜交出钥匙", "CRAFT"))
        assertFalse(evidence.locates(source.ref, "用户已定前章写主角把地图交给朋友", "CRAFT"))
        assertFalse(evidence.locates(source.ref, "前章没有可核验的定稿正文"))
        assertFalse(evidence.locates(source.ref, "未定草稿写主角已经烧掉了地图"))
        assertFalse(evidence.locates(source.ref, "规划、参考样章、草稿和待核增量"))
    }

    @Test fun inlineTaskAndCurrentPackAndConfirmedPastChapterAreLocatable() {
        val store = WorkStore(tmp.newFolder())
        val work = store.create("雨夜")
        val past = work.chapters.single()
        store.saveText(work.id, past.id, "此前主角把地图交给朋友。")
        store.setStatus(work.id, past.id, ChapterStatus.FINAL)
        val (_, target) = store.addChapter(work.id, work.volumes.single().id, "第二章 雨夜")!!
        store.saveText(work.id, target.id, "尚未采纳的第二章事实。")
        val (_, future) = store.addChapter(work.id, work.volumes.single().id, "第三章 日出")!!
        store.saveText(work.id, future.id, "未来主角到达终点。")
        store.setStatus(work.id, future.id, ChapterStatus.FINAL)
        val packText = "## 第二章 雨夜\n主角先交出钥匙，然后离开旅馆。"
        val doc = store.addPackDoc(work.id, PackKind.VOLUME, "第二章细纲", packText)
        val rulesText = "禁用空泛总结这句话。"
        val rules = store.addPackDoc(work.id, PackKind.RULES, "检查规则", rulesText)
        val character = store.addPackDoc(work.id, PackKind.CHARACTER, "人物档案", "主角害怕雨夜中的钟声。")
        val reference = store.addPackDoc(work.id, PackKind.TECHNIQUE, "写法分析", "样章里主角改写了整个故事。")

        val evidence = store.reviewEvidence(work.id, target.id, task)
        val taskRef = ReviewEvidence.task(task).ref
        val packRef = ReviewEvidence.ref("P", doc.id, packText)
        val rulesRef = ReviewEvidence.ref("P", rules.id, rulesText)
        val pastRef = ReviewEvidence.ref("C", past.id, store.text(work.id, past.id))
        assertTrue(evidence.locates(taskRef, "主角在雨夜交出钥匙"))
        assertTrue(evidence.locates(packRef, "主角先交出钥匙，然后离开旅馆"))
        assertTrue(evidence.locates(pastRef, "此前主角把地图交给朋友"))
        assertTrue(evidence.locates(rulesRef, "禁用空泛总结这句话", "CRAFT"))
        assertFalse(evidence.locates(rulesRef, "禁用空泛总结这句话", "FACT"))
        val characterRef = ReviewEvidence.ref("P", character.id, "主角害怕雨夜中的钟声。")
        assertTrue(evidence.locates(characterRef, "主角害怕雨夜中的钟声", "FACT"))
        assertFalse(evidence.locates(characterRef, "主角害怕雨夜中的钟声", "CRAFT"))
        assertFalse(evidence.locates(ReviewEvidence.ref("C", target.id, store.text(work.id, target.id)), "尚未采纳的第二章事实"))
        assertFalse(evidence.locates(ReviewEvidence.ref("C", future.id, store.text(work.id, future.id)), "未来主角到达终点"))
        assertFalse(evidence.locates(ReviewEvidence.ref("P", reference.id, store.packText(work.id, reference.id)), "样章里主角改写了整个故事"))

        val tools = store.packTools(work.id, target.id, WritingPhase.REVIEW, confirmedOnly = true)
        val docResult = tools.run(AiToolCall("d", PackTools.READ_DOC, JSONObject().put("title", doc.title)))
        val rulesResult = tools.run(AiToolCall("r", PackTools.READ_DOC, JSONObject().put("title", rules.title)))
        val chapterResult = tools.run(AiToolCall("c", PackTools.READ_CHAPTER, JSONObject().put("chapter", past.title)))
        assertTrue(docResult.contains("[来源 $packRef]"))
        assertTrue(rulesResult.contains("[来源 $rulesRef]"))
        assertTrue(chapterResult.contains("[来源 $pastRef]"))
    }

    @Test fun sourceReferenceChangesWithEditedMaterialAndShortQuotesAreRejected() {
        val store = WorkStore(tmp.newFolder())
        val work = store.create("雨夜")
        val chapterId = work.chapters.single().id
        val doc = store.addPackDoc(work.id, PackKind.VOLUME, "章节细纲", "主角在旧桥旁交出钥匙。")
        val before = store.reviewEvidence(work.id, chapterId, task)
        val oldRef = ReviewEvidence.ref("P", doc.id, "主角在旧桥旁交出钥匙。")
        assertTrue(before.locates(oldRef, "主角在旧桥旁交出钥匙"))
        assertTrue(before.locates(oldRef, "主角在旧桥旁 交出钥匙"))
        assertFalse(before.locates(oldRef, "交出"))
        assertFalse(before.locates("P_forged", "主角在旧桥旁交出钥匙"))
        assertFalse(ReviewEvidence(listOf(ReviewSource("P_marks", "标点", "………………")))
            .locates("P_marks", "………………"))

        store.savePackText(work.id, doc.id, "主角在新桥旁收回钥匙。")
        val after = store.reviewEvidence(work.id, chapterId, task)
        assertFalse(after.locates(oldRef, "主角在旧桥旁交出钥匙"))
        assertTrue(after.locates(ReviewEvidence.ref("P", doc.id, "主角在新桥旁收回钥匙。"), "主角在新桥旁收回钥匙"))
    }
}
