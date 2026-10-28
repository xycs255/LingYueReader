package com.xycs.lingyuereader

import com.xycs.lingyuereader.data.ReferenceExcerpt
import com.xycs.lingyuereader.data.ReferencePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 照范本写：范本那一章本身可能很长（整章最多带一万二千字，逐拍写时每一拍都带一遍），
 * 提示里要说明只学写法、篇幅以任务卡为准，不跟着范本铺开。
 */
class ReferenceLengthTest {
    @Test
    fun theReferenceTeachesTechniqueNotLength() {
        val text = "夜里有人叩门。掌柜先问来者身份。".repeat(600)
        val excerpt = ReferenceExcerpt("r1", "合成范本", "c1", "第一章 门外", ReferencePart.CHAPTER, 0, text.length, text,
            "章指纹", "片段指纹", chapterLength = text.length)
        val prompt = ReferenceWriting.prompt(ReferenceWritingMaterial("a1", excerpt))
        assertTrue("只学写法，篇幅以任务卡为准" in prompt)
        assertTrue("不跟着范本的长短、节奏铺开" in prompt)
        assertTrue("篇幅以任务卡为准" in ReferenceWriting.SYSTEM_RULE)
        // 不能有让篇幅跟着范本走的说法。
        assertFalse(Regex("照范本的篇幅|与范本篇幅相当|篇幅和范本").containsMatchIn(prompt + ReferenceWriting.SYSTEM_RULE))
    }
}
