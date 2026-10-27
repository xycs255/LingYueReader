package com.xycs.lingyuereader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WritingExportStagingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun sourceFailureDoesNotOpenOrTruncateExistingExport() = runBlocking {
        val existing = tmp.newFile("old.md").apply { writeText("此前导出的完整文稿") }
        var opened = false
        val error = runCatching {
            stagedWritingExport(tmp.root, {
                opened = true
                existing.outputStream()
            }) { output ->
                output.write("尚未完成".toByteArray())
                error("正文读取失败")
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(opened)
        assertEquals("此前导出的完整文稿", existing.readText())
        assertTrue(tmp.root.listFiles().orEmpty().none { it.name.startsWith("writing-export-") })
    }

    @Test fun completedExportCopiesBytesAndRemovesStagingFile() = runBlocking {
        val target = tmp.newFile("next.zip")
        stagedWritingExport(tmp.root, { target.outputStream() }) { it.write(byteArrayOf(0, 1, 2, 3)) }
        assertTrue(target.readBytes().contentEquals(byteArrayOf(0, 1, 2, 3)))
        assertTrue(tmp.root.listFiles().orEmpty().none { it.name.startsWith("writing-export-") })
    }
}
