package com.xycs.lingyuereader.ai

import org.json.JSONArray

/** A pre-send estimate for the first request of a chapter draft, not a provider quota. */
data class AiRequestBudgetPreview(
    val model: String,
    val provider: String,
    val inputTokens: Long,
    val outputTokenLimit: Int?,
    val planningTokens: Int,
    val exceedsPlanningBudget: Boolean,
    val firstRequestLow: AiCost?,
    val firstRequestHigh: AiCost?,
    val onDevice: Boolean,
    val workflow: Boolean,
    val tools: Boolean,
    val maxToolRounds: Int,
    /**
     * 写正文发几次请求：没有节拍一次；有节拍逐拍写，一拍一次（每次都重发系统提示和前文）。
     * 还没拆节拍、写之前要先拆的，按拆出 [AiRequestBudget.SPLIT_MIN] 到 [AiRequestBudget.SPLIT_MAX] 拍算。
     */
    val createMin: Int = 1,
    val createMax: Int = 1,
    /** 一键写本章写之前的小请求：缺细纲先拟一份、缺节拍先拆一次，各一次。 */
    val prepRequests: Int = 0,
) {
    /**
     * 整个流程最少、最多发几次请求（查资料的追加请求另算）：写前准备、写正文（逐拍写一拍一次），
     * 项目包流程再加审读（至少一次）、修订和复审（最多再两次）。
     */
    val minimumStages: Int get() = prepRequests + createMin + if (workflow) 1 else 0
    val maximumStages: Int get() = prepRequests + createMax + if (workflow) 3 else 0
}

/** Estimates the exact first-request payload assembled by the caller, including tool schemas. */
object AiRequestBudget {
    fun preview(
        config: AiConfig,
        system: String,
        prompt: String,
        tools: List<AiTool>,
        maxTokens: Int?,
        workflow: Boolean,
        maxToolRounds: Int = 6,
        cachePrefix: Int = 0,
        cacheExtra: Int = 0,
        /** 写正文的采样（温度、思考开关），和真发的请求体一样写进去再估。 */
        prose: ProseSampling? = null,
        /** 细纲卡里有几拍（逐拍写一拍一次请求）；[splitBeats] 为真时写之前才拆，拍数按 3 到 8 估。 */
        beats: Int = 0,
        splitBeats: Boolean = false,
        /** 写之前的拟细纲、拆节拍一共几次。 */
        prepRequests: Int = 0,
    ): AiRequestBudgetPreview {
        // Anthropic's wire builder inserts this default when callers omit maxTokens.
        val effectiveMaxTokens = maxTokens ?: if (config.protocol == AiProtocol.ANTHROPIC) AiWire.MAX_TOKENS else null
        val sent = if (config.onDevice) system + prompt else AiToolWire.body(
            config, system, JSONArray().put(AiToolWire.user(config, prompt)), tools,
            cachePrefix = cachePrefix, cacheExtra = cacheExtra, maxTokens = maxTokens, prose = prose,
        )
        val input = AiUsage.estimateTokens(sent)
        val reserve = effectiveMaxTokens?.toLong() ?: 0L
        val price = if (config.onDevice) null else AiPricing.price(config)
        // Anthropic's first call may write the marked system prefix (and preceding tool schemas)
        // into its cache. A cache write can cost more than an ordinary input token. Approximate
        // that prefix using the same wire format, then show the dearer of write/no-cache cases.
        val cacheCut = if (config.protocol == AiProtocol.ANTHROPIC) {
            maxOf(cachePrefix, cacheExtra).coerceIn(0, system.length)
        } else 0
        val cacheWriteTokens = if (cacheCut > 0) AiUsage.estimateTokens(
            AiToolWire.body(config, system.take(cacheCut), JSONArray(), tools,
                cachePrefix = cacheCut, maxTokens = maxTokens),
        ).coerceAtMost(input) else 0L
        val high = effectiveMaxTokens?.let { limit -> price?.let { p ->
            val ordinary = p.cost(AiUsage(input = input, output = limit.toLong(), requests = 1))
            if (cacheWriteTokens == 0L) ordinary else {
                val written = p.cost(AiUsage(input = input, output = limit.toLong(),
                    cacheWrite = cacheWriteTokens, requests = 1))
                if (written.inCny(1.0) > ordinary.inCny(1.0)) written else ordinary
            }
        } }
        return AiRequestBudgetPreview(
            model = config.model.trim(), provider = config.providerLabel,
            inputTokens = input, outputTokenLimit = effectiveMaxTokens,
            planningTokens = config.contextTokens,
            exceedsPlanningBudget = input + reserve > config.contextTokens,
            // Cache hits are not guaranteed; low is input-only, high allows a first cache write.
            firstRequestLow = price?.cost(AiUsage(input = input, requests = 1)),
            firstRequestHigh = high,
            onDevice = config.onDevice, workflow = workflow, tools = tools.isNotEmpty(), maxToolRounds = maxToolRounds,
            createMin = if (splitBeats) SPLIT_MIN else beats.coerceAtLeast(1),
            createMax = if (splitBeats) SPLIT_MAX else beats.coerceAtLeast(1),
            prepRequests = prepRequests,
        )
    }

    /** AI 拆节拍拆出几拍（见 WritePrompts.beatsSplit）。 */
    const val SPLIT_MIN = 3
    const val SPLIT_MAX = 8
}
