package com.xycs.lingyuereader.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class AiConfigTest {
    @Test
    fun offByDefault() {
        assertFalse(AiConfig().enabled)
        assertFalse(AiConfig().ready)
        assertEquals(AiPresets.DEEPSEEK_V4_PRO, AiConfig().model)
        assertEquals(128_000, AiConfig().contextTokens)
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

    @Test
    fun retiredDeepSeekModelNeedsAnExplicitChoiceAndKeepsTheKey() {
        val old = AiConfig(enabled = true).withKey("sk-existing").copy(model = "deepseek-chat")
        assertTrue(old.retiredOfficialDeepSeek)
        assertFalse(old.complete)
        assertFalse(old.ready)
        assertEquals("deepseek-chat", old.withPreset(AiPresets.of(AiPresets.DEEPSEEK)).model)
        assertTrue(AiPresets.retiredDeepSeekModel(" DeepSeek-Reasoner "))
        assertTrue(AiPresets.retiredDeepSeekModel("deepseek-v4-flash"))
        assertFalse(AiPresets.retiredDeepSeekModel(AiPresets.DEEPSEEK_FLASH))
        assertTrue(old.copy(model = "deepseek-v4-flash").retiredOfficialDeepSeek)
        assertTrue(old.copy(model = "deepseek-v4-flash").ready)
        val proxy = old.copy(baseUrl = "https://proxy.example.com/v1")
        assertFalse(proxy.retiredOfficialDeepSeek)
        assertTrue(proxy.ready)
        val flash = old.copy(model = AiPresets.DEEPSEEK_FLASH)
        assertEquals("sk-existing", flash.apiKey)
        assertTrue(flash.ready)
        assertEquals(128_000, flash.contextTokens)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class AiStoreDeepSeekCompatibilityTest {
    @Test
    fun savedLegacyModelAndKeyAreNotSilentlyUpgraded() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AiStore.FILE, Context.MODE_PRIVATE).edit()
            .clear()
            .putBoolean("enabled", true)
            .putString("preset", AiPresets.DEEPSEEK)
            .putString("protocol", AiProtocol.OPENAI.name)
            .putString("baseUrl", "https://api.deepseek.com")
            .putString("model", "deepseek-chat")
            .putString("key.deepseek", "sk-existing")
            .commit()

        val loaded = AiStore(context).load()
        assertEquals("deepseek-chat", loaded.model)
        assertEquals("sk-existing", loaded.apiKey)
        assertFalse(loaded.ready)

        AiStore(context).save(loaded.copy(model = AiPresets.DEEPSEEK_V4_PRO))
        val switched = AiStore(context).load()
        assertEquals(AiPresets.DEEPSEEK_V4_PRO, switched.model)
        assertEquals("sk-existing", switched.apiKey)
        assertTrue(switched.ready)
    }
}
