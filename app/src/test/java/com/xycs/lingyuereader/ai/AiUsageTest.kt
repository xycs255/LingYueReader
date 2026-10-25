package com.xycs.lingyuereader.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.YearMonth
import java.util.Collections

/** 花费透明：解析各家的 usage（流式、非流式、工具多轮），拿不到时估算；估钱；按天、作品、功能记账和 90 天滚动汇总。 */
class AiUsageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val bodies: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    /** 依次回的几个回包：Content-Type 和内容。 */
    private val replies = ArrayDeque<Pair<String, String>>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            bodies += JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val (type, body) = synchronized(replies) { replies.removeFirst() }
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}"
    private fun openAi(price: AiPrice? = AiPrice(2.0, 0.2, 8.0)) = AiConfig(enabled = true, preset = AiPresets.CUSTOM, baseUrl = "$base/v1", model = "m", prices = price?.let { mapOf(AiPresets.CUSTOM to it) }.orEmpty()).withKey("sk")
    private fun anthropic() = openAi(AiPrice(4.0, 0.2, 20.0, usd = true, cacheWrite = 5.0)).copy(protocol = AiProtocol.ANTHROPIC, model = "claude-opus-5-5")

    private fun sse(vararg lines: String) = "text/event-stream" to lines.joinToString("\n\n") { "data: $it" } + "\n\n"

    @Test
    fun openAiStreamWithIncludeUsageAndDeepSeekCacheFields() = runBlocking {
        replies += sse(
            """{"choices":[{"delta":{"content":"林月"}}]}""",
            """{"choices":[{"delta":{"content":"到了渡口。"},"finish_reason":"stop"}],"usage":null}""",
            // include_usage 的最后一块：choices 是空的；DeepSeek 另给命中、未命中。
            """{"choices":[],"usage":{"prompt_tokens":1000,"completion_tokens":200,"prompt_cache_hit_tokens":900,"prompt_cache_miss_tokens":100}}""",
            "[DONE]",
        )
        val events = mutableListOf<AiUsageEvent>()
        val client = AiClient().apply { onUsage = { events += it } }
        val meter = AiMeter(AiFeature.INLINE, "w1")
        val answer = withContext(meter) { client.stream(openAi(), "系统", listOf(AiMessage("user", "写"))) }
        assertEquals("林月到了渡口。", answer)
        assertEquals(AiUsage(1000, 900, 200, requests = 1), meter.usage)
        assertFalse(meter.usage.estimated)
        // 100 × 2 + 900 × 0.2 + 200 × 8，每百万 token。
        assertEquals((100 * 2.0 + 900 * 0.2 + 200 * 8.0) / 1_000_000, meter.cost.cny, 1e-12)
        assertEquals(1, events.size)
        assertEquals("w1", events.single().meter?.workId)
        assertEquals(0.9, meter.usage.hitRate, 1e-9)
    }

    @Test
    fun nonStreamJsonAndKimiStyleUsage() = runBlocking {
        replies += "application/json" to """{"choices":[{"message":{"content":"好"},"finish_reason":"stop"}],"usage":{"prompt_tokens":50,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":32}}}"""
        val meter = AiMeter(AiFeature.TEST)
        withContext(meter) { AiClient().stream(openAi(), "", listOf(AiMessage("user", "好"))) }
        assertEquals(AiUsage(50, 32, 2, requests = 1), meter.usage)
        // Kimi 把用量写在 choices[0].usage 里，命中叫 cached_tokens。
        val d = AiWire.delta(AiProtocol.OPENAI, """{"choices":[{"delta":{},"finish_reason":"stop","usage":{"prompt_tokens":80,"completion_tokens":5,"cached_tokens":64}}]}""")
        assertEquals(AiUsage(80, 64, 5, requests = 1), d.usage)
    }

    @Test
    fun anthropicStreamMergesMessageStartAndDelta() = runBlocking {
        replies += sse(
            """{"type":"message_start","message":{"usage":{"input_tokens":20,"cache_read_input_tokens":3000,"cache_creation_input_tokens":500,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"沈默点头。"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}""",
            """{"type":"message_stop"}""",
        )
        val meter = AiMeter(AiFeature.EDIT, "w")
        assertEquals("沈默点头。", withContext(meter) { AiClient().stream(anthropic(), "系统", listOf(AiMessage("user", "改"))) })
        assertEquals(AiUsage(3520, 3000, 42, 500, requests = 1), meter.usage)
        // 美元：新输入 20 × 4、命中 3000 × 0.2、写入缓存 500 × 5、输出 42 × 20。
        assertEquals((20 * 4.0 + 3000 * 0.2 + 500 * 5.0 + 42 * 20.0) / 1_000_000, meter.cost.usd, 1e-12)
        assertEquals(0.0, meter.cost.cny, 0.0)
    }

    @Test
    fun toolRoundsAddUp() = runBlocking {
        val tool = AiTool("read", "读一章", JSONObject().put("type", "object"))
        replies += sse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":5000,"completion_tokens":30,"prompt_cache_hit_tokens":4800}}""",
            "[DONE]",
        )
        replies += sse(
            """{"choices":[{"delta":{"content":"杜娘关了门。"},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":5200,"completion_tokens":60,"prompt_cache_hit_tokens":5000}}""",
            "[DONE]",
        )
        val events = mutableListOf<AiUsageEvent>()
        val client = AiClient().apply { onUsage = { events += it } }
        val meter = AiMeter(AiFeature.SYNC, "w")
        val answer = withContext(meter) { client.converse(openAi(), "设定包", "整理", listOf(tool), run = { "第一章正文" }) }
        assertEquals("杜娘关了门。", answer)
        assertEquals(AiUsage(10_200, 9_800, 90, requests = 2), meter.usage)
        assertEquals(2, events.size)
    }

    @Test
    fun anthropicToolCollectorKeepsUsage() {
        val c = AiToolWire.Collector(AiProtocol.ANTHROPIC) { }
        c.feed("""{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_read_input_tokens":90,"output_tokens":1}}}""")
        c.feed("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
        c.feed("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}""")
        c.feed("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""")
        assertEquals(AiUsage(100, 90, 7, requests = 1), c.finish().usage)
    }

    @Test
    fun noUsageMeansEstimateAndUnpricedWhenNoPrice() = runBlocking {
        replies += sse("""{"choices":[{"delta":{"content":"老周在渡口等了一夜。"},"finish_reason":"stop"}]}""", "[DONE]")
        val meter = AiMeter(AiFeature.ASK)
        withContext(meter) { AiClient().stream(openAi(price = null), "系统提示", listOf(AiMessage("user", "老周在哪"))) }
        assertTrue(meter.usage.estimated)
        assertTrue(meter.usage.input > 0 && meter.usage.output > 0)
        // 没有单价：不估钱，只记一次“没估钱”。
        assertEquals(1, meter.cost.unpriced)
        assertNull(AiPricing.money(meter.cost))
        assertTrue(AiPricing.brief(meter.usage, meter.cost)!!.contains("估算"))
        // 汉字约 0.6 token。
        assertEquals(6, AiUsage.estimateTokens("渡口渡口渡口渡口渡口"))
    }

    @Test
    fun streamOptionsOnlyForKnownProviders() {
        val deepseek = AiConfig(enabled = true).withPreset(AiPresets.of("deepseek")).withKey("k")
        assertTrue(JSONObject(AiWire.body(deepseek, "", listOf(AiMessage("user", "问")))).getJSONObject("stream_options").getBoolean("include_usage"))
        assertTrue(JSONObject(AiToolWire.body(deepseek, "", org.json.JSONArray(), emptyList())).has("stream_options"))
        val qwen = deepseek.withPreset(AiPresets.of("qwen"))
        assertTrue(JSONObject(AiWire.body(qwen, "", listOf(AiMessage("user", "问")))).has("stream_options"))
        // 不确定支持的（Kimi、智谱、局域网、自定义）不加，免得 400。
        listOf("kimi", "glm", AiPresets.LOCAL).forEach { id ->
            assertFalse(id, JSONObject(AiWire.body(deepseek.withPreset(AiPresets.of(id)), "", listOf(AiMessage("user", "问")))).has("stream_options"))
        }
        // 非流式请求不加。
        assertFalse(JSONObject(AiWire.body(deepseek, "", listOf(AiMessage("user", "问")), stream = false)).has("stream_options"))
    }

    @Test
    fun pricing() {
        val deepseek = AiConfig(enabled = true).withPreset(AiPresets.of("deepseek"))
        // deepseek-chat 价格页上没有：不估。
        assertNull(AiPricing.reference(deepseek))
        val flash = AiPricing.reference(deepseek.copy(model = "deepseek-flash"))!!
        assertEquals("2026-10-25", flash.date)
        val u = AiUsage(1_000_000, 900_000, 100_000, requests = 1)
        // 10 万未命中 × 2 + 90 万命中 × 0.04 + 10 万输出 × 8 = 0.2 + 0.036 + 0.8。
        assertEquals(1.036, flash.price.cost(u).cny, 1e-9)
        val claude = AiConfig(enabled = true).withPreset(AiPresets.of("claude"))
        val ref = AiPricing.reference(claude)!!
        assertTrue(ref.price.usd)
        assertEquals(4.0, ref.price.input, 0.0)
        assertEquals(5.0, ref.price.cacheWrite, 0.0)
        // 作者填的优先。
        val mine = AiPrice(1.0, 0.1, 2.0)
        assertEquals(mine, AiPricing.price(claude.copy(prices = mapOf("claude" to mine))))
        // 通义千问、Kimi、自定义：没有内置价。
        assertNull(AiPricing.reference(deepseek.withPreset(AiPresets.of("qwen"))))
        assertNull(AiPricing.reference(deepseek.withPreset(AiPresets.of(AiPresets.CUSTOM))))
        // 币种：折成人民币。
        assertEquals(7.1 + 1.0, AiCost(cny = 1.0, usd = 1.0).inCny(7.1), 1e-9)
        assertEquals("¥0.003", AiPricing.money(AiCost(cny = 0.003)))
        assertEquals("¥1.20 + $0.05", AiPricing.money(AiCost(cny = 1.2, usd = 0.05)))
        assertEquals("约 ¥0.003 · 缓存命中 92%", AiPricing.brief(AiUsage(1000, 920, 10, requests = 1), AiCost(cny = 0.003)))
        assertEquals("1.2 万", AiPricing.tokens(12_000))
    }

    @Test
    fun bookAddsByDayWorkFeatureAndRollsUpAfter90Days() {
        val day = LocalDate.of(2026, 10, 25)
        var b = UsageBook()
        b = b.add(day, "w1", AiFeature.INLINE, AiUsage(100, 50, 10, requests = 1), AiCost(cny = 0.01))
        b = b.add(day, "w1", AiFeature.INLINE, AiUsage(200, 100, 20, requests = 1), AiCost(cny = 0.02))
        b = b.add(day, "w2", AiFeature.EDIT, AiUsage(10, 0, 1, requests = 1), AiCost(usd = 0.001))
        b = b.add(day, "", AiFeature.TRANSLATE, AiUsage(5, 0, 5, requests = 1, estimatedRequests = 1), AiCost(unpriced = 1))
        assertEquals(3, b.days[day]!!.size)
        val inline = b.days[day]!!.first { it.feature == "INLINE" }
        assertEquals(AiUsage(300, 150, 30, requests = 2), inline.usage)
        assertEquals(0.03, inline.cost.cny, 1e-12)
        assertEquals(4, b.day(day).usage.requests)

        // 早的明细：100 天前、95 天前（同一个月）并成按月的，近的留着。
        val old1 = day.minusDays(100)
        val old2 = day.minusDays(99)
        b = b.add(old1, "w1", AiFeature.INLINE, AiUsage(1, 0, 1, requests = 1), AiCost(cny = 1.0))
            .add(old2, "w1", AiFeature.INLINE, AiUsage(1, 0, 1, requests = 1), AiCost(cny = 2.0))
            .add(day.minusDays(89), "w1", AiFeature.EDIT, AiUsage(1, 0, 1, requests = 1), AiCost(cny = 4.0))
            .compact(day)
        assertFalse(old1 in b.days)
        assertFalse(old2 in b.days)
        assertTrue(day.minusDays(89) in b.days)
        val month = b.months[YearMonth.from(old1)]!!.single()
        assertEquals(3.0, month.cost.cny, 1e-12)
        assertEquals(2, month.usage.requests)
        // 全部：明细加按月汇总。
        assertEquals(1.0 + 2.0 + 4.0 + 0.03, UsageBook.total(b.rows(null, day)).cost.cny, 1e-9)
        // 本月：只看这个月的。
        assertEquals(0.03, UsageBook.total(b.rows(day.withDayOfMonth(1), day).filter { it.work == "w1" }).cost.cny, 1e-12)
        // 存下来再读出来一样。
        assertEquals(b, UsageBook.fromJson(UsageBook.toJson(b)))
    }

    @Test
    fun storePersistsAndSurvivesDamage() {
        val file = tmp.newFile("ai-usage.json")
        var today = LocalDate.of(2026, 10, 25)
        val store = UsageStore(file) { today }
        store.add("w1", AiFeature.DRAFT, AiUsage(10, 0, 10, requests = 1), AiCost(cny = 0.5))
        today = today.plusDays(1)
        store.add("w1", AiFeature.DRAFT, AiUsage(10, 0, 10, requests = 1), AiCost(cny = 0.5))
        val again = UsageStore(file) { today }.load()
        assertEquals(2, again.days.size)
        // 只有数字：文件里没有请求内容这类字段。
        assertFalse(file.readText().contains("text"))
        file.writeText("坏了")
        assertTrue(UsageStore(file) { today }.load().days.isEmpty())
    }
}
