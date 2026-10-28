package com.xycs.lingyuereader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 作品的“写正文的温度”和“写这一拍出 3 个方向”：存盘、备份恢复都带上，旧数据缺字段读成默认。 */
class WorkAssistTemperatureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun temperatureAndVariantsSurviveJsonAndBackup() {
        val dirs = listOf(tmp.newFolder("a"), tmp.newFolder("b"))
        val a = WorkStore(dirs[0])
        val w = a.create("渡口")
        // 新作品：服务商默认、不勾。
        assertNull(a.assist(w.id).temperature)
        assertFalse(a.assist(w.id).beatVariants)

        a.updateAssist(w.id) { it.copy(temperature = 1.3f, beatVariants = true) }
        val file = File(dirs[0], "works/${w.id}/assist.json")
        // 按十进制存：1.3 不存成 1.2999999523。
        assertEquals("1.3", JSONObject(file.readText()).get("temperature").toString())
        assertEquals(1.3f, WorkStore(dirs[0]).assist(w.id).temperature)
        assertTrue(WorkStore(dirs[0]).assist(w.id).beatVariants)

        // 备份恢复带上。
        val b = WorkStore(dirs[1])
        assertEquals(1, b.restoreBackup(String(a.backup()!!)))
        assertEquals(1.3f, b.assist(w.id).temperature)
        assertTrue(b.assist(w.id).beatVariants)

        // 改回服务商默认：存盘里不再有这个字段。
        a.updateAssist(w.id) { it.copy(temperature = null) }
        assertFalse(JSONObject(file.readText()).has("temperature"))
        assertNull(WorkStore(dirs[0]).assist(w.id).temperature)
    }

    @Test
    fun oldOrDamagedDataReadsAsTheProviderDefault() {
        val dir = tmp.newFolder("old")
        val store = WorkStore(dir)
        val w = store.create("渡口")
        store.updateAssist(w.id) { it.copy(temperature = 1.5f, beatVariants = true, style = "短句为主") }
        val file = File(dir, "works/${w.id}/assist.json")
        // 旧数据没有这两项。
        file.writeText(JSONObject(file.readText()).apply { remove("temperature"); remove("beatVariants") }.toString())
        val old = WorkStore(dir).assist(w.id)
        assertNull(old.temperature)
        assertFalse(old.beatVariants)
        assertEquals("短句为主", old.style)
        // 坏数据（超出 0 到 2、不是数）当作服务商默认，别的设置照旧。
        for (bad in listOf<Any>(9.5, -1, "很高", JSONObject.NULL)) {
            file.writeText(JSONObject(file.readText()).put("temperature", bad).toString())
            assertNull("$bad", WorkStore(dir).assist(w.id).temperature)
            assertEquals("短句为主", WorkStore(dir).assist(w.id).style)
        }
    }
}
