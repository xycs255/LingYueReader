package com.xycs.lingyuereader.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.data.ReplaceRule
import com.xycs.lingyuereader.data.TextRules
import com.xycs.lingyuereader.data.RegexTooSlowException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 常用的净化规则，点一下填进表单。 */
internal val REPLACE_TEMPLATES = listOf(
    "网址" to ReplaceRule("""(https?://|www\.)[\w./?=&%#-]+""", "", regex = true),
    "整行“本章未完…”" to ReplaceRule("""^.*本章未完.*$""", "", regex = true),
    "整行求票求收藏" to ReplaceRule("""^.*(求月票|求推荐票|求收藏|求订阅).*$""", "", regex = true),
    "多余空格" to ReplaceRule("""[ \t]{2,}""", " ", regex = true),
)

/**
 * 一本 TXT/Markdown 书的净化与分章规则。改动实时预览：分成几章、每条规则匹配几处；
 * 保存后按新规则重新解析。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookRulesScreen(vm: AppViewModel, bookId: String) {
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val entry = shelf.firstOrNull { it.id == bookId }
    // 没保存的改动旋转屏幕后还在：规则从磁盘读过一次就不再读，免得盖掉。全文太大，不存，重新读。
    var rulesRead by rememberSaveable { mutableStateOf(false) }
    var toc by rememberSaveable { mutableStateOf("") }
    var rules by rememberSaveable(stateSaver = RulesSaver) { mutableStateOf(listOf<ReplaceRule>()) }
    var source by remember { mutableStateOf<String?>(null) }
    var chapters by remember { mutableStateOf<List<String>?>(null) }
    // 试分章时正则太慢、被放弃了（或上一个太慢的正则还在跑）：这时不让保存。
    var tooSlow by remember { mutableStateOf<String?>(null) }
    // 写法像会灾难性回溯、作者点了“仍然试一下”的正则。
    var allowed by remember { mutableStateOf(setOf<String>()) }
    var pattern by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var regex by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(entry?.id) {
        val e = entry ?: return@LaunchedEffect
        if (!rulesRead) {
            val r = vm.bookRules(e.id)
            toc = r.toc
            rules = r.replace
            rulesRead = true
        }
        source = vm.bookSourceText(e)
    }
    val loaded = rulesRead && source != null
    val current = TextRules(rules, toc.trim())
    // 分章正则的写法像会灾难性回溯（如 (\w+\s?)+）：先不试分章，免得丢下一条停不下来的线程；作者点“仍然试一下”再试。
    val tocRisk = current.toc.takeIf { it.isNotBlank() && TextRules.error(it) == null }?.let(TextRules::risky)
    val tocHeld = tocRisk != null && current.toc !in allowed
    // 停手半秒再试分章：整本重新解析，不能每敲一个字就跑一遍。
    LaunchedEffect(current, loaded, tocHeld) {
        val e = entry ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        chapters = null
        tooSlow = null
        if (tocHeld) return@LaunchedEffect
        delay(500)
        chapters = try {
            vm.previewChapters(e, current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (slow: RegexTooSlowException) {
            tooSlow = slowNote(slow) ?: "正则太慢，在这本书上跑不完，请改写分章或净化规则。"
            null
        } catch (failed: Exception) {
            null
        }
    }
    val tocError = toc.takeIf { it.isNotBlank() }?.let(TextRules::error)

    SubPageScaffold(
        title = "净化与分章",
        onBack = { vm.back() },
    ) { padding ->
        val c = LocalGroupedColors.current
        if (entry == null || !loaded) {
            LoadingState("正在读取规则和原文……", Modifier.padding(top = padding.calculateTopPadding()))
            return@SubPageScaffold
        }
        val mono = androidx.compose.ui.text.TextStyle(color = c.label, fontSize = TypeScale.Body, fontFamily = FontFamily.Monospace)
        val fieldColors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent, labelColor = c.secondary, borderColor = Color.Transparent)
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            item {
                GroupedSection(header = "分章格式", footer = "留空时自动识别“第X章”“序章”“番外”等。分不对时选一个格式，或自己写正则（匹配到的整行当作章名）。") {
                    row { CheckRow("自动识别", toc.isBlank()) { toc = "" } }
                    TextRules.TOC_PRESETS.forEach { p -> row { CheckRow(p.label, toc == p.pattern) { toc = p.pattern } } }
                    row {
                        TextField(
                            value = toc,
                            onValueChange = { toc = it },
                            label = "分章正则",
                            singleLine = true,
                            colors = fieldColors,
                            textStyle = mono,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (tocError != null) {
                    Text("正则有误：$tocError", color = c.destructive, fontSize = TypeScale.Caption, modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 20.dp))
                }
                val list = chapters
                // 组名就叫“预览”；试分的结果写在卡片第一行，不拿状态当组名。
                GroupedSection(header = "预览") {
                    row {
                        if (tocHeld) {
                            Text(
                                "分章正则$tocRisk。先没试分章，请改写；确定没问题可以仍然试一下。",
                                color = c.destructive,
                                fontSize = TypeScale.Body,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        } else if (list == null && tooSlow == null) {
                            LoadingRow("正在试分章……")
                        } else {
                            val count = list?.size ?: 0
                            Text(
                                tooSlow ?: when {
                                    count <= 1 -> "按这个规则只分出 $count 章，可能没匹配到章名。"
                                    else -> "将分为 $count 章"
                                },
                                color = if (tooSlow != null || count <= 1) c.destructive else c.secondary,
                                fontSize = TypeScale.Caption,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                    if (tocHeld) row { ActionRow("仍然试一下") { allowed = allowed + current.toc } }
                    if (list != null && list.size > 1) {
                        val shown = list.take(12) + if (list.size > 12) listOf("……", list.last()) else emptyList()
                        shown.forEach { title -> row { Text(title, color = if (title == "……") c.secondary else c.label, fontSize = TypeScale.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) } }
                    }
                }
                GroupedSection(header = "净化规则") {
                    if (rules.isEmpty()) {
                        row { EmptyRow("还没有规则。在下面添加：查找的文字会被替换，替换为留空就是删除。") }
                    }
                    rules.forEachIndexed { i, r ->
                        row {
                            val preview = rememberPreview(r, source.orEmpty())
                            RuleRow(
                                rule = r,
                                matches = preview?.fold({ "匹配 ${it.first} 处" }, { (it as? RegexTooSlowException)?.let(::slowLabel) ?: "正则太慢" }) ?: "统计中……",
                                samples = preview?.getOrNull()?.second.orEmpty(),
                                onToggle = { on -> rules = rules.toMutableList().also { it[i] = r.copy(enabled = on) } },
                                onDelete = { rules = rules.filterIndexed { j, _ -> j != i } },
                            )
                        }
                    }
                }
                val draft = ReplaceRule(pattern, replacement, regex)
                val error = if (regex && pattern.isNotEmpty()) TextRules.error(pattern) else null
                // 写法像会灾难性回溯的正则先不统计（统计也要整本跑一遍），作者点“仍然试一下”再统计。
                val draftRisk = if (regex && pattern.isNotEmpty() && error == null) TextRules.risky(pattern) else null
                val draftHeld = draftRisk != null && pattern !in allowed
                val draftPreview = rememberPreview(draft, source.orEmpty(), run = !draftHeld)
                val draftSlow = draftPreview?.isFailure == true
                GroupedSection(
                    header = "添加规则",
                    footer = when {
                        error != null -> "正则有误：$error"
                        draftHeld -> "这个正则$draftRisk。先没统计，请改写；确定没问题可以仍然试一下。"
                        draftSlow && (draftPreview?.exceptionOrNull() as? RegexTooSlowException)?.let(::slowNote) != null ->
                            slowNote(draftPreview?.exceptionOrNull() as RegexTooSlowException)
                        draftSlow -> "正则太慢，在这本书上跑不完。请改写，避免 (\\w+\\s?)+ 这类套在一起的重复。"
                        pattern.isNotEmpty() -> (draftPreview?.getOrNull()?.let { "将匹配 ${it.first} 处" } ?: "统计中……") +
                            draftPreview?.getOrNull()?.second.orEmpty().joinToString("") { "\n$it" }
                        else -> null
                    },
                ) {
                    row {
                        val templates = listOf(ReplaceRule("", "") to "不用模板") + REPLACE_TEMPLATES.map { (label, t) -> t to label }
                        PickerRow("常用", templates, templates.firstOrNull { it.first == draft }?.first ?: ReplaceRule("", "")) { t ->
                            pattern = t.pattern
                            replacement = t.replacement
                            regex = t.regex
                        }
                    }
                    row { TextField(value = pattern, onValueChange = { pattern = it }, label = "查找", singleLine = true, colors = fieldColors, textStyle = mono, modifier = Modifier.fillMaxWidth()) }
                    row { TextField(value = replacement, onValueChange = { replacement = it }, label = "替换为（留空即删除）", singleLine = true, colors = fieldColors, textStyle = mono.copy(fontFamily = FontFamily.Default), modifier = Modifier.fillMaxWidth()) }
                    row { ToggleRow("按正则匹配", regex) { regex = it } }
                    if (draftHeld) row { ActionRow("仍然试一下") { allowed = allowed + pattern } }
                    row {
                        // 正则要等统计完、确认不慢才能添加。
                        ActionRow("添加", enabled = pattern.isNotEmpty() && error == null && !draftSlow && (!regex || draftPreview != null)) {
                            rules = rules + draft
                            pattern = ""
                            replacement = ""
                            regex = false
                        }
                    }
                }
                val canSave = !busy && tocError == null && tooSlow == null && !tocHeld
                // 主按钮和上面的卡片左右对齐（都缩进 12dp）。
                PrimaryButton(
                    if (busy) "正在重新整理……" else "保存并重新分章",
                    onClick = { vm.saveRules(entry, current) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    enabled = canSave,
                )
                Text(
                    "规则只影响灵阅里的显示，不会改动原文件。",
                    color = c.secondary,
                    fontSize = TypeScale.Caption,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 一条净化规则：查找 → 替换、匹配几处和示例；右边删除和开关。 */
@Composable
private fun RuleRow(rule: ReplaceRule, matches: String, samples: List<String>, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val c = LocalGroupedColors.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (rule.regex) "正则 " else "") + rule.pattern, color = c.label, fontSize = TypeScale.Body, fontFamily = FontFamily.Monospace)
            Text(
                (if (rule.replacement.isEmpty()) "→ 删除" else "→ ${rule.replacement}") + " · $matches",
                color = c.secondary,
                fontSize = TypeScale.Caption,
                modifier = Modifier.padding(top = 2.dp),
            )
            samples.forEach { Text(it, color = c.secondary, fontSize = TypeScale.Footnote, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        IconButton(onClick = onDelete) {
            Icon(LingYueIcons.Delete, contentDescription = "删除这条", tint = c.destructive)
        }
        Box(Modifier.toggleable(value = rule.enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onToggle).padding(4.dp)) {
            LingYueSwitch(rule.enabled)
        }
    }
}

/**
 * 在后台统计规则匹配几处（整本书的文字可能有十几兆，不能在主线程上算）；停手 300 毫秒再算。
 * 统计中（或 [run] 为假、先不统计）为 null；正则太慢、被放弃时是失败（[RegexTooSlowException]）。改了规则就不再等上一次的结果。
 */
@Composable
private fun rememberPreview(rule: ReplaceRule, source: String, run: Boolean = true): Result<Pair<Int, List<String>>>? {
    val state = produceState<Result<Pair<Int, List<String>>>?>(null, rule, source, run) {
        value = null
        if (rule.pattern.isEmpty() || !run) return@produceState
        delay(300)
        value = try {
            Result.success(runInterruptible(Dispatchers.Default) { TextRules.preview(rule, source) })
        } catch (slow: RegexTooSlowException) {
            Result.failure(slow)
        }
    }
    return state.value
}

/** 净化规则列表旋转屏幕时存成 JSON，和 rules.json 同一个格式。 */
private val RulesSaver = Saver<List<ReplaceRule>, String>(
    save = { TextRules(it).toJson().toString() },
    restore = { TextRules.fromJson(JSONObject(it)).replace },
)

/** 规则行上的短说明：停用了的、后台卡满了的、只是太慢的。 */
private fun slowLabel(e: RegexTooSlowException): String = when {
    e.busy -> "暂时不能统计"
    e.disabled -> "太慢，已停用"
    else -> "正则太慢"
}

/** 停用、卡满这两种的完整说明；只是太慢的为 null（各处自己说）。 */
private fun slowNote(e: RegexTooSlowException): String? = when {
    e.busy -> "后台已有 ${TextRules.MAX_STUCK} 条太慢的正则停不下来，暂时不能再试别的正则，重启应用后恢复。"
    e.disabled -> "这条规则太慢，已停用（它在后台还没停下，不再重跑）。请改写它，避免 (\\w+\\s?)+ 这类套在一起的重复。"
    else -> null
}
