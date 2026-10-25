package com.xycs.lingyuereader.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DeviceModelsImportTest {
    @Test
    fun importedUppercaseTaskAppearsInInstalledListAfterRestart() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("device-model").toFile()
        try {
            val id = DeviceModelFiles(dir).import("reader.TASK", { byteArrayOf(1, 2, 3).inputStream() }, 3L) { _, _, _ -> }
            assertEquals("file:reader.TASK", id)
            assertTrue(DeviceModelFiles(dir).installed(id))
            assertTrue(id in DeviceModelFiles(dir).installedIds())
        } finally {
            dir.deleteRecursively()
        }
    }
}
