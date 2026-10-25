package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FontStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val font = File("src/test/resources/testfonts/TestBlocks.ttf")

    @Test
    fun importsFontsAndRejectsOtherFiles() {
        val store = FontStore(File(tmp.root, "fonts"))
        assertEquals("TestBlocks.ttf", store.import("TestBlocks.ttf", font.inputStream()))
        val bad = runCatching { store.import("readme.ttf", "not a font".byteInputStream()) }
        assertTrue(bad.isFailure)
        assertEquals(listOf("TestBlocks.ttf"), store.list())
        assertEquals("TestBlocks", FontStore.label("TestBlocks.ttf"))
        // 不能借文件名跳出字体目录。
        assertNull(store.file("../fonts/TestBlocks.ttf"))
        store.delete("TestBlocks.ttf")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun replacingFontKeepsOldFileIfAtomicMoveFails() {
        val dir = File(tmp.root, "fonts")
        val store = FontStore(dir)
        store.import("TestBlocks.ttf", font.inputStream())
        val target = File(dir, "TestBlocks.ttf")
        val original = target.readBytes()
        val replacement = original.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val failing = FontStore(dir) { _, _ -> throw IOException("injected move failure") }

        assertTrue(runCatching { failing.import("TestBlocks.ttf", replacement.inputStream()) }.exceptionOrNull() is IOException)
        assertArrayEquals(original, target.readBytes())
        assertEquals(listOf("TestBlocks.ttf"), store.list())
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("font-") && it.extension == "tmp" })

        store.import("TestBlocks.ttf", replacement.inputStream())
        assertArrayEquals(replacement, target.readBytes())
    }
}
