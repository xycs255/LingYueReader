package com.xycs.lingyuereader.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConfigTest {
    @Test
    fun offByDefault() {
        assertFalse(AiConfig().enabled)
        assertFalse(AiConfig().ready)
    }

    @Test
    fun eachProviderKeepsItsOwnKey() {
        val deepseek = AiConfig(enabled = true).withKey("sk-deepseek")
        assertTrue(deepseek.ready)
        val kimi = deepseek.withPreset(AiPresets.of("kimi"))
        assertEquals("https://api.moonshot.cn/v1", kimi.baseUrl)
        assertEquals("", kimi.apiKey)
        assertFalse(kimi.ready)
        assertEquals("sk-deepseek", kimi.withPreset(AiPresets.of("deepseek")).apiKey)
    }

    @Test
    fun customStartsFromTheCurrentEndpoint() {
        val custom = AiConfig().withPreset(AiPresets.of(AiPresets.CUSTOM))
        assertEquals(AiConfig().baseUrl, custom.baseUrl)
        assertEquals("example.com", custom.copy(baseUrl = "https://api.example.com/v1").providerLabel)
    }

    @Test
    fun neverPrintsTheKey() {
        assertFalse(AiConfig().withKey("sk-secret-123").toString().contains("secret"))
    }
}
