package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.CrashLog
import com.xycs.lingyuereader.ui.glass.GlassDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 上次灵阅闪退了（还没问过）：打开时低调地问一次要不要复制错误信息发给开发者。“复制”“不用了”都算问过，以后不再弹；
 * 记录还留在“我的 → 闪退记录”里。
 */
@Composable
internal fun CrashPrompt(vm: AppViewModel) {
    var crashed by remember { mutableStateOf<CrashLog.Entry?>(null) }
    LaunchedEffect(Unit) { crashed = withContext(Dispatchers.IO) { CrashLog.unseen() } }
    val entry = crashed ?: return
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    fun done() {
        crashed = null
        scope.launch { withContext(Dispatchers.IO) { CrashLog.markSeen() } }
    }
    GlassDialog(
        show = true,
        title = "上次灵阅意外退出了",
        summary = "要不要复制错误信息发给开发者？里面只有出错的位置、时间和版本，不带正文。以后也能在“我的 → 闪退记录”里找到。",
        onDismissRequest = { done() },
    ) {
        DialogButtons(
            onDismiss = { done() },
            dismissText = "不用了",
            onConfirm = {
                clipboard.setText(AnnotatedString(CrashLog.report(entry)))
                done()
                vm.showMessage("错误信息复制好了")
            },
            confirmText = "复制",
        )
    }
}

/** “我的 → 闪退记录”：最近几次闪退、接住了的意外，点开看堆栈、复制，也能清空。 */
@Composable
fun CrashLogScreen(vm: AppViewModel) {
    var list by remember { mutableStateOf<List<CrashLog.Entry>?>(null) }
    var open by remember { mutableStateOf<CrashLog.Entry?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        list = withContext(Dispatchers.IO) { CrashLog.entries().also { CrashLog.markSeen() } }
    }
    val snackbar = rememberWritingMessages(vm)
    val clipboard = LocalClipboardManager.current
    SubPageScaffold("闪退记录", onBack = { vm.back() }, snackbar = snackbar) { padding ->
        val all = list
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            when {
                all == null -> item(key = "loading") { LoadingState("正在读闪退记录……") }
                all.isEmpty() -> item(key = "empty") {
                    EmptyState(LingYueIcons.Info, "没有闪退记录", "灵阅意外退出时会在这里记下出错的位置、时间和版本（不带正文），好发给开发者查。")
                }
                else -> item(key = "list") {
                    GroupedSection(
                        footer = "闪退和接住的意外各留最近 ${CrashLog.KEEP} 次。记录只有出错的位置、时间、版本和当时的界面，错误说明里的汉字都隐去了。",
                    ) {
                        all.forEach { e ->
                            row {
                                NavRow(
                                    (if (e.fatal) "闪退" else "接住的意外") + " · " + CrashLog.time(e.at),
                                    subtitle = headline(e) + (if (e.count > 1) " · ${e.count} 次" else ""),
                                    subtitleMaxLines = 2,
                                ) { open = e }
                            }
                        }
                    }
                    GroupedSection {
                        row { ActionRow("全部复制") { clipboard.setText(AnnotatedString(all.joinToString("\n\n----\n\n") { CrashLog.report(it) })); vm.showMessage("复制好了") } }
                        row { ActionRow("清空记录", destructive = true) { clearing = true } }
                    }
                }
            }
        }
        CrashLogDialogs(vm, open, onClose = { open = null }, clearing, onClearing = { clearing = it }) {
            scope.launch {
                withContext(Dispatchers.IO) { CrashLog.clear() }
                list = emptyList()
            }
        }
    }
}

/** 闪退记录页的对话框（挂在这一页的 Scaffold 里）：点开一条看全文、复制；清空前再问一次。 */
@Composable
private fun CrashLogDialogs(vm: AppViewModel, open: CrashLog.Entry?, onClose: () -> Unit, clearing: Boolean, onClearing: (Boolean) -> Unit, onClear: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    open?.let { e ->
        GlassDialog(show = true, title = if (e.fatal) "闪退" else "接住的意外", summary = CrashLog.time(e.at), onDismissRequest = onClose) {
            val c = LocalGroupedColors.current
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionContainer {
                    Text(CrashLog.report(e), color = c.label, fontSize = TypeScale.Footnote, lineHeight = TypeScale.FootnoteLine, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
                }
            }
            DialogButtons(
                onDismiss = {
                    clipboard.setText(AnnotatedString(CrashLog.report(e)))
                    vm.showMessage("复制好了")
                },
                dismissText = "复制",
                onConfirm = onClose,
                confirmText = "好",
            )
        }
    }
    if (clearing) {
        GlassDialog(show = true, title = "清空闪退记录", summary = "记下的几次闪退、意外都删掉，不能恢复。", onDismissRequest = { onClearing(false) }) {
            DialogButtons(
                onDismiss = { onClearing(false) },
                onConfirm = {
                    onClearing(false)
                    onClear()
                },
                confirmText = "清空",
                destructive = true,
            )
        }
    }
}

/** 一条记录的第一行：异常的类名（去掉包名）和说明。 */
private fun headline(e: CrashLog.Entry): String {
    val first = e.trace.lineSequence().firstOrNull().orEmpty()
    val name = first.substringBefore(": ").substringAfterLast('.')
    return if (": " in first) name + "：" + first.substringAfter(": ") else name
}
