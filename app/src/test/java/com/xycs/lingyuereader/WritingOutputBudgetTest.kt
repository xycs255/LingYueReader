package com.xycs.lingyuereader

import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WritingOutputBudgetTest {
    @Test
    fun v4ChapterBudgetLeavesRoomForThinkingAndProse() {
        val base = AiConfig().withPreset(AiPresets.of("deepseek"))
        assertEquals(32_768, draftOutputTokenLimit(base.copy(model = "deepseek-v4-pro")))
        assertEquals(16_384, draftOutputTokenLimit(base.copy(model = "deepseek-flash")))
        assertEquals(16_384, draftOutputTokenLimit(base.copy(model = "deepseek-v4-flash")))
        assertEquals(8_192, draftOutputTokenLimit(base.copy(model = "deepseek-chat")))
    }

    @Test
    fun otherProvidersKeepTheirPreviousCaps() {
        assertEquals(8_192, draftOutputTokenLimit(AiConfig().withPreset(AiPresets.of("qwen"))))
        assertNull(draftOutputTokenLimit(AiConfig().withPreset(AiPresets.of("claude"))))
        // A custom model name by itself does not imply the official V4 output limit.
        assertNull(draftOutputTokenLimit(AiConfig(preset = AiPresets.CUSTOM,
            baseUrl = "https://other.example/v1", model = "deepseek-v4-pro")))
    }

    /** 编辑器里写一段：开着思考的 V4 用整章的上限（思考也算在里面），别的照旧压到 8192。 */
    @Test
    fun inlineKeepsTheFullBudgetOnlyWhenAThinkingV4Thinks() {
        val base = AiConfig().withPreset(AiPresets.of("deepseek"))
        val pro = base.copy(model = "deepseek-v4-pro")
        assertEquals(32_768, inlineOutputTokenLimit(pro, thinking = true))
        assertEquals(8_192, inlineOutputTokenLimit(pro, thinking = false))
        assertEquals(16_384, inlineOutputTokenLimit(base.copy(model = "deepseek-flash"), thinking = true))
        assertEquals(16_384, inlineOutputTokenLimit(base.copy(model = "deepseek-v4-flash"), thinking = true))
        assertEquals(8_192, inlineOutputTokenLimit(base.copy(model = "deepseek-flash"), thinking = false))
        // 不能开关思考的：开着也照旧。
        assertEquals(8_192, inlineOutputTokenLimit(base.copy(model = "deepseek-chat"), thinking = true))
        assertEquals(8_192, inlineOutputTokenLimit(AiConfig().withPreset(AiPresets.of("qwen")), thinking = true))
        assertNull(inlineOutputTokenLimit(AiConfig().withPreset(AiPresets.of("claude")), thinking = true))
        assertNull(inlineOutputTokenLimit(AiConfig(preset = AiPresets.CUSTOM,
            baseUrl = "https://other.example/v1", model = "deepseek-v4-pro"), thinking = true))
    }

    @Test
    fun emptyProseSaysWhyAndWhatToDo() {
        assertEquals("AI 没有写出正文，再试一次", emptyProseMessage(truncated = false, suggestThinkingOff = true))
        assertEquals("模型把这次的输出上限都用在思考上，没写出正文", emptyProseMessage(truncated = true, suggestThinkingOff = false))
        assertEquals("模型把这次的输出上限都用在思考上，没写出正文。可以在作品 AI 设置里关掉“写正文时先思考”再试",
            emptyProseMessage(truncated = true, suggestThinkingOff = true))
    }
}
