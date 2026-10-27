package com.xycs.lingyuereader

import com.xycs.lingyuereader.data.PackImport
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.WorkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WritingImportSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun archiveWithIgnoredAsset(expandedBytes: Int): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("封面.png"))
            val zeros = ByteArray(8_192)
            var left = expandedBytes
            while (left > 0) {
                val count = minOf(left, zeros.size)
                zip.write(zeros, 0, count)
                left -= count
            }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("人物.md"))
            zip.write("林月".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    @Test
    fun directMarkdownKeepsChineseEncodingAndLeavesBlankSourcesUnchecked() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = WritingModel(WorkStore(tmp.newFolder()), scope) { throw AssertionError(it) }
            val gbk = "林月守渡口".toByteArray(Charset.forName("GBK"))
            val items = model.parseImport(listOf(
                "人物档案.md" to { gbk.inputStream() },
                "总纲.md" to { " \n\t".byteInputStream() },
                "封面.png" to { throw AssertionError("Unrelated file should not open") },
            ))
            assertEquals(2, items.size)
            assertEquals("林月守渡口", items[0].text)
            assertEquals(PackKind.CHARACTER, items[0].kind)
            assertFalse(items[1].include)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun directMarkdownStopsReadingAtItsByteLimit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = WritingModel(WorkStore(tmp.newFolder()), scope) { throw AssertionError(it) }
            val source = object : InputStream() {
                var remaining = PackImport.MAX_TEXT_BYTES + 1
                override fun read(): Int = if (remaining-- > 0) 'a'.code else -1
                override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(len, remaining)
                    bytes.fill('a'.code.toByte(), off, off + count)
                    remaining -= count
                    return count
                }
            }
            val error = runCatching { model.parseImport(listOf("巨型设定.md" to { source })) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("16 MB 导入上限"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun multipleZipsShareOneExpandedBudgetAndSourceCountIsLimited() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = WritingModel(WorkStore(tmp.newFolder()), scope) { throw AssertionError(it) }
            val archive = archiveWithIgnoredAsset(70 * 1024 * 1024)
            val error = runCatching {
                model.parseImport(listOf("一.zip" to { archive.inputStream() }, "二.zip" to { archive.inputStream() }))
            }.exceptionOrNull()
            assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("128 MB 上限"))

            val many: List<Pair<String, () -> InputStream>> = List(PackImport.MAX_IMPORT_SOURCES + 1) { n ->
                "资料$n.md" to { throw AssertionError("Source count should fail before opening files") }
            }
            val countError = runCatching { model.parseImport(many) }.exceptionOrNull()
            assertTrue(countError?.message.orEmpty(), countError?.message.orEmpty().contains("一次最多导入"))
        } finally {
            scope.cancel()
        }
    }
}
