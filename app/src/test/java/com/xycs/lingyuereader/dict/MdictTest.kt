package com.xycs.lingyuereader.dict

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 用 mdict-utils 生成的真实 .mdx/.mdd（tools/make_mdict_fixtures.py）测试读取，
 * 覆盖 1.2/2.0 版、无压缩/LZO/zlib、加密索引、UTF-8/UTF-16/GBK。
 */
class MdictTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String) = File("src/test/resources/mdict/$name")

    private fun open(name: String, withMdd: Boolean = false): MdictDictionary {
        val mdx = fixture(name)
        val idx = tmp.newFile("$name.idx")
        MdictDictionary.buildIndex(MdictFile(mdx), idx, isResource = false)
        val mdds = if (withMdd) {
            val mdd = fixture(name.replace(".mdx", ".mdd"))
            val mddIdx = tmp.newFile("$name.mdd.idx")
            MdictDictionary.buildIndex(MdictFile(mdd, isMdd = true), mddIdx, isResource = true)
            listOf(mdd to mddIdx)
        } else {
            emptyList()
        }
        return MdictDictionary.open(mdx, idx, mdds, emptyList())
    }

    @Test
    fun readsEveryFormatVariant() {
        for (name in listOf("v2_zlib_utf8.mdx", "v2_zlib_encrypted_index.mdx", "v1_lzo_utf8.mdx", "v1_plain_utf16.mdx", "v2_zlib_gbk.mdx")) {
            val dict = open(name)
            assertEquals(name, "测试词典", dict.title)
            val bank = dict.lookup("银行").single()
            assertTrue(name, bank.html.contains("经营存款、贷款等业务的金融机构"))
            assertEquals(name, "<p>第 1234 条的释义。</p>", dict.lookup("词条1234").single().html)
            assertEquals(name, "<p>第 2999 条的释义。</p>", dict.lookup("词条2999").single().html)
            assertTrue(name, dict.lookup("没有这个词").isEmpty())
        }
    }

    @Test
    fun countsEntriesAndIgnoresCase() {
        val file = MdictFile(fixture("v2_zlib_utf8.mdx"))
        assertEquals(3004L, file.entryCount)
        var n = 0
        file.forEachKey { _, _, size -> assertTrue(size > 0); n++ }
        assertEquals(3004, n)
        assertEquals("<p>英文问候语</p>", open("v2_zlib_utf8.mdx").lookup("hello").single().html)
    }

    @Test
    fun followsLinksToTheTargetEntry() {
        val linked = open("v2_zlib_utf8.mdx").lookup("银號").single()
        assertEquals("银行", linked.key)
    }

    @Test
    fun resourcesComeFromTheMdd() {
        val dict = open("v2_zlib_utf8.mdx", withMdd = true)
        val png = dict.resource("bank.png")
        assertNotNull(png)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), png!!.copyOf(4))
        assertEquals(".def{color:#a23b2a}", String(dict.resource("/style.css")!!))
    }

    @Test
    fun resourcesAreFoundEvenWhenTheEntryWritesAnotherFolderOrDecoratesThePath() {
        val dict = open("v2_zlib_utf8.mdx", withMdd = true)
        // .mdd 里存的是 \bank.png；释义里写了别的目录、带了 ./ 和参数，按文件名也能找到。
        assertNotNull(dict.resource("images/bank.png"))
        assertNotNull(dict.resource("./BANK.PNG?v=2#top"))
        assertNotNull(dict.resource("..\\pic\\bank.png"))
        assertNull(dict.resource("nothing.gif"))
    }

    @Test
    fun aTruncatedRecordSectionIsRejectedWhenOpened() {
        // 文件不完整（下载、复制到一半）：块信息里的正文块加起来比文件还长，导入时就报错，不等查词时读出界。
        for (name in listOf("v2_zlib_utf8.mdx", "v1_lzo_utf8.mdx")) {
            val bytes = fixture(name).readBytes()
            val cut = tmp.newFile("cut-$name").apply { writeBytes(bytes.copyOf(bytes.size - 64)) }
            val error = runCatching { MdictFile(cut) }.exceptionOrNull()
            assertTrue(name, error is MdictFile.Unsupported)
            val whole = tmp.newFile("whole-$name").apply { writeBytes(bytes) }
            assertTrue(name, MdictFile(whole).entryCount > 0)
        }
    }

    @Test
    fun ripemd128TestVectors() {
        fun hex(s: String) = Ripemd128.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("cdf26213a150dc3ecb610f18f6b38b46", hex(""))
        assertEquals("c14a12199c66e4ba84636b0f69144c77", hex("abc"))
        assertEquals("9e327b3d6e523062afc1132d7df9d1b8", hex("message digest"))
    }
}
