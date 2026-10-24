package com.xycs.lingyuereader.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** 按 saveRules 的真实目录交换步骤构造中断现场，再重新创建 Library 触发启动恢复。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class RulesRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.filesDir, "books")
    private val index get() = File(root, "index.json")
    private lateinit var library: Library

    @Before fun setUp() {
        root.deleteRecursively()
        library = Library(context)
    }

    private fun book(): BookEntry {
        val file = File(context.cacheDir, "规则中断.txt").apply { writeText("第一章 起\n　　旧正文。\n") }
        return library.add(library.describe(listOf(Uri.fromFile(file))), null)
    }

    private fun stage(entry: BookEntry): File {
        val staging = File(root, ".rules-${entry.id}-${UUID.randomUUID()}")
        val dir = File(root, entry.id)
        assertTrue(dir.copyRecursively(File(staging, "next")))
        File(staging, "index-before.json").writeBytes(index.readBytes())
        return staging
    }

    private fun text(library: Library, entry: BookEntry): String =
        (library.content(library.list().single { it.id == entry.id }).chapters.single().blocks.single() as Block.Para).text

    @Test fun deathAfterOldDirectoryMoveRestoresReadableOriginal() {
        val entry = book()
        val before = index.readBytes()
        val staging = stage(entry)
        val dir = File(root, entry.id)
        assertTrue(dir.renameTo(File(staging, "previous")))
        assertFalse(dir.exists())

        val reopened = Library(context)

        assertTrue(dir.isDirectory)
        assertFalse(staging.exists())
        assertArrayEquals(before, index.readBytes())
        assertEquals("旧正文。", text(reopened, entry))
    }

    @Test fun deathAfterNewDirectoryInstallBeforeCommitRollsBackBookAndIndex() {
        val entry = book()
        val before = index.readBytes()
        val staging = stage(entry)
        val dir = File(root, entry.id)
        File(staging, "next/rules.json").writeText(TextRules(listOf(ReplaceRule("旧", "新"))).toJson().toString())
        assertTrue(dir.renameTo(File(staging, "previous")))
        assertTrue(File(staging, "next").renameTo(dir))
        val changed = JSONArray(index.readText()).apply { getJSONObject(0).put("charCount", 9999) }
        index.writeText(changed.toString()) // 模拟索引已写，但事务标记尚未落盘。

        val reopened = Library(context)

        assertFalse(staging.exists())
        assertArrayEquals(before, index.readBytes())
        assertEquals(TextRules(), reopened.rules(entry.id))
        assertEquals("旧正文。", text(reopened, entry))
    }

    @Test fun committedDirectorySurvivesRestartAndOldCopyIsCleaned() {
        val entry = book()
        val staging = stage(entry)
        val dir = File(root, entry.id)
        val newRules = TextRules(listOf(ReplaceRule("旧", "新版")))
        val expectedChars = library.previewWith(entry, newRules).charCount
        File(staging, "next/rules.json").writeText(newRules.toJson().toString())
        assertTrue(dir.renameTo(File(staging, "previous")))
        assertTrue(File(staging, "next").renameTo(dir))
        index.writeText(JSONArray(index.readText()).apply { getJSONObject(0).put("charCount", expectedChars) }.toString())
        File(staging, "committed").writeText("ok")

        val reopened = Library(context)

        assertFalse(staging.exists())
        assertEquals(newRules, reopened.rules(entry.id))
        assertEquals(expectedChars, reopened.list().single().charCount)
        assertEquals("新版正文。", text(reopened, entry))
    }

    @Test fun deathDuringRollbackAfterRestoringOldDirectoryStillRestoresIndex() {
        val entry = book()
        val before = index.readBytes()
        val staging = stage(entry)
        val dir = File(root, entry.id)
        assertTrue(File(staging, "next").renameTo(File(staging, "abandoned")))
        index.writeText(JSONArray(index.readText()).apply { getJSONObject(0).put("charCount", 9999) }.toString())

        val reopened = Library(context)

        assertFalse(staging.exists())
        assertArrayEquals(before, index.readBytes())
        assertEquals("旧正文。", text(reopened, entry))
        assertTrue(dir.isDirectory)
    }
}
