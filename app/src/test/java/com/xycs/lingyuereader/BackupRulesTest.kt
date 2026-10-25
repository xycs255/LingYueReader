package com.xycs.lingyuereader

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** 系统云备份每个应用只有 25 MB，超了整个应用都不再备份：大文件夹都要排除。 */
class BackupRulesTest {
    private val big = listOf("books/", "dicts/", "fonts/", "tts-models/", "llm/")

    private fun excludedFiles(xml: String, section: String): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$xml"))
        val root = doc.getElementsByTagName(section).item(0) as Element
        val excludes = root.getElementsByTagName("exclude")
        return (0 until excludes.length).map { excludes.item(it) as Element }
            .filter { it.getAttribute("domain") == "file" }.map { it.getAttribute("path") }.toSet()
    }

    @Test
    fun largeFoldersStayOutOfCloudBackup() {
        val old = excludedFiles("backup_rules.xml", "full-backup-content")
        val cloud = excludedFiles("data_extraction_rules.xml", "cloud-backup")
        big.forEach { path ->
            assertTrue("备份规则（Android 11 及以前）漏了 $path", path in old)
            assertTrue("云备份规则（Android 12 起）漏了 $path", path in cloud)
        }
    }
}
