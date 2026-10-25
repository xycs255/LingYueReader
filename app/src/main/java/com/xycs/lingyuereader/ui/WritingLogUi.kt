package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.SentRequest
import com.xycs.lingyuereader.data.WriteRecord
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 对话框底下只有一个键（“好”“关闭”）：整宽。[primary] 时是强调色底配 onTint 的字，否则是灰底正文色。
 * 配色取设置页的（阅读页的面板里也跟着书页配色），不用 miuix 写死的主色。
 */
@Composable
internal fun DialogOkButton(text: String, primary: Boolean = true, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    val (fill, content) = if (primary) buttonColors(c) else c.fill to c.label
    TextButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        colors = ButtonDefaults.textButtonColors(color = fill, textColor = content),
    )
}

/** 发给 AI 的原文：系统提示（规矩、带上的设定资料）和这次的请求，可以选中复制。 */
@Composable
internal fun SentDialog(sent: SentRequest, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    GlassDialog(show = true, title = "发给 AI 的内容", summary = "${sent.label} · ${whenLabel(sent.at)} · 共 ${sent.size} 字", onDismissRequest = onDismiss) {
        val c = LocalGroupedColors.current
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("系统提示（${sent.system.length} 字）", color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            SelectionContainer { Text(sent.system, color = c.label, fontSize = 13.sp, lineHeight = 19.sp) }
            Text("请求（${sent.prompt.length} 字）", color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            SelectionContainer { Text(sent.prompt, color = c.label, fontSize = 13.sp, lineHeight = 19.sp) }
            if (sent.lookups.isNotEmpty()) {
                Text("AI 查了（${sent.lookups.size} 次）", color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                sent.lookups.forEach { Text("· $it", color = c.label, fontSize = 13.sp, lineHeight = 19.sp) }
            }
        }
        // 左边“复制”（复制了不关），右边“好”。
        DialogButtons(
            onDismiss = {
                clipboard.setText(
                    AnnotatedString(
                        "【系统提示】\n${sent.system}\n\n【请求】\n${sent.prompt}" + if (sent.lookups.isEmpty()) "" else "\n\n【AI 查了】\n" + sent.lookups.joinToString("\n"),
                    ),
                )
            },
            dismissText = "复制",
            onConfirm = onDismiss,
            confirmText = "好",
        )
    }
}

/** AI 记录里点开一条之后能做的（删除另外放在最后、红色）。 */
private enum class LogAction(val label: String) { USEFUL("有用"), USELESS("没用"), COPY("复制") }

/**
 * 写作 AI 的记录：灵感、读者点评、矛盾检查、AI 写的章，新的在前。点开看全文、复制，标有用或没用（作者自己判断提示词好不好用），
 * 也可以删掉（删了不能恢复，要再确认一次）。
 */
@Composable
fun WorkAiLogScreen(vm: AppViewModel, workId: String) {
    val changed by vm.writingAi.logChanged.collectAsStateWithLifecycle()
    var list by remember { mutableStateOf<List<WriteRecord>?>(null) }
    var open by remember { mutableStateOf<WriteRecord?>(null) }
    var deleting by remember { mutableStateOf<WriteRecord?>(null) }
    LaunchedEffect(workId, changed) { list = vm.writingAi.aiLog(workId) }
    val snackbar = rememberWritingMessages(vm)
    val clipboard = LocalClipboardManager.current

    SubPageScaffold("AI 记录", onBack = { vm.back() }, snackbar = snackbar) { padding ->
        // 配色在骨架提供的配色里面读。
        val c = LocalGroupedColors.current
        val all = list
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            when {
                all == null -> item(key = "loading") { LoadingState("正在读 AI 记录……") }
                all.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        LingYueIcons.Sparkle,
                        "还没有 AI 记录",
                        "要过的灵感、读者点评、矛盾检查和 AI 写的章都会记在这里，可以回头看、复制，标有用还是没用。",
                    )
                }
                else -> {
                    // 一条一格，连成一张卡片；删掉一条时淡出，下面的平滑补上来。
                    items(all, key = { it.id }, contentType = { "record" }) { r ->
                        GroupedListCell(r === all.first(), r === all.last(), Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()), gap = 8.dp) {
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
                    item(key = "footer") {
                        val rated = all.filter { it.rating != 0 }
                        GroupedListFooter(
                            if (rated.isEmpty()) "点开一条，可以标“有用”或“没用”。攒多了能看出哪类请求 AI 做得好、哪类不行。"
                            else "标过的 ${rated.size} 条里，有用 ${rated.count { it.rating > 0 }} 条，没用 ${rated.count { it.rating < 0 }} 条。",
                            Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()),
                        )
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
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(r.text, color = c.label, fontSize = 15.sp, lineHeight = 23.sp) }
                }
                // 标有用 / 没用（标过的再点一次就取消）、复制：一行一项，标过的打勾；删除单独放最后、红色，还要再确认。
                DialogOptionList(
                    LogAction.entries.map { it to it.label },
                    modifier = Modifier.padding(top = 12.dp),
                    selected = when (r.rating) {
                        1 -> LogAction.USEFUL
                        -1 -> LogAction.USELESS
                        else -> null
                    },
                    onPick = { action ->
                        when (action) {
                            LogAction.USEFUL -> vm.writingAi.rateLog(workId, r.id, if (r.rating == 1) 0 else 1)
                            LogAction.USELESS -> vm.writingAi.rateLog(workId, r.id, if (r.rating == -1) 0 else -1)
                            LogAction.COPY -> {
                                clipboard.setText(AnnotatedString(r.text))
                                vm.showMessage("已复制")
                            }
                        }
                        open = null
                    },
                )
                Box(Modifier.clip(RoundedCornerShape(12.dp))) {
                    ActionRow("删除这条记录", destructive = true) {
                        open = null
                        deleting = r
                    }
                }
                DialogOkButton("关闭", primary = false) { open = null }
            }
        }
        deleting?.let { r ->
            GlassDialog(
                show = true,
                title = "删除这条记录",
                summary = "“${r.kind}”这条 AI 记录会删掉，不能恢复。",
                onDismissRequest = { deleting = null },
            ) {
                DialogButtons(
                    onDismiss = { deleting = null },
                    onConfirm = {
                        deleting = null
                        vm.writingAi.deleteLog(workId, r.id)
                    },
                    confirmText = "删除",
                    destructive = true,
                )
            }
        }
    }
}

/**
 * 长列表里的一格：同一组的格子连成一张圆角卡片（首格上面圆角、末格下面圆角），和 [GroupedSection] 一个样子，
 * 但能懒加载、每格能单独做增删动画（LazyColumn 的 items + animateItem）。[last] 那格下面留出 [gap]：
 * 卡片之间 12dp，下面紧跟说明小字时 8dp（[GroupedListFooter]）。
 */
@Composable
internal fun GroupedListCell(first: Boolean, last: Boolean, modifier: Modifier = Modifier, gap: Dp = 12.dp, content: @Composable () -> Unit) {
    val c = LocalGroupedColors.current
    val r = 16.dp
    val shape = RoundedCornerShape(
        topStart = if (first) r else 0.dp,
        topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp,
        bottomEnd = if (last) r else 0.dp,
    )
    Box(modifier.fillMaxWidth().padding(bottom = if (last) gap else 0.dp)) {
        Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(shape).background(c.cell)) {
            content()
        }
    }
}

/** 长列表的组名：和 [GroupedSection] 的组名一样，卡片上方的灰色小字。 */
@Composable
internal fun GroupedListHeader(text: String, modifier: Modifier = Modifier) {
    SmallTitle(text, modifier = modifier, textColor = LocalGroupedColors.current.secondary)
}

/** 长列表卡片下的说明小字（和 [GroupedSection] 的 footer 一样）：末格用 `gap = 8.dp`，下面再留 12dp。 */
@Composable
internal fun GroupedListFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LocalGroupedColors.current.secondary,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
    )
}
