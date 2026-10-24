package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChapterStats
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.RuleHit
import com.xycs.lingyuereader.data.RuleReport
import com.xycs.lingyuereader.data.RuleSet
import com.xycs.lingyuereader.data.WorkChapter
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical

private val MISS = Color(0xFFE5484D)

/** 一章的统计，一行说完。 */
internal fun statsLine(s: ChapterStats): String =
    "${s.words} 字 · 对白 ${s.dialoguePercent}% · 段落中位 ${s.medianParagraph} 字 · 平均句长 ${s.averageSentence} 字"

/**
 * 检查规则：设定包里“检查规则”文档读出来的规则（逐条用白话列出，没看懂的行标红），
 * 可以让 AI 从写作规范里整理一份，也可以一键检查全书，点进不合规则的章节。全部在本机算，不联网（AI 整理除外）。
 */
@Composable
fun WorkRulesScreen(vm: AppViewModel, workId: String) {
    val packs by vm.writing.packs.collectAsStateWithLifecycle()
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val pack = packs[workId]
    val ruleDocs = pack.orEmpty().filter { it.kind == PackKind.RULES }
    val hasStyle = pack.orEmpty().any { (it.kind == PackKind.STYLE || it.kind == PackKind.VOICE) && it.words > 0 }
    var rules by remember { mutableStateOf<RuleSet?>(null) }
    var results by remember { mutableStateOf<List<Pair<WorkChapter, RuleReport>>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var drafting by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = rememberWritingMessages(vm)
    LaunchedEffect(workId) { vm.writing.loadPack(workId) }
    // 规则文档改过（设定包刷新）就重读规则，检查结果作废。
    LaunchedEffect(workId, pack) {
        rules = vm.writing.rules(workId)
        results = null
    }

    fun checkAll() {
        checking = true
        scope.launch {
            results = runCatching { vm.writing.checkAll(workId) }.getOrElse { emptyList() }
            checking = false
        }
    }

    fun draftRules() {
        drafting = true
        scope.launch {
            try {
                draft = vm.writingAi.draftRules(workId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.showMessage(e.message ?: "整理失败")
            } finally {
                drafting = false
            }
        }
    }

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "检查规则",
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) { Icon(LingYueIcons.Back, contentDescription = "返回") }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    val set = rules
                    GroupedSection(
                        header = if (set == null || set.isEmpty) "规则" else "规则（${set.rules.size} 条）",
                        footer = "规则写在设定包的“检查规则”文档里，每行一条，比如“字数：3000-6000”“至多 1：点了点头、顿了顿／一顿”“禁用：您”“句式：不是……而是……”“短段：12 字以内至多 5 处”。" +
                            "灵阅在本机检查，不联网。",
                    ) {
                        if (set != null && set.isEmpty && set.unknown.isEmpty()) {
                            row { Text("还没有规则。", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                        }
                        set?.rules?.forEach { r ->
                            row {
                                GroupedRow {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(CheckRules.describe(r), color = c.label, fontSize = 15.sp, lineHeight = 21.sp)
                                        Text("${r.source.doc} · 第 ${r.source.line} 行", color = c.secondary, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        if (ruleDocs.isEmpty()) {
                            row {
                                ActionRow("新建检查规则") {
                                    vm.writing.addDoc(workId, PackKind.RULES, PackKind.RULES.label) { vm.open(Screen.PackDoc(workId, it.id)) }
                                }
                            }
                        } else {
                            ruleDocs.forEach { d -> row { NavRow(if (ruleDocs.size == 1) "编辑规则" else "编辑“${d.title}”") { vm.open(Screen.PackDoc(workId, d.id)) } } }
                        }
                        row {
                            ActionRow(if (drafting) "AI 正在整理……" else "用 AI 从写作规范整理", enabled = !drafting && aiConfig.ready && hasStyle) { draftRules() }
                        }
                    }
                    if (!aiConfig.ready || !hasStyle) {
                        Text(
                            if (!aiConfig.ready) "用 AI 整理要先在“我的 → AI 助手”里设置服务商。" else "用 AI 整理要先在设定包里放写作规范或称谓与语气文档。",
                            color = c.secondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
                        )
                    }
                    if (set != null && set.unknown.isNotEmpty()) {
                        GroupedSection(header = "没看懂的行", footer = "这些行没有生效。改成上面的写法，或者在行首加“#”当说明。") {
                            set.unknown.forEach { u ->
                                row {
                                    GroupedRow {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(u.text, color = MISS, fontSize = 15.sp, lineHeight = 21.sp)
                                            Text("${u.doc} · 第 ${u.line} 行", color = c.secondary, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (set != null && !set.isEmpty) {
                        val list = results
                        val bad = list.orEmpty().filter { it.second.hits.isNotEmpty() }
                        GroupedSection(
                            header = "全书检查",
                            footer = list?.let { "共 ${it.size} 章，${bad.size} 章有不合规则的地方。检查的是已经存盘的正文。" },
                        ) {
                            row { ActionRow(if (checking) "正在检查……" else if (list == null) "检查全书" else "重新检查", enabled = !checking) { checkAll() } }
                            bad.forEach { (ch, report) ->
                                row {
                                    NavRow(
                                        ch.title,
                                        value = "${report.hits.size} 条",
                                        subtitle = report.hits.take(2).joinToString("；") { it.message },
                                    ) { vm.open(Screen.Editor(workId, ch.id, check = true)) }
                                }
                            }
                        }
                    }
                }
            }
            draft?.let { text ->
                RulesDraftDialog(
                    text,
                    replacing = ruleDocs.isNotEmpty(),
                    onDismiss = { draft = null },
                ) { edited ->
                    draft = null
                    vm.writing.saveRules(workId, edited) { vm.showMessage("已存进设定包的“${it.title}”") }
                }
            }
        }
    }
}

/** AI 整理出来的规则：先给作者看、能改，存了才生效。 */
@Composable
private fun RulesDraftDialog(text: String, replacing: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(text) }
    val parsed = remember(value) { CheckRules.parse(value) }
    val c = LocalGroupedColors.current
    GlassDialog(
        show = true,
        title = "AI 整理的规则",
        summary = "读懂 ${parsed.rules.size} 条" + (if (parsed.unknown.isNotEmpty()) "，${parsed.unknown.size} 行没看懂" else "") +
            if (replacing) "。存了会替换现在的检查规则，原来的进历史版本。" else "。",
        onDismissRequest = onDismiss,
    ) {
        TextField(value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp))
        if (parsed.unknown.isNotEmpty()) {
            Text(
                "没看懂：" + parsed.unknown.take(3).joinToString("；") { it.text },
                color = MISS,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 3,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            TextButton(
                text = "存为检查规则",
                enabled = parsed.rules.isNotEmpty(),
                onClick = { onSave(value.trimEnd() + "\n") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        if (parsed.rules.isEmpty()) Text("没有读懂的规则，先改一改。", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** 编辑器里的规则检查：本章统计和不合规则的地方；点一条，依次选中正文里出现的位置。 */
@Composable
internal fun RulesPanel(
    report: RuleReport?,
    hasRules: Boolean,
    onJump: (RuleHit) -> Unit,
    onOpenRules: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalGroupedColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(c.cell)
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("规则检查", color = c.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(text = "规则", onClick = onOpenRules)
            IconButton(onClick = onClose) { Icon(LingYueIcons.Close, contentDescription = "收起", tint = c.secondary) }
        }
        when {
            !hasRules -> Text(
                "还没有检查规则。在设定包里写一份“检查规则”，比如篇幅、口头禅每章至多几次、禁用词，灵阅就能逐章检查。",
                color = c.secondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            report == null -> Text("正在检查……", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
            else -> {
                Text(statsLine(report.stats), color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    if (report.hits.isEmpty()) Text("都合规则。", color = Color(0xFF2F9E44), fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
                    report.hits.forEach { h ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = h.ranges.isNotEmpty()) { onJump(h) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(h.message, color = c.label, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
                            if (h.ranges.isNotEmpty()) Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary)
                        }
                    }
                }
            }
        }
    }
}
