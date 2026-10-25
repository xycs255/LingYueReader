package com.xycs.lingyuereader.dict

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** 一本导入的词典读坏了，只跳过它：内置词典和别的词典照常给出结果。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DictionaryLookupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun oneBrokenImportedDictionaryDoesNotEmptyTheWholeLookup() {
        File(context.filesDir, "dicts").deleteRecursively()
        val dicts = Dictionaries(context)
        val picked = listOf("v2_zlib_utf8.mdx", "v1_lzo_utf8.mdx").map { name ->
            val f = File(context.cacheDir, name)
            File("src/test/resources/mdict/$name").copyTo(f, overwrite = true)
            Library.Picked(Uri.fromFile(f), name)
        }
        picked.forEach { p -> assertEquals(1, dicts.import(listOf(p)) { File(it.uri.path!!).inputStream() }.added.size) }
        val before = dicts.lookup("银行")
        assertEquals(2, before.imported.size)
        assertTrue(before.words.isNotEmpty())

        // 第一本的正文文件没了（读出错）：查词不能整个失败。
        val broken = dicts.settings().dicts.first()
        assertTrue(File(File(context.filesDir, "dicts/${broken.id}"), broken.mdx).delete())
        val after = dicts.lookup("银行")
        assertTrue("内置词典的结果还在", after.words.isNotEmpty())
        assertEquals(listOf(dicts.settings().dicts[1].id), after.imported.map { it.first.id })
        assertTrue(dicts.isWord("银行"))
    }
}
