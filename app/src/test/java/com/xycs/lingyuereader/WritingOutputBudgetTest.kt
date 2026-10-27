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
}
