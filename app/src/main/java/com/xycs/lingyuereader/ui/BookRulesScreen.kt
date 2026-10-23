package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.ui.glass.GlassCard
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
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

    GlassScaffold(
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
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            item {
                SmallTitle("分章规则")
                GlassCard(Modifier.padding(horizontal = 12.dp)) {
                    Text("留空时自动识别“第X章”“序章”“番外”等。分不对时选一个格式，或自己写正则（匹配到的整行当作章名）。", style = MiuixTheme.textStyles.footnote1)
                    FlowRow(
                        Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Choice("自动识别", toc.isBlank()) { toc = "" }
                        TextRules.TOC_PRESETS.forEach { p -> Choice(p.label, toc == p.pattern) { toc = p.pattern } }
                    }
                    TextField(
                        value = toc,
                        onValueChange = { toc = it },
                        label = "分章正则",
                        singleLine = true,
                        textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (tocError != null) Text("正则有误：$tocError", color = MiuixTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    val list = chapters
                    Text(
                        when {
                            list == null -> "正在试分章……"
                            list.size <= 1 -> "按这个规则只分出 ${list.size} 章，可能没匹配到章名。"
                            else -> "将分为 ${list.size} 章："
                        },
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    if (list != null && list.size > 1) {
                        Text(
                            list.take(12).joinToString("\n") + if (list.size > 12) "\n……\n" + list.last() else "",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                SmallTitle("净化规则")
                if (rules.isEmpty()) {
                    Text(
                        "还没有规则。下面添加：查找的文字会被替换，替换为留空就是删除。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                }
            }
            itemsIndexed(rules) { i, r ->
                val preview = rememberPreview(r, source)
                GlassCard(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (r.regex) "正则 " else "") + r.pattern,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                            )
                            Text(
                                if (r.replacement.isEmpty()) "→ 删除" else "→ ${r.replacement}",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Switch(checked = r.enabled, onCheckedChange = { on -> rules = rules.toMutableList().also { it[i] = r.copy(enabled = on) } })
                    }
                    Text(preview?.let { "匹配 ${it.first} 处" } ?: "统计中……", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    preview?.second.orEmpty().forEach { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    TextButton("删除这条", onClick = { rules = rules.filterIndexed { j, _ -> j != i } }, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item {
                GlassCard(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("添加规则", fontWeight = FontWeight.Medium)
                    FlowRow(
                        Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        REPLACE_TEMPLATES.forEach { (label, t) ->
                            Choice(label, false) {
                                pattern = t.pattern
                                replacement = t.replacement
                                regex = t.regex
                            }
                        }
                    }
                    TextField(
                        value = pattern,
                        onValueChange = { pattern = it },
                        label = "查找",
                        singleLine = true,
                        textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        label = "替换为（留空即删除）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("按正则匹配", modifier = Modifier.weight(1f))
                        Switch(checked = regex, onCheckedChange = { regex = it })
                    }
                    val draft = ReplaceRule(pattern, replacement, regex)
                    val error = if (regex && pattern.isNotEmpty()) TextRules.error(pattern) else null
                    val draftPreview = rememberPreview(draft, source)
                    if (error != null) {
                        Text("正则有误：$error", color = MiuixTheme.colorScheme.error, fontSize = 13.sp)
                    } else if (pattern.isNotEmpty()) {
                        Text(draftPreview?.let { "将匹配 ${it.first} 处" } ?: "统计中……", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        draftPreview?.second.orEmpty().forEach { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    }
                    TextButton(
                        "添加",
                        enabled = pattern.isNotEmpty() && error == null,
                        onClick = {
                            rules = rules + draft
                            pattern = ""
                            replacement = ""
                            regex = false
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            item {
                TextButton(
                    if (busy) "正在重新整理……" else "保存并重新分章",
                    enabled = !busy && tocError == null,
                    onClick = { vm.saveRules(entry, current) },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                )
                Text(
                    "规则只影响灵阅里的显示，不会改动原文件。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
            }
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

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
