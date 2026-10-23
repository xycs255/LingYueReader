package com.xycs.lingyuereader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
}
