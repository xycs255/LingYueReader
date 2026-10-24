package com.xycs.lingyuereader.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.data.ReplaceRule
import com.xycs.lingyuereader.data.TextRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 常用的净化规则，点一下填进表单。 */
private val REPLACE_TEMPLATES = listOf(
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
    var loaded by remember { mutableStateOf(false) }
    var toc by remember { mutableStateOf("") }
    var rules by remember { mutableStateOf(listOf<ReplaceRule>()) }
    var source by remember { mutableStateOf("") }
    var chapters by remember { mutableStateOf<List<String>?>(null) }
    var pattern by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }

    LaunchedEffect(entry?.id) {
        val e = entry ?: return@LaunchedEffect
        val r = vm.bookRules(e.id)
        toc = r.toc
        rules = r.replace
        source = vm.bookSourceText(e)
        loaded = true
    }
    val current = TextRules(rules, toc.trim())
    // 停手半秒再试分章：整本重新解析，不能每敲一个字就跑一遍。
    LaunchedEffect(current, loaded) {
        val e = entry ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        chapters = null
        delay(500)
        chapters = runCatching { vm.previewChapters(e, current) }.getOrNull()
    }
    val tocError = toc.takeIf { it.isNotBlank() }?.let(TextRules::error)

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "净化与分章",
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) {
                            Icon(LingYueIcons.Back, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            if (entry == null || !loaded) {
                InfiniteProgressIndicator(Modifier.padding(top = padding.calculateTopPadding() + 48.dp).fillMaxWidth())
                return@GlassScaffold
            }
            val mono = androidx.compose.ui.text.TextStyle(color = c.label, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
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
                        Text("正则有误：$tocError", color = c.destructive, fontSize = 13.sp, modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 20.dp))
                    }
                    val list = chapters
                    // 组名就叫“预览”；试分的结果写在卡片第一行，不拿状态当组名。
                    GroupedSection(header = "预览") {
                        row {
                            Text(
                                when {
                                    list == null -> "正在试分章……"
                                    list.size <= 1 -> "按这个规则只分出 ${list.size} 章，可能没匹配到章名。"
                                    else -> "将分为 ${list.size} 章"
                                },
                                color = if (list != null && list.size <= 1) c.destructive else c.secondary,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                        if (list != null && list.size > 1) {
                            val shown = list.take(12) + if (list.size > 12) listOf("……", list.last()) else emptyList()
                            shown.forEach { title -> row { Text(title, color = if (title == "……") c.secondary else c.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) } }
                        }
                    }
                    GroupedSection(header = "净化规则") {
                        if (rules.isEmpty()) {
                            row {
                                Text(
                                    "还没有规则。在下面添加：查找的文字会被替换，替换为留空就是删除。",
                                    color = c.secondary,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                )
                            }
                        }
                        rules.forEachIndexed { i, r ->
                            row {
                                val preview = rememberPreview(r, source)
                                RuleRow(
                                    rule = r,
                                    matches = preview?.let { "匹配 ${it.first} 处" } ?: "统计中……",
                                    samples = preview?.second.orEmpty(),
                                    onToggle = { on -> rules = rules.toMutableList().also { it[i] = r.copy(enabled = on) } },
                                    onDelete = { rules = rules.filterIndexed { j, _ -> j != i } },
                                )
                            }
                        }
                    }
                    val draft = ReplaceRule(pattern, replacement, regex)
                    val error = if (regex && pattern.isNotEmpty()) TextRules.error(pattern) else null
                    val draftPreview = rememberPreview(draft, source)
                    GroupedSection(
                        header = "添加规则",
                        footer = when {
                            error != null -> "正则有误：$error"
                            pattern.isNotEmpty() -> (draftPreview?.let { "将匹配 ${it.first} 处" } ?: "统计中……") +
                                draftPreview?.second.orEmpty().joinToString("") { "\n$it" }
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
                        row {
                            ActionRow("添加", enabled = pattern.isNotEmpty() && error == null) {
                                rules = rules + draft
                                pattern = ""
                                replacement = ""
                                regex = false
                            }
                        }
                    }
                    Box(
                        Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (!busy && tocError == null) c.tint else c.fill)
                            .clickable(enabled = !busy && tocError == null) { vm.saveRules(entry, current) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (busy) "正在重新整理……" else "保存并重新分章", color = if (!busy && tocError == null) Color.White else c.secondary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "规则只影响灵阅里的显示，不会改动原文件。",
                        color = c.secondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                    )
                }
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
            Text((if (rule.regex) "正则 " else "") + rule.pattern, color = c.label, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            Text(
                (if (rule.replacement.isEmpty()) "→ 删除" else "→ ${rule.replacement}") + " · $matches",
                color = c.secondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            samples.forEach { Text(it, color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        IconButton(onClick = onDelete) {
            Icon(LingYueIcons.Delete, contentDescription = "删除这条", tint = c.destructive)
        }
        Box(Modifier.toggleable(value = rule.enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onToggle).padding(4.dp)) {
            LingYueSwitch(rule.enabled)
        }
    }
}

/** 在后台统计规则匹配几处（整本书的文字可能有十几兆，不能在主线程上算）；停手 300 毫秒再算。 */
@Composable
private fun rememberPreview(rule: ReplaceRule, source: String): Pair<Int, List<String>>? {
    val state = produceState<Pair<Int, List<String>>?>(null, rule, source) {
        value = null
        if (rule.pattern.isEmpty()) return@produceState
        delay(300)
        value = withContext(Dispatchers.Default) { TextRules.preview(rule, source) }
    }
    return state.value
}
