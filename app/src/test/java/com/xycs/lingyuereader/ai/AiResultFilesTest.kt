package com.xycs.lingyuereader.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** 整章译文、速读、伴读、讲书稿：写失败不冲掉旧结果，读不出的文件先另存一份，不被接下来的保存覆盖。 */
class AiResultFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun failedWritesThrowAndKeepEveryEarlierResult() {
        val translations = File(tmp.root, AiTranslate.FILE)
        val old = Translations(chapters = mapOf(0 to ("第一章 林月" to mapOf(1 to "林月推开了门。"))))
        AiTranslate.write(translations, old)
        val summaries = File(tmp.root, AiSummaries.FILE)
        AiSummaries.write(summaries, mapOf(0 to ("第一章 林月" to "林月进山。")))
        val companion = File(tmp.root, AiCompanion.FILE)
        val c = ChapterCompanion(listOf("林月进山"), listOf("她为何独行？"))
        AiCompanion.write(companion, mapOf(0 to ("第一章 林月" to c)))
        val talks = File(tmp.root, AiTalk.FILE)
        AiTalk.write(talks, mapOf("k" to Talk("沈默的夜", listOf(TalkLine(0, "大家好。")), at = 1)))

        // 临时文件的位置被占住：写不成。必须抛给调用方，旧文件原样留着。
        listOf(translations, summaries, companion, talks).forEach { File(tmp.root, it.name + ".tmp").mkdir() }
        assertTrue(runCatching { AiTranslate.write(translations, Translations()) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { AiSummaries.write(summaries, emptyMap()) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { AiCompanion.write(companion, emptyMap()) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { AiTalk.write(talks, emptyMap()) }.exceptionOrNull() is IOException)
        assertEquals(old, AiTranslate.load(translations))
        assertEquals(mapOf(0 to ("第一章 林月" to "林月进山。")), AiSummaries.load(summaries))
        assertEquals(c, AiCompanion.load(companion)[0]?.second)
        assertEquals("沈默的夜", AiTalk.load(talks)["k"]?.title)
        assertTrue("没有找到保存位置也要报错", runCatching { AiTranslate.write(null, old) }.exceptionOrNull() is IOException)
    }

    @Test
    fun damagedFilesAreKeptBeforeTheyAreTreatedAsEmpty() {
        val file = File(tmp.root, AiSummaries.FILE).apply { writeText("{\"0\":{\"t\":\"第一章") }
        assertTrue("显示时按没有摘要算", AiSummaries.read(file).isEmpty())
        val copy = File(tmp.root, AiSummaries.FILE + ".damaged")
        assertEquals("{\"0\":{\"t\":\"第一章", copy.readText())
        // 之后存新的摘要：坏文件已经另存，不会被冲掉。
        AiSummaries.write(file, AiSummaries.load(file) + (1 to ("第二章 老周" to "老周下山。")))
        assertEquals(mapOf(1 to ("第二章 老周" to "老周下山。")), AiSummaries.read(file))
        assertEquals("{\"0\":{\"t\":\"第一章", copy.readText())

        val translations = File(tmp.root, AiTranslate.FILE).apply { writeText("{\"chapters\":") }
        assertEquals(Translations(), AiTranslate.read(translations))
        assertTrue(File(tmp.root, AiTranslate.FILE + ".damaged").isFile)
    }

    @Test
    fun aFileThatCannotEvenBeCopiedIsNeverOverwrittenAsEmpty() {
        // 读不出、也另存不了（这里用同名的文件夹模拟）：显示时当空的，要改了再存时必须报错。
        val talks = File(tmp.root, AiTalk.FILE).apply { mkdir() }
        assertTrue(AiTalk.read(talks).isEmpty())
        assertTrue(runCatching { AiTalk.load(talks) }.exceptionOrNull() is IOException)
        assertFalse(File(tmp.root, AiTalk.FILE + ".damaged").exists())
        val companion = File(tmp.root, AiCompanion.FILE).apply { mkdir() }
        assertTrue(runCatching { AiCompanion.load(companion) }.exceptionOrNull() is IOException)
        assertTrue("文件不存在就是空的", AiTranslate.load(File(tmp.root, "none.json")).chapters.isEmpty())
    }
}
