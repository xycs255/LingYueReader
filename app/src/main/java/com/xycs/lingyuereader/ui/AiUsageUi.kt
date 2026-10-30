package com.xycs.lingyuereader.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.ai.AiConfig
import com.xycs.lingyuereader.ai.AiFeature
import com.xycs.lingyuereader.ai.AiPricing
import com.xycs.lingyuereader.ai.UsageBook
import com.xycs.lingyuereader.ai.UsageRow
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.time.LocalDate

/** 用量页按哪段时间看“按功能”“按作品”。 */
private enum class UsagePeriod(val label: String) { MONTH("本月"), QUARTER("近 90 天"), ALL("全部") }

/** 用量页的数是钱还是 token：有单价的请求花了钱就按钱，全都没单价就按 token。 */
private class UsageMeasure(val money: Boolean, val rate: Double) {
    fun value(r: UsageRow): Double = if (money) r.cost.inCny(rate) else (r.usage.input + r.usage.output).toDouble()
    fun label(r: UsageRow): String = if (money) "¥" + AiPricing.amount(r.cost.inCny(rate)) else AiPricing.tokens(r.usage.input + r.usage.output) + " token"
}

/**
 * AI 用量：今天、本月花了多少（约），近 30 天每天的条形图，按功能、按作品分开看。只有数字，不存请求内容。
 * 从作品的“写作 AI”打开时（[workId] 不为空）默认只看这部作品，可以切到全部。
 */
@Composable
fun AiUsageScreen(vm: AppViewModel, workId: String?) {
    val book by vm.aiUsage.collectAsStateWithLifecycle()
    val config by vm.aiConfig.collectAsStateWithLifecycle()
    val works by vm.writing.works.collectAsStateWithLifecycle()
    var onlyWork by rememberSaveable { mutableStateOf(workId != null) }
    var period by rememberSaveable { mutableStateOf(UsagePeriod.MONTH) }
    var editingRate by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val keep: (UsageRow) -> Boolean = { !onlyWork || it.work == workId }
    fun rows(from: LocalDate?) = book.rows(from, today).filter(keep)
    val todayRow = UsageBook.total(book.days[today].orEmpty().filter(keep))
    val monthRow = UsageBook.total(rows(today.withDayOfMonth(1)))
    val allRows = rows(null)
    val all = UsageBook.total(allRows)
    val measure = UsageMeasure(all.cost.cny > 0 || all.cost.usd > 0, config.usdRate)

    SubPageScaffold("AI 用量", onBack = { vm.back() }) { padding ->
        val c = LocalGroupedColors.current
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            item(key = "body") {
                if (workId != null) {
                    Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                        SegmentedControl(listOf(true to (works.firstOrNull { it.id == workId }?.title?.let { "《$it》" } ?: "这部作品"), false to "全部作品"), onlyWork) { onlyWork = it }
                    }
                }
                val notes = buildList {
                    if (all.cost.usd > 0) add("美元按 1 美元 = ${AiPricing.plain(config.usdRate)} 元折算。")
                    if (all.cost.unpriced > 0) add("有 ${all.cost.unpriced} 次请求没有单价，没算钱（在“我的 → AI 服务 → 价格”里填）。")
                    if (all.usage.estimated) add("有 ${all.usage.estimatedRequests} 次服务商没返回用量，按字数估算。")
                }
                GroupedSection(
                    footer = "都是约数：按服务商返回的 token 数和单价（参考价或你填的）算，实际以服务商账单为准。" + notes.joinToString(""),
                ) {
                    row {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("今天", fontSize = TypeScale.Caption, color = c.secondary)
                            val shown = animateFromZero(measure.value(todayRow).toFloat())
                            Text(
                                if (measure.money) "约 ¥" + AiPricing.amount(shown.toDouble()) else AiPricing.tokens(shown.toLong()) + " token",
                                color = c.label,
                                fontSize = TypeScale.Display,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Text(summaryLine(todayRow), fontSize = TypeScale.Caption, color = c.secondary, modifier = Modifier.padding(top = 2.dp))
                            Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                            Row(Modifier.fillMaxWidth()) {
                                UsageFigure("本月", measure.label(monthRow), Modifier.weight(1f))
                                UsageFigure("本月请求", "${monthRow.usage.requests} 次", Modifier.weight(1f))
                                UsageFigure("缓存命中", if (monthRow.usage.input > 0) "${Math.round(monthRow.usage.hitRate * 100)}%" else "—", Modifier.weight(1f))
                            }
                        }
                    }
                }
                GroupedSection(header = "近 30 天") {
                    row {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            val values = (29 downTo 0).map { back ->
                                val d = today.minusDays(back.toLong())
                                measure.value(UsageBook.total(book.days[d].orEmpty().filter(keep)))
                            }
                            DailyBars(values, measure.money)
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                Text("30 天前", fontSize = TypeScale.Footnote, color = c.secondary)
                                Spacer(Modifier.weight(1f))
                                Text("今天", fontSize = TypeScale.Footnote, color = c.secondary)
                            }
                        }
                    }
                }
                Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    SegmentedControl(UsagePeriod.entries.map { it to it.label }, period) { period = it }
                }
                val from = when (period) {
                    UsagePeriod.MONTH -> today.withDayOfMonth(1)
                    UsagePeriod.QUARTER -> today.minusDays((UsageBook.KEEP_DAYS - 1).toLong())
                    UsagePeriod.ALL -> null
                }
                val periodRows = rows(from)
                val byFeature = periodRows.groupBy { it.feature }.map { (k, v) -> AiFeature.of(k).label to UsageBook.total(v) }
                UsageBarsSection("按功能", byFeature, measure, "${period.label}还没有用过 AI。")
                if (!onlyWork) {
                    val byWork = periodRows.groupBy { it.work }.map { (id, v) ->
                        (if (id.isEmpty()) "阅读" else works.firstOrNull { it.id == id }?.title?.let { "《$it》" } ?: "已删除的作品") to UsageBook.total(v)
                    }
                    UsageBarsSection("按作品", byWork, measure, "${period.label}还没有用过 AI。")
                }
                GroupedSection(
                    header = "提醒与换算",
                    footer = "今天的花费超过提醒线时提示一次，不会自动停。用量只存在这台手机上（只有数字，不存发了什么），不进灵阅的备份；" +
                        "最近 ${UsageBook.KEEP_DAYS} 天按天记，更早的并成按月的汇总。向量检索、AI 朗读和本机模型不计。",
                ) {
                    row {
                        PickerRow("每天花费提醒", DAILY_ALERTS.map { it to if (it == 0.0) "不提醒" else "超过 ¥${AiPricing.plain(it)}" }, config.dailyAlert.takeIf { it in DAILY_ALERTS } ?: 0.0) { v ->
                            vm.saveAiConfig(config.copy(dailyAlert = v))
                        }
                    }
                    row { NavRow("美元汇率", value = "1 美元 = ${AiPricing.plain(config.usdRate)} 元") { editingRate = true } }
                    if (!book.days.isEmpty() || !book.months.isEmpty()) row { ActionRow("清除用量记录", destructive = true) { clearing = true } }
                }
            }
        }
        if (editingRate) RateDialog(config, onDismiss = { editingRate = false }) { rate ->
            editingRate = false
            vm.saveAiConfig(config.copy(usdRate = rate))
        }
        if (clearing) {
            GlassDialog(show = true, title = "清除用量记录", summary = "所有日子、所有作品的用量数字都会清掉，不能恢复；不影响 AI 设置和稿子。", onDismissRequest = { clearing = false }) {
                DialogButtons(onDismiss = { clearing = false }, onConfirm = {
                    clearing = false
                    vm.clearAiUsage()
                }, confirmText = "清除", destructive = true)
            }
        }
    }
}

/** 每天花费提醒的几档（人民币）。 */
private val DAILY_ALERTS = listOf(0.0, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0)

private fun summaryLine(r: UsageRow): String =
    if (r.usage.isEmpty) "今天还没有用过 AI"
    else "${r.usage.requests} 次请求 · 输入 ${AiPricing.tokens(r.usage.input)} · 输出 ${AiPricing.tokens(r.usage.output)} token" +
        if (r.usage.cached > 0) " · 缓存命中 ${Math.round(r.usage.hitRate * 100)}%" else ""

@Composable
private fun UsageFigure(label: String, value: String, modifier: Modifier = Modifier) {
    val c = LocalGroupedColors.current
    Column(modifier) {
        Text(value, color = c.label, fontSize = TypeScale.Headline, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = TypeScale.Caption, color = c.secondary, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 一组带条的行（按功能、按作品）：名字、右边的数、下面一根从 0 长出来的条（照统计页“各本书”）。 */
@Composable
private fun UsageBarsSection(header: String, items: List<Pair<String, UsageRow>>, measure: UsageMeasure, empty: String) {
    val c = LocalGroupedColors.current
    val sorted = items.sortedByDescending { measure.value(it.second) }
    val top = sorted.firstOrNull()?.let { measure.value(it.second) }?.takeIf { it > 0 } ?: 1.0
    GroupedSection(header = header) {
        if (sorted.isEmpty()) row { EmptyRow(empty) }
        sorted.forEach { (name, r) ->
            row {
                Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = c.label, fontSize = TypeScale.Body, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(measure.label(r) + " · ${r.usage.requests} 次", color = c.secondary, fontSize = TypeScale.Caption, modifier = Modifier.padding(start = 12.dp))
                    }
                    val grown = animateFromZero((measure.value(r) / top).toFloat())
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(Radius.Full).background(c.fill)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(grown.coerceIn(0f, 1f)).clip(Radius.Full).background(c.tint))
                    }
                }
            }
        }
    }
}

/** 近 30 天每天一根竖条，最右是今天；打开时从左往右一根根长起来（同统计页热力图的节奏）。 */
@Composable
private fun DailyBars(values: List<Double>, money: Boolean) {
    val c = LocalGroupedColors.current
    val reveal = remember { Animatable(if (Effects.noMotion) 1f else 0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, Motion.standard(Motion.COUNT_UP)) }
    val top = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val used = values.count { it > 0 }
    val total = values.sum()
    val tint = c.tint
    val fill = c.fill
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics {
                contentDescription = "近 30 天有 $used 天用了 AI，共" + if (money) "约 ${AiPricing.amount(total)} 元" else "约 ${AiPricing.tokens(total.toLong())} token"
            },
    ) {
        val n = values.size
        val cell = size.width / n
        val gap = cell * 0.28f
        val front = reveal.value * (n + 6)
        values.forEachIndexed { i, v ->
            val t = (front - i).coerceIn(0f, 6f) / 6f
            if (t <= 0f) return@forEachIndexed
            val h = if (v > 0) (size.height * (v / top).toFloat()).coerceAtLeast(3f) * t else 3f
            drawRoundRect(
                if (v > 0) tint else fill,
                topLeft = Offset(i * cell + gap / 2, size.height - h),
                size = Size(cell - gap, h),
                cornerRadius = CornerRadius((cell - gap) / 3),
                alpha = if (i == n - 1 || v <= 0) t else 0.55f * t + 0.2f,
            )
        }
    }
}

/** 改美元汇率：填一个数（例如 7.1）。 */
@Composable
private fun RateDialog(config: AiConfig, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf(AiPricing.plain(config.usdRate)) }
    val rate = text.trim().toDoubleOrNull()?.takeIf { it > 0 && it < 100 }
    GlassDialog(show = true, title = "美元汇率", summary = "Claude 按美元计价；用量页把美元折成人民币加总时用。只是换算，不影响扣费。", onDismissRequest = onDismiss) {
        GroupedTextField(
            value = text,
            onValueChange = { text = it },
            label = "1 美元 = 多少元",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        DialogButtons(onDismiss = onDismiss, onConfirm = { rate?.let(onSave) }, confirmText = "保存", confirmEnabled = rate != null)
    }
}

/** 单价写成一句：“输入 ¥2 · 命中 ¥0.04 · 输出 ¥8 / 百万 token”。 */
internal fun priceLine(p: com.xycs.lingyuereader.ai.AiPrice): String {
    val sign = if (p.usd) "$" else "¥"
    return "输入 $sign${AiPricing.plain(p.input)} · 命中 $sign${AiPricing.plain(p.cached)} · 输出 $sign${AiPricing.plain(p.output)}"
}

/**
 * AI 设置里的“价格与用量”一节：现在按什么单价估（参考价写明出处和日期、“以服务商为准”）、自己填单价、看用量。
 */
@Composable
internal fun PriceSection(vm: AppViewModel, config: AiConfig) {
    val book by vm.aiUsage.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    val choice = AiPricing.choose(config)
    val reference = AiPricing.reference(config)
    val model = config.model.trim()
    val custom = choice?.kind == AiPricing.PriceKind.MODEL || choice?.kind == AiPricing.PriceKind.PROVIDER
    val today = book.day(LocalDate.now())
    GroupedSection(
        header = "价格与用量",
        footer = when {
            config.onDevice -> "本机模型不联网、不花钱，不计用量。"
            choice?.kind == AiPricing.PriceKind.MODEL -> "这是 $model 的价，按你填的单价估（每百万 token）。换了模型要另填。"
            choice?.kind == AiPricing.PriceKind.PROVIDER -> "这是以前按服务商填的通用价，未指定模型，现在按它估 $model（每百万 token）。" +
                "不是这个模型的价时，给 $model 单独填一个。"
            reference != null -> "这是 $model 的参考价，以服务商为准：${reference.source}（${reference.date}），每百万 token" +
                (if (reference.note.isNotEmpty()) "，${reference.note}" else "") + "。和你的账户价格不一样时，自己填单价。"
            else -> "$model 没有内置参考价（价格常变，查不到出处的不内置），也没填单价：没估钱，只记 token 数。填上单价才估钱。"
        },
    ) {
        if (!config.onDevice) {
            val label = when (choice?.kind) {
                AiPricing.PriceKind.MODEL -> "单价（你填的）"
                AiPricing.PriceKind.PROVIDER -> "单价（你填的，未指定模型）"
                AiPricing.PriceKind.REFERENCE -> "单价（参考价）"
                null -> "单价"
            }
            row { ValueRow(label, choice?.price?.let(::priceLine) ?: "没估钱") }
            row { NavRow("自己填单价", value = if (custom) "已填" else null) { editing = true } }
        }
        row {
            NavRow(
                "AI 用量",
                value = if (today.usage.isEmpty) "今天还没用" else "今天 " + (AiPricing.money(today.cost)?.let { "约 $it" } ?: "${AiPricing.tokens(today.usage.input + today.usage.output)} token"),
            ) { vm.open(com.xycs.lingyuereader.Screen.AiUsage(null)) }
        }
    }
    if (editing) PriceDialog(config, onDismiss = { editing = false }) { price ->
        editing = false
        // 填的价记在当前模型名下；“清除”清掉现在起作用的那份（给这个模型填的，没有就是旧的通用价）。
        val key = AiPricing.priceKey(config)
        vm.saveAiConfig(
            config.copy(
                prices = when {
                    price != null -> config.prices + (key to price)
                    config.prices.containsKey(key) -> config.prices - key
                    else -> config.prices - config.preset
                },
            ),
        )
    }
}

/** 填单价：每百万 token 的输入、缓存命中、输出价，人民币或美元；“用参考价”清掉自己填的。 */
@Composable
private fun PriceDialog(config: AiConfig, onDismiss: () -> Unit, onSave: (com.xycs.lingyuereader.ai.AiPrice?) -> Unit) {
    val start = AiPricing.price(config)
    var input by remember { mutableStateOf(start?.input?.let(AiPricing::plain).orEmpty()) }
    var cached by remember { mutableStateOf(start?.cached?.let(AiPricing::plain).orEmpty()) }
    var output by remember { mutableStateOf(start?.output?.let(AiPricing::plain).orEmpty()) }
    var usd by remember { mutableStateOf(start?.usd ?: (config.protocol == com.xycs.lingyuereader.ai.AiProtocol.ANTHROPIC)) }
    fun num(s: String) = s.trim().toDoubleOrNull()?.takeIf { it >= 0 }
    val ok = num(input) != null && num(output) != null && (cached.isBlank() || num(cached) != null)
    GlassDialog(show = true, title = "单价", summary = "这是 ${config.model.trim()} 的价（${config.providerLabel}），只用于这个模型，换了模型要另填。每百万 token 多少钱，照服务商价格页填；缓存命中价不知道可以空着，按输入价算。", onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SegmentedControl(listOf(false to "人民币 ¥", true to "美元 $"), usd) { usd = it }
            listOf(Triple("输入", input) { v: String -> input = v }, Triple("缓存命中", cached) { v: String -> cached = v }, Triple("输出", output) { v: String -> output = v }).forEach { (label, value, set) ->
                GroupedTextField(
                    value = value,
                    onValueChange = set,
                    label = label,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (AiPricing.choose(config)?.kind.let { it == AiPricing.PriceKind.MODEL || it == AiPricing.PriceKind.PROVIDER }) {
                Box(Modifier.clip(RoundedCornerShape(Radius.Medium))) {
                    ActionRow(if (AiPricing.reference(config) != null) "不用自己填的，用参考价" else "清除填的单价", destructive = true) { onSave(null) }
                }
            }
        }
        DialogButtons(
            onDismiss = onDismiss,
            onConfirm = {
                val i = num(input) ?: return@DialogButtons
                onSave(com.xycs.lingyuereader.ai.AiPrice(i, num(cached) ?: i, num(output) ?: return@DialogButtons, usd))
            },
            confirmText = "保存",
            confirmEnabled = ok,
        )
    }
}
