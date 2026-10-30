package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.ui.glass.GlassCard
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.data.SearchHit
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun SearchScreen(vm: AppViewModel, bookId: String) {
    val search by vm.bookSearch.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf(search.query) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { focus.requestFocus() }

    SubPageScaffold(
        title = "书内搜索",
        onBack = { vm.back() },
        barBottom = {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "人名、地名、原话…",
                useLabelAsPlaceholder = true,
                singleLine = true,
                leadingIcon = {
                    Icon(
                        LingYueIcons.Search,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    vm.search(query)
                    keyboard?.hide()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .focusRequester(focus),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            overscrollEffect = null,
        ) {
            when {
                search.query.isBlank() -> item { EmptyState(LingYueIcons.Search, "在这本书里找", "人名、地名、记得的一句话都行。") }
                search.running -> item {
                    ProvideGroupedColors {
                        GroupedSection(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            row { LoadingRow("正在搜索“${search.query}”…") }
                        }
                    }
                }
                search.error != null -> item { EmptyState(LingYueIcons.Search, "搜索失败", search.error!!) }
                search.hits.isEmpty() -> item { EmptyState(LingYueIcons.Search, "没有找到“${search.query}”", "换个说法，或者少打几个字试试。") }
                else -> item {
                    SmallTitle(if (search.hits.size >= 300) "300 处以上，只列前 300 处" else "共 ${search.hits.size} 处", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            items(search.hits) { hit ->
                GlassCard(
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp),
                    pressFeedbackType = pressFeedback(),
                    onClick = { vm.jumpFromSearch(bookId, hit.chapterIndex, hit.blockIndex, hit.charOffset) },
                ) {
                    Column {
                        Text(hit.chapterTitle, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                        Text(
                            buildAnnotatedString {
                                if (hit.before.length >= 18) append("…")
                                append(hit.before)
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)) {
                                    append(hit.match)
                                }
                                append(hit.after)
                                append("…")
                            },
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 全书架搜索：在所有书的正文里找一句话，按书分组列出，点一处直接打开那本书的那个位置。 */
@Composable
fun LibrarySearchScreen(vm: AppViewModel) {
    val state by vm.librarySearch.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf(state.query) }
    val keyboard = LocalSoftwareKeyboardController.current

    SubPageScaffold(
        title = "在所有书里搜索",
        onBack = { vm.back() },
        barBottom = {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "一句话、人名、地名…",
                useLabelAsPlaceholder = true,
                singleLine = true,
                leadingIcon = {
                    Icon(
                        LingYueIcons.Search,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    vm.searchLibrary(query)
                    keyboard?.hide()
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        },
    ) { padding ->
        LibrarySearchList(
            state,
            PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
        ) { book, hit ->
            vm.open(com.xycs.lingyuereader.Screen.Reader(book.entry.id, hit.chapterIndex, hit.blockIndex, char = hit.charOffset, highlight = true))
        }
    }
}

/** 全书架搜索的结果列表：进度（或总数）、按书分组的命中；搜着时一本本接在后面。 */
@Composable
internal fun LibrarySearchList(
    state: AppViewModel.LibrarySearchState,
    contentPadding: PaddingValues,
    onOpen: (AppViewModel.BookHits, SearchHit) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().overScrollVertical(),
        contentPadding = contentPadding,
        overscrollEffect = null,
    ) {
        val found = state.results.sumOf { it.hits.size }
        when {
            state.query.isEmpty() -> item { EmptyState(LingYueIcons.Search, "在所有书里找", "书架上每本书的正文都会搜一遍。") }
            !state.running && found == 0 -> item { EmptyState(LingYueIcons.Search, "没有找到“${state.query}”", "换个说法，或者少打几个字试试。") }
            else -> item(key = "status") {
                // 搜索进度不写在组名里（组名只是标签）：搜着时是卡片里一行转圈的“正在搜索”，搜完换成结果的总数。
                AnimatedContent(state.running, transitionSpec = { Motion.fadeThrough() }, label = "librarySearchStatus") { running ->
                    if (running) {
                        ProvideGroupedColors {
                            GroupedSection(Modifier.padding(top = 8.dp)) { row { LoadingRow("正在搜索 ${state.done}/${state.total} 本…") } }
                        }
                    } else {
                        SmallTitle("在 ${state.results.size} 本书里找到 $found 处", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        state.results.forEach { book ->
            // 一本书搜完整组接在后面：书名和各处命中淡入，已有的不动。
            item(key = "book-" + book.entry.id) {
                Text(
                    "《${book.entry.title}》" + if (book.more) " · 只列前 ${book.hits.size} 处" else " · ${book.hits.size} 处",
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
                )
            }
            // 同一段（表格这类按块定位的）可能有几处命中落在同一位置，键带上序号。
            itemsIndexed(book.hits, key = { i, _ -> "hit-${book.entry.id}-$i" }) { _, hit ->
                GlassCard(
                    modifier = Modifier
                        .animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 10.dp),
                    pressFeedbackType = pressFeedback(),
                    onClick = { onOpen(book, hit) },
                ) {
                    Column {
                        Text(hit.chapterTitle, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                        Text(
                            buildAnnotatedString {
                                if (hit.before.length >= 18) append("…")
                                append(hit.before)
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)) {
                                    append(hit.match)
                                }
                                append(hit.after)
                                append("…")
                            },
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 命中卡片按下时下沉；减少动效时不动（墨水屏上下沉一下只会留残影）。 */
private fun pressFeedback(): PressFeedbackType = if (Effects.noMotion) PressFeedbackType.None else PressFeedbackType.Sink
