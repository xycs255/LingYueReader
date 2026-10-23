package com.xycs.lingyuereader.dict

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PinyinAnnotatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun polyphonesAreReadByTheWordTheyAreIn() {
        val a = Dictionaries(context).pinyinAnnotator()
        val r = a.annotate("他去银行，行走在路上。")
        assertEquals(listOf("tā", "qù", "yín", "háng", null, "xíng", "zǒu", "zài", "lù", "shang", null), r.toList())
        // 词典里“路上”的“上”是轻声；“行长”的“长”读 zhǎng。
        val chief = a.annotate("银行行长")
        assertEquals(listOf("yín", "háng", "háng", "zhǎng"), chief.toList())
        assertNull(a.annotate("ABC，１２")[0])
    }

    @Test
    fun theLongestWordWinsAndUnknownCharactersFallBack() {
        val words = mapOf("银行" to "yín háng", "行长" to "háng zhǎng", "银行行长" to "yín háng háng zhǎng")
        val chars = mapOf("长" to "cháng", "行" to "xíng", "去" to "qù")
        val a = PinyinAnnotator({ words[it] }, { chars[it] })
        assertEquals(listOf("qù", "yín", "háng", "háng", "zhǎng"), a.annotate("去银行行长").toList())
        // 不在词里的字取最常用的读音。
        assertEquals(listOf("cháng"), a.annotate("长").toList())
    }
}
