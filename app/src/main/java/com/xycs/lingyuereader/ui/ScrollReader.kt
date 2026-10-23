package com.xycs.lingyuereader.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.data.Chapter
import com.xycs.lingyuereader.data.Marks
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

/** 列表里第 0 项是章名，正文块从第 1 项开始。 */
internal const val HEADER_ITEMS = 1

@Composable
internal fun ScrollReader(
    chapter: Chapter,
    chapterIndex: Int,
    nextTitle: String?,
    styles: ReaderStyles,
    marks: Marks,
    listState: LazyListState,
    topInset: Dp,
    bottomInset: Dp,
    onTap: (xFraction: Float) -> Unit,
    onLongPress: (block: Int) -> Unit,
    onPosition: (item: Int, offset: Int, fraction: Float) -> Unit,
    onNext: () -> Unit,
    registerTurner: ((Boolean) -> Unit) -> Unit,
) {
    val palette = styles.palette
    val scope = rememberCoroutineScope()
    val lineHeightPx = with(LocalDensity.current) { (styles.settings.fontSize * styles.settings.lineHeight).sp.toPx() }
    val total = chapter.blocks.size + 2

    DisposableEffect(listState) {
        registerTurner { forward ->
            val viewport = listState.layoutInfo.viewportSize.height
            val distance = (viewport - lineHeightPx * 2).coerceAtLeast(lineHeightPx)
            scope.launch { listState.animateScrollBy(if (forward) distance else -distance) }
        }
        onDispose { }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (item, offset) ->
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: item
                val fraction = if (last >= total - 1) 1f else item.toFloat() / total
                onPosition(item, offset, fraction)
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap(it.x / size.width) }) },
        contentPadding = PaddingValues(
            start = styles.horizontalPadding,
            end = styles.horizontalPadding,
            top = topInset,
            bottom = bottomInset + 12.dp,
        ),
    ) {
        item {
            Text(
                chapter.title,
                style = styles.title,
                modifier = Modifier.padding(top = styles.titleTop, bottom = styles.titleBottom),
            )
        }
        itemsIndexed(chapter.blocks) { index, block ->
            BlockView(
                block,
                styles,
                marks.notesIn(chapterIndex, index),
                modifier = Modifier.readerGestures(index, onTap) { onLongPress(index) },
            )
        }
        item {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("— 本章完 —", color = palette.secondary, fontFamily = styles.fontFamily, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                if (nextTitle != null) {
                    Button(onClick = onNext, colors = ButtonDefaults.buttonColors(color = palette.bar)) {
                        Text("下一章　$nextTitle", color = palette.accent, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text("全书完", color = palette.secondary, fontSize = 13.sp)
                }
            }
        }
    }
}
