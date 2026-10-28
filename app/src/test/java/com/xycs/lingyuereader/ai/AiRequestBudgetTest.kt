package com.xycs.lingyuereader.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRequestBudgetTest {
    private val deepSeek = AiConfig()

    @Test
    fun estimatesActualFirstWireRequestAndWorkflowStagesWithoutAssumingCacheHits() {
        val tool = AiTool("read_doc", "按标题读取设定文档", JSONObject().put("type", "object"))
        val withTool = AiRequestBudget.preview(deepSeek, "设定包原文", "写本章", listOf(tool), 32_768, workflow = true)
        val withoutTool = AiRequestBudget.preview(deepSeek, "设定包原文", "写本章", emptyList(), 32_768, workflow = true)

        assertEquals("deepseek-v4-pro", withTool.model)
        assertEquals(2, withTool.minimumStages)
        assertEquals(4, withTool.maximumStages)
        assertEquals(6, withTool.maxToolRounds)
        assertTrue(withTool.tools)
        assertTrue(withTool.inputTokens > withoutTool.inputTokens)
        assertEquals(32_768, withTool.outputTokenLimit)
        assertTrue(withTool.firstRequestLow!!.cny > 0)
        assertTrue(withTool.firstRequestHigh!!.cny > withTool.firstRequestLow.cny)
        assertEquals(0.0, withTool.firstRequestLow.usd, 0.0)
    }

    @Test
    fun manualLongKnowledgeShowsPlanningWarningWithoutSilentlyTruncatingIt() {
        val system = "设定".repeat(150_000)
        val estimate = AiRequestBudget.preview(deepSeek, system, "写本章", emptyList(), 32_768, workflow = true)

        assertTrue(estimate.exceedsPlanningBudget)
        assertTrue(estimate.inputTokens > deepSeek.contextTokens)
        assertEquals(2, estimate.minimumStages)
        assertEquals(4, estimate.maximumStages)
        assertFalse(estimate.onDevice)
    }

    @Test
    fun unknownPriceAndOutputLimitAreShownAsUnknown() {
        val custom = AiConfig(preset = AiPresets.CUSTOM, baseUrl = "https://example.com/v1", model = "my-model")
        val estimate = AiRequestBudget.preview(custom, "系统", "写本章", emptyList(), null, workflow = false)

        assertEquals(1, estimate.minimumStages)
        assertEquals(1, estimate.maximumStages)
        assertNull(estimate.firstRequestLow)
        assertNull(estimate.firstRequestHigh)
        assertNull(estimate.outputTokenLimit)
    }

    @Test
    fun onDeviceModelHasNoProviderCharge() {
        val device = AiConfig().withPreset(AiPresets.of(AiPresets.DEVICE)).copy(model = "local-model")
        val estimate = AiRequestBudget.preview(device, "本章资料", "写本章", emptyList(), null, workflow = false)

        assertTrue(estimate.onDevice)
        assertTrue(estimate.inputTokens > 0)
        assertNull(estimate.firstRequestLow)
        assertNull(estimate.firstRequestHigh)
    }

    @Test
    fun dollarPricesPreserveCurrencyForUserExchangeRate() {
        val claude = AiConfig().withPreset(AiPresets.of("claude")).copy(usdRate = 7.25)
        val estimate = AiRequestBudget.preview(claude, "系统", "写本章", emptyList(), null, workflow = false)

        assertEquals(AiWire.MAX_TOKENS, estimate.outputTokenLimit)
        assertTrue(estimate.firstRequestHigh!!.usd > estimate.firstRequestLow!!.usd)
        val low = estimate.firstRequestLow!!
        assertTrue(low.usd > 0)
        assertEquals(0.0, low.cny, 0.0)
        assertEquals(low.usd * claude.usdRate, low.inCny(claude.usdRate), 0.000001)
    }

    @Test
    fun anthropicFirstCacheWritePremiumUsesBothRealSystemBreakpoints() {
        val claude = AiConfig().withPreset(AiPresets.of("claude"))
        val prefix = "设定".repeat(5_000)
        val system = prefix + "范例".repeat(5_000)
        val noCache = AiRequestBudget.preview(claude, system, "写本章", emptyList(), null, workflow = false)
        val packOnly = AiRequestBudget.preview(claude, system, "写本章", emptyList(), null,
            workflow = false, cachePrefix = prefix.length)
        val packAndSamples = AiRequestBudget.preview(claude, system, "写本章", emptyList(), null,
            workflow = false, cachePrefix = prefix.length, cacheExtra = system.length)

        val price = requireNotNull(AiPricing.price(claude))
        val ordinaryHigh = price.cost(AiUsage(input = noCache.inputTokens, output = AiWire.MAX_TOKENS.toLong()))
        assertEquals(ordinaryHigh.usd, noCache.firstRequestHigh!!.usd, 0.0000001)
        val packOrdinary = price.cost(AiUsage(input = packOnly.inputTokens, output = AiWire.MAX_TOKENS.toLong()))
        val packHigh = requireNotNull(packOnly.firstRequestHigh)
        assertTrue(packHigh.usd > packOrdinary.usd)
        assertTrue(requireNotNull(packAndSamples.firstRequestHigh).usd > packHigh.usd)

        val actualBody = AiToolWire.body(claude, system, JSONArray().put(AiToolWire.user(claude, "写本章")),
            emptyList(), cachePrefix = prefix.length, cacheExtra = system.length)
        assertEquals(AiUsage.estimateTokens(actualBody), packAndSamples.inputTokens)
    }

    /** 逐拍写一拍一次请求：5 拍就是 5 次；写前要拟细纲、拆节拍的另加，拆出几拍不知道时按 3 到 8 拍算。 */
    @Test
    fun beatsAndPrepCountAsRequests() {
        val five = AiRequestBudget.preview(deepSeek, "资料", "写第一拍", emptyList(), 32_768, workflow = false, beats = 5)
        assertEquals(5, five.minimumStages)
        assertEquals(5, five.maximumStages)
        assertEquals("共 5 次请求：逐拍写 5 拍、每拍 1 次", com.xycs.lingyuereader.ui.draftRequestsLine(five))
        val project = AiRequestBudget.preview(deepSeek, "资料", "写第一拍", emptyList(), 32_768, workflow = true, beats = 5)
        assertEquals(6, project.minimumStages)
        assertEquals(8, project.maximumStages)
        val oneClick = AiRequestBudget.preview(deepSeek, "资料", "写本章", emptyList(), 32_768, workflow = false, splitBeats = true, prepRequests = 2)
        assertEquals(2 + 3, oneClick.minimumStages)
        assertEquals(2 + 8, oneClick.maximumStages)
        assertEquals("共 5～10 次请求：写前准备 2 次，逐拍写 3～8 拍、每拍 1 次", com.xycs.lingyuereader.ui.draftRequestsLine(oneClick))
        // 没有节拍：一次写完。
        val whole = AiRequestBudget.preview(deepSeek, "资料", "写本章", emptyList(), 32_768, workflow = false)
        assertEquals("共 1 次请求：写正文 1 次", com.xycs.lingyuereader.ui.draftRequestsLine(whole))
    }
}
