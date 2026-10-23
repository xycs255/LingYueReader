package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.BookEntry
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DetailScreen(vm: AppViewModel, bookId: String) {
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val entry = shelf.firstOrNull { it.id == bookId }
    val dialogs = remember { BookDialogState() }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "书籍详情",
                navigationIcon = {
                    IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (entry == null) return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            overscrollEffect = null,
        ) {
            item { Header(entry, vm) }
            entry.description?.let { text ->
                item {
                    SmallTitle("简介")
                    Card(Modifier.padding(horizontal = 12.dp)) {
                        Text(text, style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(4.dp))
                    }
                }
            }
            item {
                SmallTitle("信息")
                Card(Modifier.padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                    InfoRow("篇幅", "${entry.chapterCount} 章 · 约 ${wordCount(entry.charCount)}")
                    InfoRow("累计阅读", durationLabel(entry.readMillis))
                    InfoRow("添加于", dateLabel(entry.addedAt))
                    if (entry.openedAt > 0) InfoRow("最近阅读", dateLabel(entry.openedAt))
                    InfoRow("来源文件", entry.sources.joinToString("\n") { it.name })
                }
            }
            item {
                SmallTitle("操作")
                Card(Modifier.padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                    if (entry.sources.any { it.uri != null }) {
                        ArrowPreference(title = "从原文件刷新", summary = "原文件改过后重新读取", onClick = { vm.refresh(entry) })
                    }
                    ArrowPreference(title = "重命名", onClick = { dialogs.renaming = true })
                    ArrowPreference(
                        title = "移除",
                        summary = "删除本机副本与进度、书签批注",
                        onClick = { dialogs.removing = true },
                    )
                }
            }
        }
        BookDialogs(entry, vm, dialogs, onRemoved = { vm.back() })
    }
}

@Composable
private fun Header(entry: BookEntry, vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(104.dp)) {
            BookCover(entry.title, entry.format.label, cover = entry.cover?.let { vm.bookFile(entry.id, it) })
        }
        Column(Modifier.weight(1f).padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            entry.author?.let { Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            Text(
                entry.progress?.let { "${progressLabel(entry)} · ${it.chapterTitle}" } ?: progressLabel(entry),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
            Button(
                onClick = { vm.open(Screen.Reader(entry.id)) },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(if (entry.progress == null) "开始阅读" else "继续阅读", color = MiuixTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    BasicComponent(title = title, summary = value)
}

internal fun wordCount(chars: Int): String = when {
    chars >= 10_000 -> "%.1f 万字".format(chars / 10_000f)
    else -> "$chars 字"
}

internal fun durationLabel(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1 -> "不到 1 分钟"
        minutes < 60 -> "$minutes 分钟"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
    }
}

private fun dateLabel(millis: Long): String = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(millis))
