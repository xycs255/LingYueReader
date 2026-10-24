package com.xycs.lingyuereader.ui

import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 书籍详情，照 Apple Books 的样子：居中的大封面、书名作者、整宽的“继续阅读”，下面是简介、信息和操作三组。 */
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
                        row { ValueRow("篇幅", "${entry.chapterCount} 章 · 约 ${wordCount(entry.charCount)}") }
                        row { ValueRow("累计阅读", durationLabel(entry.readMillis)) }
                        row { ValueRow("添加于", dateLabel(entry.addedAt)) }
                        if (entry.openedAt > 0) row { ValueRow("最近阅读", dateLabel(entry.openedAt)) }
                        row { ValueRow("来源文件", entry.sources.joinToString("\n") { it.name }) }
                    }
                    GroupedSection {
                        if (entry.sources.any { it.uri != null }) {
                            row { NavRow("从原文件刷新") { vm.refresh(entry) } }
                        }
                        if (entry.format in setOf(BookFormat.TXT, BookFormat.MARKDOWN, BookFormat.MIXED)) {
                            row { NavRow("净化与分章规则") { vm.open(Screen.BookRules(entry.id)) } }
                        }
                        row { NavRow("重命名") { dialogs.renaming = true } }
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
    Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(132.dp)) {
            BookCover(entry.title, entry.format.label, cover = entry.cover?.let { vm.bookFile(entry.id, it) })
        }
        Text(entry.title, color = c.label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
        entry.author?.let { Text(it, color = c.secondary, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp)) }
        Text(
            entry.progress?.let { "${progressLabel(entry)} · ${it.chapterTitle}" } ?: progressLabel(entry),
            color = c.secondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Box(
            Modifier
                .padding(top = 18.dp)
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.tint)
                .clickable(role = Role.Button) { vm.open(Screen.Reader(entry.id)) },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (entry.progress == null) "开始阅读" else "继续阅读", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
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
