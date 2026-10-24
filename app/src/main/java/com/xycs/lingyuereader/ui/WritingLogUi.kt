package com.xycs.lingyuereader.ui

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.SentRequest
import com.xycs.lingyuereader.data.WriteRecord
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
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 发给 AI 的原文：系统提示（规矩、带上的设定资料）和这次的请求，可以选中复制。 */
@Composable
internal fun SentDialog(sent: SentRequest, onDismiss: () -> Unit) {
    val c = LocalGroupedColors.current
    val clipboard = LocalClipboardManager.current
    GlassDialog(show = true, title = "发给 AI 的内容", summary = "${sent.label} · ${whenLabel(sent.at)} · 共 ${sent.size} 字", onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("系统提示（${sent.system.length} 字）", color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            SelectionContainer { Text(sent.system, color = c.label, fontSize = 13.sp, lineHeight = 19.sp) }
            Text("请求（${sent.prompt.length} 字）", color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            SelectionContainer { Text(sent.prompt, color = c.label, fontSize = 13.sp, lineHeight = 19.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = "复制", onClick = { clipboard.setText(AnnotatedString("【系统提示】\n${sent.system}\n\n【请求】\n${sent.prompt}")) }, modifier = Modifier.weight(1f))
            TextButton(text = "好", onClick = onDismiss, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

/**
 * 写作 AI 的记录：灵感、读者点评、矛盾检查、AI 写的章，新的在前。点开看全文、复制，标有用或没用（作者自己判断提示词好不好用），也可以删掉。
 */
@Composable
fun WorkAiLogScreen(vm: AppViewModel, workId: String) {
    val changed by vm.writingAi.logChanged.collectAsStateWithLifecycle()
    var list by remember { mutableStateOf<List<WriteRecord>?>(null) }
    var open by remember { mutableStateOf<WriteRecord?>(null) }
    LaunchedEffect(workId, changed) { list = vm.writingAi.aiLog(workId) }
    val snackbar = rememberWritingMessages(vm)
    val clipboard = LocalClipboardManager.current

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "AI 记录",
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
                    val all = list
                    val rated = all.orEmpty().filter { it.rating != 0 }
                    GroupedSection(
                        footer = if (rated.isEmpty()) "点开一条，可以标“有用”或“没用”。攒多了能看出哪类请求 AI 做得好、哪类不行。"
                        else "标过的 ${rated.size} 条里，有用 ${rated.count { it.rating > 0 }} 条，没用 ${rated.count { it.rating < 0 }} 条。",
                    ) {
                        if (all != null && all.isEmpty()) row { Text("还没有记录。", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                        all.orEmpty().forEach { r ->
                            row {
                                NavRow(
                                    r.kind + (if (r.chapterTitle.isNotBlank()) " · ${r.chapterTitle}" else ""),
                                    value = when (r.rating) {
                                        1 -> "有用"
                                        -1 -> "没用"
                                        else -> null
                                    },
                                    subtitle = whenLabel(r.at) + " · " + r.text.replace('\n', ' ').take(60),
                                ) { open = r }
                            }
                        }
                    }
                }
            }
            open?.let { r ->
                GlassDialog(
                    show = true,
                    title = r.kind + (if (r.chapterTitle.isNotBlank()) " · ${r.chapterTitle}" else ""),
                    summary = whenLabel(r.at) + if (r.ask.isNotBlank()) " · 要求：${r.ask}" else "",
                    onDismissRequest = { open = null },
                ) {
                    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        SelectionContainer { Text(r.text, color = c.label, fontSize = 15.sp, lineHeight = 23.sp) }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            text = "有用",
                            onClick = { vm.writingAi.rateLog(workId, r.id, if (r.rating == 1) 0 else 1); open = null },
                            modifier = Modifier.weight(1f),
                            colors = if (r.rating == 1) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                        )
                        TextButton(
                            text = "没用",
                            onClick = { vm.writingAi.rateLog(workId, r.id, if (r.rating == -1) 0 else -1); open = null },
                            modifier = Modifier.weight(1f),
                            colors = if (r.rating == -1) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(text = "复制", onClick = { clipboard.setText(AnnotatedString(r.text)); vm.showMessage("已复制") }, modifier = Modifier.weight(1f))
                        TextButton(text = "删除", onClick = { vm.writingAi.deleteLog(workId, r.id); open = null }, modifier = Modifier.weight(1f))
                        TextButton(text = "关闭", onClick = { open = null }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
