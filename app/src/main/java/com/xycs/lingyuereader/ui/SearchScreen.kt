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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun SearchScreen(vm: AppViewModel, bookId: String) {
    val savedQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val hits by vm.searchHits.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf(savedQuery) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { focus.requestFocus() }

    GlassScaffold(
        topBar = {
            SmallTopAppBar(
                color = Color.Transparent,
                modifier = Modifier.glassBar(),
                title = "书内搜索",
                navigationIcon = {
                    IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                bottomContent = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "人名、地名、原话…",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                MiuixIcons.Search,
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
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            overscrollEffect = null,
        ) {
            if (savedQuery.isNotBlank()) {
                item {
                    SmallTitle(if (hits.size >= 300) "300 处以上，只列前 300 处" else "共 ${hits.size} 处")
                }
            }
            items(hits) { hit ->
                GlassCard(
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp),
                    pressFeedbackType = PressFeedbackType.Sink,
                    onClick = { vm.jumpFromSearch(bookId, hit.chapterIndex, hit.blockIndex) },
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

    GlassScaffold(
        topBar = {
            SmallTopAppBar(
                color = Color.Transparent,
                modifier = Modifier.glassBar(),
                title = "在所有书里搜索",
                navigationIcon = {
                    IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                bottomContent = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "一句话、人名、地名…",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                MiuixIcons.Search,
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
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            overscrollEffect = null,
        ) {
            if (state.query.isNotEmpty()) {
                item {
                    val found = state.results.sumOf { it.hits.size }
                    SmallTitle(
                        when {
                            state.running -> "正在搜索 ${state.done}/${state.total} 本…"
                            found == 0 -> "没有找到“${state.query}”"
                            else -> "在 ${state.results.size} 本书里找到 $found 处"
                        },
                    )
                }
            }
            state.results.forEach { book ->
                item(key = "book-" + book.entry.id) {
                    Text(
                        "《${book.entry.title}》" + if (book.more) " · 只列前 ${book.hits.size} 处" else " · ${book.hits.size} 处",
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
                    )
                }
                items(book.hits) { hit ->
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp),
                        pressFeedbackType = PressFeedbackType.Sink,
                        onClick = { vm.open(com.xycs.lingyuereader.Screen.Reader(book.entry.id, hit.chapterIndex, hit.blockIndex)) },
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
}
