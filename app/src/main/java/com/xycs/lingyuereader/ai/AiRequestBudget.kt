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
) {
    /** The create/review/revise/re-review stages; tool rounds may add requests inside each stage. */
    val minimumStages: Int get() = if (workflow) 2 else 1
    val maximumStages: Int get() = if (workflow) 4 else 1
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
    ): AiRequestBudgetPreview {
        // Anthropic's wire builder inserts this default when callers omit maxTokens.
        val effectiveMaxTokens = maxTokens ?: if (config.protocol == AiProtocol.ANTHROPIC) AiWire.MAX_TOKENS else null
        val sent = if (config.onDevice) system + prompt else AiToolWire.body(
            config, system, JSONArray().put(AiToolWire.user(config, prompt)), tools,
            cachePrefix = cachePrefix, cacheExtra = cacheExtra, maxTokens = maxTokens,
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
        )
    }
}
