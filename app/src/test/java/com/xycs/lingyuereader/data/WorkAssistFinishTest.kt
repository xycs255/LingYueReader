package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** “这章写完了”的勾选项：默认整理、摘要勾上，审稿不勾；存盘、备份恢复都带上，旧数据缺字段读成默认。 */
class WorkAssistFinishTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun defaultsSyncAndSummaryButNotReview() {
        val d = FinishOptions()
        assertTrue(d.sync)
        assertTrue(d.summary)
        assertFalse(d.review)
        assertEquals(d, WorkAssist().finish)
    }

    @Test
    fun optionsSurviveJsonAndBackup() {
        val dirs = listOf(tmp.newFolder("a"), tmp.newFolder("b"))
        val a = WorkStore(dirs[0])
        val w = a.create("长夜")
        assertEquals(FinishOptions(), a.assist(w.id).finish)

        val picked = FinishOptions(sync = false, summary = true, review = true)
        a.updateAssist(w.id) { it.copy(finish = picked) }
        val file = File(dirs[0], "works/${w.id}/assist.json")
        val saved = JSONObject(file.readText()).getJSONObject("finish")
        assertFalse(saved.getBoolean("sync"))
        assertTrue(saved.getBoolean("review"))
        assertEquals(picked, WorkStore(dirs[0]).assist(w.id).finish)

        // 备份恢复带上。
        val b = WorkStore(dirs[1])
        assertEquals(1, b.restoreBackup(String(a.backup()!!)))
        assertEquals(picked, b.assist(w.id).finish)
    }

    @Test
    fun oldDataWithoutTheFieldReadsAsTheDefaults() {
        val dir = tmp.newFolder("old")
        val store = WorkStore(dir)
        val w = store.create("长夜")
        store.updateAssist(w.id) { it.copy(finish = FinishOptions(sync = false, summary = false, review = true), style = "短句为主") }
        val file = File(dir, "works/${w.id}/assist.json")
        file.writeText(JSONObject(file.readText()).apply { remove("finish") }.toString())
        val old = WorkStore(dir).assist(w.id)
        assertEquals(FinishOptions(), old.finish)
        assertEquals("短句为主", old.style)
        // 只缺其中一项：缺的那项按默认，别的照存的。
        file.writeText(JSONObject(file.readText()).put("finish", JSONObject().put("review", true)).toString())
        assertEquals(FinishOptions(sync = true, summary = true, review = true), WorkStore(dir).assist(w.id).finish)
    }
}
