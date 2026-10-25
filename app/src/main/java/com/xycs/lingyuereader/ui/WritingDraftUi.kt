package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * AI 写本章：按细纲卡、本章资料、上一章结尾、写作规范和检查规则写一版草稿。边写边显示，写完存成草稿；
 * 作者看过以后放进正文（替换或接在后面，原来的正文先存一版），也可以接着写、重写。AI 写的字不算码字数。
 */
@Composable
fun ChapterDraftScreen(vm: AppViewModel, workId: String, chapterId: String) {
    val drafts by vm.writingAi.drafts.collectAsStateWithLifecycle()
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val chapter = works.firstOrNull { it.id == workId }?.chapter(chapterId)
    var instruction by rememberSaveable(chapterId) { mutableStateOf("") }
    var placing by remember { mutableStateOf(false) }
    var card by remember(chapterId) { mutableStateOf<ChapterCard?>(null) }
    LaunchedEffect(workId, chapterId) {
        vm.writingAi.loadDraft(workId, chapterId)
        card = vm.writing.cards(workId, chapterId).first.card
    }
    val draft = drafts[workId to chapterId]
    val text = draft?.text.orEmpty()
    val loading = draft?.loading == true
    val lookup by vm.writingAi.lookup.collectAsStateWithLifecycle()
    // 写完以后按检查规则看一遍（本机算）。
    val hits by produceState<Int?>(null, text, loading) {
        value = if (loading || text.isBlank()) null else vm.writing.rules(workId).takeIf { !it.isEmpty }?.let { CheckRules.check(text, it).hits.size }
    }
    val snackbar = rememberWritingMessages(vm)
    val locked = chapter?.locked == true
    val sent by vm.writingAi.lastRequest.collectAsStateWithLifecycle()
    var showSent by remember { mutableStateOf(false) }

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "AI 写本章",
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) { Icon(LingYueIcons.Back, contentDescription = "返回") }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().imePadding().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    GroupedSection(
                        footer = when {
                            locked -> "这一章已定稿，不能让 AI 重写。要改先解锁并登记原因。"
                            !aiConfig.ready -> "先在“我的 → AI 助手”里设置服务商。"
                            card?.isEmpty == true -> "这一章还没有细纲卡：AI 只能凭前文和设定猜着写。先写细纲卡更稳。"
                            else -> "按细纲卡、本章资料、上一章结尾、写作规范和检查规则写；篇幅按检查规则里的字数。写好的是草稿，看过再放进正文。"
                        },
                    ) {
                        row {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                TextField(
                                    value = instruction,
                                    onValueChange = { instruction = it },
                                    label = "这一章想怎么写（可空）· 比如：多写对白，节奏快一点",
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 140.dp),
                                )
                            }
                        }
                        if (card?.isEmpty == true) row { NavRow("先写细纲卡") { vm.open(Screen.ChapterCard(workId, chapterId)) } }
                        row {
                            when {
                                loading -> ActionRow("停止", destructive = true) { vm.writingAi.stopDraft(workId, chapterId) }
                                text.isBlank() -> ActionRow("写本章", enabled = aiConfig.ready && !locked) { vm.writingAi.writeDraft(workId, chapterId, instruction) }
                                else -> ActionRow("重写一版", enabled = aiConfig.ready && !locked) { vm.writingAi.writeDraft(workId, chapterId, instruction) }
                            }
                        }
                        if (text.isNotBlank() && !loading) {
                            row { ActionRow("接着写", enabled = aiConfig.ready && !locked) { vm.writingAi.writeDraft(workId, chapterId, more = true) } }
                        }
                        if (sent != null && sent?.label in setOf("写本章", "接着写")) {
                            row { NavRow("看发给 AI 的内容", value = sent?.let { "${it.size} 字" }) { showSent = true } }
                        }
                    }
                    if (text.isNotBlank() || loading || draft?.error != null) {
                        GroupedSection(
                            header = buildString {
                                append("草稿 · ${WordCount.of(text)} 字")
                                if (loading) append(lookup?.let { " · 正在查：$it" } ?: " · 正在写……")
                                hits?.let { append(if (it == 0) " · 合规则" else " · $it 条不合规则") }
                            },
                        ) {
                            draft?.error?.let { e -> row { Text("出错了：$e", color = Color(0xFFE5484D), fontSize = 14.sp, modifier = Modifier.padding(16.dp)) } }
                            if (text.isNotBlank()) {
                                row {
                                    SelectionContainer {
                                        Text(
                                            text,
                                            color = c.label,
                                            fontSize = 17.sp,
                                            lineHeight = 30.sp,
                                            style = androidx.compose.ui.text.TextStyle(textIndent = TextIndent(firstLine = 2.em)),
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        )
                                    }
                                }
                            }
                            if (text.isNotBlank() && !loading) {
                                row { ActionRow("放进正文", enabled = !locked) { placing = true } }
                                row { ActionRow("不要这版", destructive = true) { vm.writingAi.clearDraft(workId, chapterId) } }
                            }
                        }
                    }
                }
            }
            if (showSent) sent?.let { SentDialog(it) { showSent = false } }
            if (placing) {
                val empty = (chapter?.words ?: 0) == 0
                GlassDialog(
                    show = true,
                    title = "放进正文",
                    summary = if (empty) "这一章还没有正文，草稿直接放进去。" else "正文现在有 ${chapter?.words} 字。原来的正文会先存一个历史版本。AI 写的字不算码字数。",
                    onDismissRequest = { placing = false },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        fun place(replace: Boolean) {
                            placing = false
                            vm.writing.applyDraft(workId, chapterId, text, replace) {
                                vm.writingAi.clearDraft(workId, chapterId)
                                vm.showMessage("已放进正文")
                                vm.back()
                            }
                        }
                        if (empty) {
                            TextButton(text = "放进正文", onClick = { place(true) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
                        } else {
                            TextButton(text = "替换正文", onClick = { place(true) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
                            TextButton(text = "接在正文后面", onClick = { place(false) }, modifier = Modifier.fillMaxWidth())
                        }
                        Row(Modifier.fillMaxWidth()) { TextButton(text = "取消", onClick = { placing = false }, modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
