package com.xycs.lingyuereader.ai

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import com.xycs.lingyuereader.WriteAiTask
import com.xycs.lingyuereader.data.BibleEntry
import com.xycs.lingyuereader.data.BibleKind
import com.xycs.lingyuereader.data.StyleExample
import com.xycs.lingyuereader.data.WorkAssist
import com.xycs.lingyuereader.data.WorkStore
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.ui.EditApply
import com.xycs.lingyuereader.ui.applyEdit
import com.xycs.lingyuereader.ui.applyIssue
import com.xycs.lingyuereader.ui.clampRange
import com.xycs.lingyuereader.ui.editTarget
import com.xycs.lingyuereader.ui.mergeCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class WritePromptsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun rebuild(pieces: List<TextDiff.Piece>): Pair<String, String> {
        val a = pieces.filter { it.kind != TextDiff.Kind.INSERTED }.joinToString("") { it.text }
        val b = pieces.filter { it.kind != TextDiff.Kind.DELETED }.joinToString("") { it.text }
        return a to b
    }

    @Test
    fun diffMarksDeletionsAndInsertions() {
        val pieces = TextDiff.diff("他走进院子，看见一个人。", "他慢慢走进了院子，看见一个老人。")!!
        assertEquals("他走进院子，看见一个人。" to "他慢慢走进了院子，看见一个老人。", rebuild(pieces))
        val inserted = pieces.filter { it.kind == TextDiff.Kind.INSERTED }.map { it.text }
        assertEquals(listOf("慢慢", "了", "老"), inserted)
        assertTrue(pieces.none { it.kind == TextDiff.Kind.DELETED })
        assertEquals(listOf(TextDiff.Piece(TextDiff.Kind.SAME, "一样")), TextDiff.diff("一样", "一样"))
        assertEquals(listOf(TextDiff.Piece(TextDiff.Kind.DELETED, "删光")), TextDiff.diff("删光", ""))
    }

    @Test
    fun diffAlwaysRebuildsBothSides() {
        val r = Random(7)
        val alphabet = "天地人你我他的了是在风雪门灯，。"
        repeat(300) {
            val a = String(CharArray(r.nextInt(0, 40)) { alphabet[r.nextInt(alphabet.length)] })
            val b = String(CharArray(r.nextInt(0, 40)) { alphabet[r.nextInt(alphabet.length)] })
            assertEquals(a to b, rebuild(TextDiff.diff(a, b)!!))
        }
        // 改得太多就不比了。
        assertNull(TextDiff.diff("甲".repeat(50), "乙".repeat(50), maxEdits = 20))
    }

    @Test
    fun editTargetUsesTheSelectionOrTheParagraphAtTheCursor() {
        val text = "第一段。\n第二段在这里。\n\n第四段。"
        val sel = editTarget(text, TextRange(5, 8), WriteAction.POLISH)!!
        assertEquals("第二段", sel.original)
        assertEquals(5 to 8, sel.start to sel.end)
        // 光标在第二段中间：改整段。
        val para = editTarget(text, TextRange(7), WriteAction.POLISH)!!
        assertEquals("第二段在这里。", para.original)
        assertEquals(text.indexOf("第二段"), para.start)
        // 光标在开头。
        assertEquals("第一段。", editTarget(text, TextRange(0), WriteAction.PROOFREAD)!!.original)
        // 光标在段末（换行前）也算这一段。
        assertEquals("第一段。", editTarget(text, TextRange(4), WriteAction.PROOFREAD)!!.original)
        // 空行上没什么可改。
        assertNull(editTarget(text, TextRange(text.indexOf("\n\n") + 1), WriteAction.POLISH))
        // 续写在光标处，前面得有字。
        val cont = editTarget(text, TextRange(text.length), WriteAction.CONTINUE)!!
        assertEquals("" to (text.length to text.length), cont.original to (cont.start to cont.end))
        assertEquals(text, cont.continuationBase)
        assertNull(editTarget("  ", TextRange(1), WriteAction.CONTINUE))
    }

    @Test
    fun acceptingReplacesByPositionOrFindsTheOriginalAgain() {
        val state = TextFieldState("他推门进来。\n风很大。")
        val task = WriteAiTask.Edit(WriteAction.POLISH, "风很大。", 7, 11)
        assertEquals(EditApply.DONE, applyEdit(state, task, "风刮得很猛。"))
        assertEquals("他推门进来。\n风刮得很猛。", state.text.toString())

        // 采纳前作者又在前面加了字：位置对不上，在原位置附近按原文找。
        val moved = TextFieldState("开头加了一句。\n他推门进来。\n风很大。")
        assertEquals(EditApply.DONE, applyEdit(moved, task, "风刮得很猛。"))
        assertEquals("开头加了一句。\n他推门进来。\n风刮得很猛。", moved.text.toString())

        // 原文已经被改掉：不换。
        val gone = TextFieldState("全改了。")
        assertEquals(EditApply.MISSING, applyEdit(gone, task, "x"))
        assertEquals("全改了。", gone.text.toString())

        // 续写接在段末，另起一段。
        val cont = TextFieldState("他推门进来。")
        val contTask = editTarget(cont.text.toString(), TextRange(cont.text.length), WriteAction.CONTINUE)!!
        assertEquals(EditApply.DONE, applyEdit(cont, contTask, "屋里没人。"))
        assertEquals("他推门进来。\n屋里没人。", cont.text.toString())
        assertEquals(TextRange(cont.text.length), cont.selection)
    }

    @Test
    fun continuationAcceptanceRejectsInsertionOrDeletionBeforeCursor() {
        val original = "他推门进来。\n屋里很暗。"
        val at = original.indexOf("屋里")
        val task = editTarget(original, TextRange(at), WriteAction.CONTINUE)!!

        // The same request still inserts at the intended paragraph boundary when unchanged.
        val unchanged = TextFieldState(original)
        assertEquals(EditApply.DONE, applyEdit(unchanged, task, "有人点了灯。"))
        assertEquals("他推门进来。\n有人点了灯。\n屋里很暗。", unchanged.text.toString())

        for (changed in listOf("忽然，他推门进来。\n屋里很暗。", "他进来。\n屋里很暗。")) {
            val state = TextFieldState(changed)
            assertEquals(EditApply.MISSING, applyEdit(state, task, "有人点了灯。"))
            assertEquals(changed, state.text.toString())
        }
    }

    @Test
    fun continuationAcceptanceDoesNotGuessBetweenRepeatedContext() {
        val original = "他点了点头。\n他点了点头。\n天黑了。"
        val task = editTarget(original, TextRange(original.indexOf("他点了点头。") + "他点了点头。".length), WriteAction.CONTINUE)!!
        val changed = "序章。\n$original"
        val state = TextFieldState(changed)
        assertEquals(EditApply.MISSING, applyEdit(state, task, "他说可以。"))
        assertEquals(changed, state.text.toString())

        // Even a change after the cursor invalidates the request's source context.
        val changedAfter = "他点了点头。\n他点了点头。\n夜更深了。"
        val after = TextFieldState(changedAfter)
        assertEquals(EditApply.MISSING, applyEdit(after, task, "他说可以。"))
        assertEquals(changedAfter, after.text.toString())
    }

    @Test
    fun continuationWithoutSourceSnapshotCannotBeAccepted() {
        val original = "他推门进来。"
        val state = TextFieldState(original)
        val task = WriteAiTask.Edit(WriteAction.CONTINUE, "", original.length, original.length)
        assertEquals(EditApply.MISSING, applyEdit(state, task, "屋里没人。"))
        assertEquals(original, state.text.toString())
    }

    @Test
    fun acceptingOnlyLooksNearTheOriginalPlace() {
        // 选中第 30 段的“他点了点头。”去润色；等结果时作者在前面加了几个字。
        val filler = (1..60).joinToString("\n") { "第${it}段，顾长风在雪里走了很久很久。" }
        val text = filler.replace("第30段，", "他点了点头。\n第30段，")
        val at = text.indexOf("他点了点头。")
        val task = WriteAiTask.Edit(WriteAction.POLISH, "他点了点头。", at, at + 6)
        // 原处那句已经改掉了，几页之后还有一句一模一样的：不能换到那里去。
        val farText = text.replace("他点了点头。", "他摇了摇头。") + "\n" + "沈默没说话。".repeat(400) + "\n他点了点头。"
        val far = TextFieldState(farText)
        assertEquals(EditApply.MISSING, applyEdit(far, task, "他轻轻点头。"))
        assertEquals(farText, far.text.toString())

        // 前面只加了几个字：就在附近，换掉。
        val near = TextFieldState("加了几个字" + text)
        assertEquals(EditApply.DONE, applyEdit(near, task, "他轻轻点头。"))
        assertEquals("加了几个字" + text.replace("他点了点头。", "他轻轻点头。"), near.text.toString())

        // 附近有两处一样的：不知道是哪一处，不换。
        val twice = TextFieldState("加了几个字" + text.replace("第31段，", "他点了点头。\n第31段，"))
        assertEquals(EditApply.AMBIGUOUS, applyEdit(twice, task, "他轻轻点头。"))
        assertEquals(2, Regex("他点了点头。").findAll(twice.text).count())
    }

    @Test
    fun aRuleHitPastTheEndOfTheTextIsClamped() {
        // 刚删掉一大段，检查结果还是删之前的：选区夹在正文里，不越界。
        assertEquals(TextRange(3, 5), clampRange(3..9, 5))
        assertEquals(TextRange(5, 5), clampRange(40..45, 5))
        assertEquals(TextRange(1, 3), clampRange(1..2, 5))
    }

    @Test
    fun cardIsMergedFieldByFieldOnSave() {
        // 页面打开时的卡片；这期间“补写章节摘要”写进了摘要；作者在页面上改了事实。
        val base = ChapterCard(facts = "林月进城", hook = "门外有人")
        val latest = base.copy(summary = "林月进城，住进杜娘的茶馆。")
        val edited = base.copy(facts = "林月进城，带着半块玉佩")
        val merged = mergeCard(base, edited, latest)
        assertEquals("林月进城，带着半块玉佩", merged.facts)
        assertEquals("林月进城，住进杜娘的茶馆。", merged.summary)
        assertEquals("门外有人", merged.hook)
        // 页面上把摘要清空了：按页面的来。
        assertEquals("", mergeCard(latest, latest.copy(summary = ""), latest.copy(hook = "老周来了")).summary)
        assertEquals("老周来了", mergeCard(latest, latest.copy(summary = ""), latest.copy(hook = "老周来了")).hook)
    }

    @Test
    fun aCharacterIsReplacedInHisOwnSection() {
        val doc = "# 人物档案\n\n## 沈默\n游侠\n腰间半块玉佩\n\n## 林月\n药铺掌柜的女儿\n- 老周：药铺伙计"
        // 标题下的一节：整节换掉，后面的人不动。
        assertEquals(
            "# 人物档案\n\n## 沈默\n左手使刀的游侠\n（AI 整理，2026-10-24）\n\n## 林月\n药铺掌柜的女儿\n- 老周：药铺伙计",
            WritePrompts.replaceCharacter(doc, "沈默", "左手使刀的游侠", "2026-10-24"),
        )
        // 列表里的一条：换那一行。
        assertEquals(
            "# 人物档案\n\n## 沈默\n游侠\n腰间半块玉佩\n\n## 林月\n药铺掌柜的女儿\n- 老周：药铺的老伙计（AI 整理，2026-10-24）",
            WritePrompts.replaceCharacter(doc, "老周", "药铺的老伙计", "2026-10-24"),
        )
        // 最后一节换到文末；没有这个人返回空。
        assertTrue(WritePrompts.replaceCharacter("## 林月\n旧说明", "林月", "新说明", "d")!!.endsWith("## 林月\n新说明\n（AI 整理，d）"))
        assertNull(WritePrompts.replaceCharacter(doc, "杜娘", "茶馆老板娘", "d"))
        assertNull(WritePrompts.replaceCharacter("| 人物 | 想要 |\n|---|---|\n| 杜娘 | 安稳 |", "杜娘", "茶馆老板娘", "d"))
    }

    @Test
    fun diffIsMinimalAndCheapOnLongText() {
        // 和逐字的动态规划比：删加的字数一样少。
        val r = Random(11)
        val alphabet = "林月沈默老周杜娘，。"
        repeat(200) {
            val a = String(CharArray(r.nextInt(0, 60)) { alphabet[r.nextInt(alphabet.length)] })
            val b = String(CharArray(r.nextInt(0, 60)) { alphabet[r.nextInt(alphabet.length)] })
            val pieces = TextDiff.diff(a, b)!!
            assertEquals(a to b, rebuild(pieces))
            val edits = pieces.filter { it.kind != TextDiff.Kind.SAME }.sumOf { it.text.length }
            val lcs = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
            assertEquals(a.length + b.length - 2 * lcs[0][0], edits)
        }
        // 整章润色：几万字里改了几十处，照样比得出来。
        val chapter = (1..800).joinToString("\n") { "第${it}段，顾长风在雪里走了很久。" }
        val polished = chapter.replace("走了很久", "走了很久很久").replaceFirst("第400段", "第四百段")
        val pieces = TextDiff.diff(chapter, polished)!!
        assertEquals(chapter to polished, rebuild(pieces))
        // 改动太多：很快放弃，不先把内存占满。
        val other = String(CharArray(20_000) { "林月沈默"[it % 4] })
        val started = System.nanoTime()
        assertNull(TextDiff.diff(String(CharArray(20_000) { "杜娘老周"[it % 4] }), other))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun fixingAnIssueChangesOnlyThatParagraph() {
        val state = TextFieldState("他在门口。\n他再门口等着。")
        assertTrue(applyIssue(state, WriteIssue("他再门口等着。", "再门口", "在门口", "错别字")))
        assertEquals("他在门口。\n他在门口等着。", state.text.toString())
        assertFalse(applyIssue(state, WriteIssue("不存在的段落", "不", "没", "")))
    }

    @Test
    fun parsesIssuesAndDropsOnesNotInTheText() {
        val paragraphs = listOf("他再门口等着。", "天色以晚，大家都回去了。")
        val answer = """好的：```json
            {"issues":[
              {"p":1,"wrong":"再门口","right":"在门口","reason":"错别字"},
              {"p":2,"wrong":"以晚","right":"已晚","reason":"错别字"},
              {"p":2,"wrong":"编出来的","right":"x","reason":""},
              {"p":5,"wrong":"他","right":"她","reason":""},
              {"p":1,"wrong":"等着","right":"等着","reason":"没改"}
            ]}```"""
        val issues = WritePrompts.parseIssues(answer, paragraphs)
        assertEquals(listOf("再门口" to "在门口", "以晚" to "已晚"), issues.map { it.wrong to it.right })
        assertEquals(paragraphs[1], issues[1].paragraph)
        assertEquals(emptyList<WriteIssue>(), WritePrompts.parseIssues("""{"issues":[]}""", paragraphs))
        assertTrue(runCatching { WritePrompts.parseIssues("没有找到", paragraphs) }.exceptionOrNull() is AiException)
    }

    @Test
    fun proposalsMarkExistingCharacters() {
        val list = WritePrompts.parseProposals("""{"entries":[{"name":"林月","text":"药铺掌柜的女儿"},{"name":"沈默","text":"捕快"},{"name":"","text":"x"}]}""", listOf("林月"))
        assertEquals(listOf("林月" to true, "沈默" to false), list.map { it.name to it.existing })
    }

    @Test
    fun characterNamesComeFromHeadingsListsAndTables() {
        val doc = """
            # 人物档案
            ## 〇、核心人物卡（写前速查）
            | 人物 | 想要 | 害怕 |
            | --- | --- | --- |
            | 林月 | 一个家 | 被赶走 |
            ## 沈默
            想要：报仇
            - 老周：药铺伙计
            ## 人物名
        """.trimIndent()
        assertEquals(listOf("沈默", "林月", "老周").sorted(), WritePrompts.characterNames(doc).sorted())
        val appended = WritePrompts.appendCharacters("## 沈默\n游侠", listOf(BibleProposal("林月", "药铺掌柜的女儿", false), BibleProposal("沈默", "左手使刀", true)), "2026-10-24")
        assertEquals("## 沈默\n游侠\n\n## 林月\n药铺掌柜的女儿\n\n## 沈默\n左手使刀\n（AI 整理补充，2026-10-24）", appended)
    }

    @Test
    fun cleanStripsLabelsQuotesAndBlankLines() {
        assertEquals("风刮得很猛。\n他缩了缩脖子。", WritePrompts.clean("修改后：\n\n  风刮得很猛。\n\n　　他缩了缩脖子。\n", "风很大。"))
        assertEquals("风刮得很猛。", WritePrompts.clean("「风刮得很猛。」", "风很大。"))
        // 原文本来就是一句对话：引号留着。
        assertEquals("“你来了。”", WritePrompts.clean("“你来了。”", "“你来啦。”"))
    }

    @Test
    fun systemPromptCarriesStylePackAndAcceptedExamples() {
        val assist = WorkAssist(style = "第一人称，口语化", accepted = listOf(StyleExample("润色", "风很大。", "风刮得很猛。", 1)))
        val s = WritePrompts.system("长夜", assist, pack = "【人物档案·人物（优先级 3）】\n林月：药铺掌柜的女儿", examples = true)
        assertTrue("《长夜》" in s)
        assertTrue("作者对文风的要求（最优先" in s && "第一人称，口语化" in s)
        assertTrue("林月：药铺掌柜的女儿" in s)
        assertTrue("原文：风很大。\n改为：风刮得很猛。" in s)
        assertFalse("林月" in WritePrompts.system("长夜", assist))
        assertFalse("改为" in WritePrompts.system("长夜", assist))
    }

    @Test
    fun editPromptSeparatesContextFromTheTarget() {
        val p = WritePrompts.edit(WriteAction.POLISH, "风很大。", "他推门进来。", "他缩了缩脖子。", "第一章")
        assertTrue("前文（只作语境，不要改）：\n他推门进来。" in p)
        assertTrue("要改的文字：\n风很大。" in p)
        assertTrue("后文（只作语境，不要改）：\n他缩了缩脖子。" in p)
        val custom = WritePrompts.edit(WriteAction.CUSTOM, "风很大。", "", "", "", "改成第一人称")
        assertTrue("按作者的要求改这段文字：改成第一人称" in custom)
        val cont = WritePrompts.edit(WriteAction.CONTINUE, "", "他推门进来。", "", "第一章")
        assertTrue("上文：\n他推门进来。" in cont && "接着上文往下写" in cont)
    }

    @Test
    fun rememberKeepsTheNewestAcceptedEdits() {
        var a = WorkAssist()
        repeat(WorkAssist.KEEP_EXAMPLES + 5) { i -> a = WritePrompts.remember(a, WriteAction.POLISH, "原$i", "改$i", i.toLong()) }
        assertEquals(WorkAssist.KEEP_EXAMPLES, a.accepted.size)
        assertEquals("原${WorkAssist.KEEP_EXAMPLES + 4}", a.accepted.last().before)
        // 续写没有“原文”，不算改法。
        assertEquals(a, WritePrompts.remember(a, WriteAction.CONTINUE, "", "续", 0))
    }

    @Test
    fun assistIsSavedAndTravelsWithTheBackup() {
        val s = WorkStore(tmp.newFolder())
        val w = s.create("长夜")
        assertEquals(WorkAssist(), s.assist(w.id))
        s.updateAssist(w.id) { it.copy(realtime = true, style = "短句", entries = listOf(BibleEntry("e1", BibleKind.OUTLINE, "主线", "复仇"))) }
        assertEquals("短句", s.assist(w.id).style)
        assertTrue(s.assist(w.id).realtime)

        val other = WorkStore(tmp.newFolder())
        other.restoreBackup(String(s.backup()!!))
        val restored = other.assist(w.id)
        assertTrue(restored.realtime)
        // 旧版设定集的条目在备份时已并进设定包（大纲 → 总纲），跟着备份过去。
        val outline = other.pack(w.id).single()
        assertEquals(com.xycs.lingyuereader.data.PackKind.OUTLINE, outline.kind)
        assertEquals("## 主线\n复仇", other.packText(w.id, outline.id))
        // 作品不在了不能写设定。
        assertTrue(runCatching { s.updateAssist("nope") { it } }.isFailure)
    }
}
