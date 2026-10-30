package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.BookEntry
import com.xycs.lingyuereader.data.BookFormat
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 书籍详情：左封面右书名作者进度，下面整宽的“继续阅读”，再下面是简介、信息和操作几组。 */
@Composable
fun DetailScreen(vm: AppViewModel, bookId: String) {
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val entry = shelf.firstOrNull { it.id == bookId }
    val dialogs = remember { BookDialogState() }

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "书籍详情",
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) {
                            Icon(LingYueIcons.Back, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            if (entry == null) return@GlassScaffold
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
                overscrollEffect = null,
            ) {
                item { Header(entry, vm) }
                entry.description?.let { text ->
                    item {
                        GroupedSection(header = "简介") {
                            row { Text(text, color = c.label, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.padding(16.dp)) }
                        }
                    }
                }
                item {
                    GroupedSection(header = "信息") {
                        row { ValueRow("累计阅读", durationLabel(entry.readMillis)) }
                        row { ValueRow("添加于", dateLabel(entry.addedAt)) }
                        if (entry.openedAt > 0) row { ValueRow("最近阅读", dateLabel(entry.openedAt)) }
                        row { ValueRow("来源文件", entry.sources.joinToString("\n") { it.name }) }
                    }
                    // 跳页的用带“›”的行（净化与分章），就地做事、弹对话框的用动作行（刷新、分组、重命名）。
                    GroupedSection {
                        if (entry.format in setOf(BookFormat.TXT, BookFormat.MARKDOWN, BookFormat.MIXED)) {
                            row { NavRow("净化与分章") { vm.open(Screen.BookRules(entry.id)) } }
                        }
                        if (entry.sources.any { it.uri != null }) {
                            row { ActionRow("从原文件刷新") { vm.refresh(entry) } }
                        }
                        row { ActionRow("分组…") { dialogs.grouping = true } }
                        row { ActionRow("重命名") { dialogs.renaming = true } }
                    }
                    GroupedSection(footer = "删除本机副本与阅读进度、书签批注；原文件不受影响。") {
                        row { ActionRow("移除", destructive = true) { dialogs.removing = true } }
                    }
                }
            }
            BookDialogs(entry, vm, dialogs, onRemoved = { vm.back() })
        }
    }
}

@Composable
private fun Header(entry: BookEntry, vm: AppViewModel) {
    val c = LocalGroupedColors.current
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 20.dp)) {
        // 封面在左、书名作者进度在右：一眼看全，不用把一张大封面摆在正中占掉半屏。
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(96.dp)) {
                BookCover(entry.title, entry.author.orEmpty(), cover = entry.cover?.let { vm.bookFile(entry.id, it) })
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(entry.title, color = c.label, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                entry.author?.let { Text(it, color = c.secondary, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp)) }
                Text(
                    "${entry.format.label} · ${entry.chapterCount} 章 · 约 ${wordCount(entry.charCount)}",
                    color = c.secondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    entry.progress?.let { "${progressLabel(entry)} · ${it.chapterTitle}" } ?: "还没开始读",
                    color = if (entry.progress != null) c.tint else c.secondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // 主按钮和下面的卡片左右对齐（都缩进 12dp）。
        PrimaryButton(
            if (entry.progress == null) "开始阅读" else "继续阅读",
            onClick = { vm.open(Screen.Reader(entry.id)) },
            modifier = Modifier.padding(top = 20.dp),
        )
    }
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
