package com.xycs.lingyuereader.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.xycs.lingyuereader.ui.glass.GlassCard
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassMenu
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import top.yukonga.miuix.kmp.basic.Checkbox
import com.xycs.lingyuereader.data.ShelfPrefs
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.EnterExitState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
import com.xycs.lingyuereader.data.PageTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.BookEntry
import com.xycs.lingyuereader.data.BookParser
import com.xycs.lingyuereader.data.Library
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import com.xycs.lingyuereader.data.ShelfSort

/**
 * 换页面的动画（docs/motion.md“页面”一节）：
 * - 从书架打开书：阅读页从封面的位置放大出来，书架略微后退淡出；返回时缩回那本书的封面；
 * - 层级滑动：进下一层从右边推入，上一层往左退四分之一并变暗；返回反过来，离开的一页在上面；
 * - 淡入淡出：新页面略放大淡入；
 * - 无 / 省电：直接切换。
 */
private fun AnimatedContentTransitionScope<Screen>.screenTransform(style: PageTransition, forward: Boolean): ContentTransform {
    if (Effects.noMotion || style == PageTransition.NONE) return EnterTransition.None togetherWith ExitTransition.None
    val target = targetState
    val initial = initialState
    val origin = TransformOrigin(OpenFrom.pivotX, OpenFrom.pivotY)
    if (target is Screen.Reader && initial == Screen.Shelf && OpenFrom.bookId == target.bookId) {
        return (scaleIn(Motion.emphasized(), initialScale = OpenFrom.widthFraction, transformOrigin = origin) + fadeIn(Motion.standard(Motion.SHORT))) togetherWith
            (scaleOut(Motion.emphasized(), targetScale = 1.06f, transformOrigin = origin) + fadeOut(Motion.standard(Motion.LONG)))
    }
    if (initial is Screen.Reader && target == Screen.Shelf && OpenFrom.bookId == initial.bookId) {
        // 缩回去的阅读页在上面。缩小本身由 [CoverShrink] 跟着封面现在的位置做（读过的书可能已排到最前），这里只管最后淡出。
        return ((scaleIn(Motion.emphasized(), initialScale = 1.06f) + fadeIn(Motion.standard())) togetherWith
            fadeOut(Motion.standard(Motion.SHORT, delay = Motion.LONG - Motion.SHORT))).apply { targetContentZIndex = -1f }
    }
    return when (style) {
        PageTransition.FADE -> (fadeIn(Motion.standard()) + scaleIn(Motion.standard(), initialScale = 0.97f)) togetherWith fadeOut(Motion.exit())
        else -> if (forward) {
            slideInHorizontally(Motion.emphasized()) { it } togetherWith
                (slideOutHorizontally(Motion.emphasized()) { -it / 4 } + fadeOut(Motion.standard(Motion.LONG), targetAlpha = 0.35f))
        } else {
            (slideInHorizontally(Motion.emphasized()) { -it / 4 } + fadeIn(Motion.standard(Motion.LONG), initialAlpha = 0.35f)) togetherWith
                slideOutHorizontally(Motion.emphasized()) { it }
        }.apply { if (!forward) targetContentZIndex = -1f }
    }
}

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val stack by vm.stack.collectAsStateWithLifecycle()
    BackHandler(enabled = stack.size > 1) { vm.back() }

    val settings by vm.settings.collectAsStateWithLifecycle()
    LaunchedEffect(settings.eInk) { Effects.reduced = settings.eInk }
    val appearance by vm.appearance.collectAsStateWithLifecycle()
    LaunchedEffect(appearance.blur, appearance.liquidGlass) {
        com.xycs.lingyuereader.ui.glass.GlassStyle.blur = appearance.blur
        com.xycs.lingyuereader.ui.glass.GlassStyle.lens = appearance.liquidGlass
    }
    // 左右滑动的方向：进到下一层从右边进来，返回从左边进来。
    val depth = remember { object { var last = 1; var forward = true } }
    if (stack.size != depth.last) {
        depth.forward = stack.size > depth.last
        depth.last = stack.size
    }

    AnimatedContent(
        targetState = stack.last(),
        // 编辑器换章（上一章、下一章、写下一章）也滑一页：按章节先后定左右，不整页硬换。
        transitionSpec = { screenTransform(appearance.transition, editorStepForward(vm, initialState, targetState) ?: depth.forward) },
        contentKey = { if (it is Screen.Editor) it::class to it.chapterId else it::class },
        label = "screen",
    ) { screen ->
        when (screen) {
            Screen.Shelf -> HomeScreen(vm)
            is Screen.Reader -> {
                // 返回书架时缩回这本书的封面：封面的位置在书架排好后才知道，所以每帧现读。
                val toCover = stack.last() == Screen.Shelf && OpenFrom.bookId == screen.bookId &&
                    appearance.transition != PageTransition.NONE && !Effects.noMotion
                val shrink by transition.animateFloat({ Motion.emphasized() }, label = "cover") { state ->
                    if (state == EnterExitState.PostExit && toCover) OpenFrom.widthFraction else 1f
                }
                Box(
                    Modifier.graphicsLayer {
                        if (shrink < 1f) {
                            scaleX = shrink
                            scaleY = shrink
                            transformOrigin = TransformOrigin(OpenFrom.pivotX, OpenFrom.pivotY)
                        }
                    },
                ) { ReaderScreen(vm, screen) }
            }
            is Screen.Search -> SearchScreen(vm, screen.bookId)
            Screen.LibrarySearch -> LibrarySearchScreen(vm)
            is Screen.Detail -> DetailScreen(vm, screen.bookId)
            Screen.Dictionaries -> DictionariesScreen(vm)
            Screen.Ai -> AiScreen(vm)
            Screen.OfflineVoice -> OfflineVoiceScreen(vm)
            Screen.Catalogs -> CatalogsScreen(vm)
            is Screen.CatalogEdit -> CatalogEditScreen(vm, screen.catalogId)
            is Screen.CatalogBrowse -> CatalogBrowseScreen(vm, screen)
            Screen.Theme -> ThemeScreen(vm)
            is Screen.BookRules -> BookRulesScreen(vm, screen.bookId)
            is Screen.Work -> WorkScreen(vm, screen.workId)
            is Screen.Editor -> EditorScreen(vm, screen.workId, screen.chapterId, screen.check)
            is Screen.WorkHistory -> WorkHistoryScreen(vm, screen.workId, screen.chapterId, screen.docId)
            is Screen.WorkAi -> WorkAiScreen(vm, screen.workId)
            is Screen.WorkAiAdvanced -> WorkAiAdvancedScreen(vm, screen.workId)
            is Screen.WorkPack -> WorkPackScreen(vm, screen.workId)
            is Screen.WorkLedger -> WorkLedgerScreen(vm, screen.workId, screen.chapterId, screen.promises)
            is Screen.PackDoc -> PackDocScreen(vm, screen.workId, screen.docId)
            is Screen.ChapterCard -> ChapterCardScreen(vm, screen.workId, screen.chapterId)
            is Screen.WorkChanges -> WorkChangesScreen(vm, screen.workId)
            is Screen.WorkRules -> WorkRulesScreen(vm, screen.workId)
            is Screen.ChapterBrief -> ChapterBriefScreen(vm, screen.workId, screen.chapterId)
            is Screen.ChapterReview -> ChapterReviewScreen(vm, screen.workId, screen.chapterId)
            is Screen.ChapterDraft -> ChapterDraftScreen(vm, screen.workId, screen.chapterId)
            is Screen.WorkAiLog -> WorkAiLogScreen(vm, screen.workId)
            is Screen.WorkStyleSamples -> WorkStyleSamplesScreen(vm, screen.workId)
            is Screen.WritingReference -> WritingReferenceScreen(vm, screen.workId, screen.chapterId)
            is Screen.AiUsage -> AiUsageScreen(vm, screen.workId)
            is Screen.ChapterSync -> ChapterSyncScreen(vm, screen.workId)
            is Screen.ChapterFinish -> ChapterFinishScreen(vm, screen.workId, screen.chapterId)
            is Screen.WorkFormatSettings -> WorkFormatSettingsScreen(vm, screen.workId)
            is Screen.WorkBanned -> WorkBannedScreen(vm, screen.workId)
            is Screen.WorkLearned -> WorkLearnedScreen(vm, screen.workId)
            is Screen.WorkFormat -> WorkFormatScreen(vm, screen.workId)
            Screen.CrashLog -> CrashLogScreen(vm)
        }
    }
    // 闪退记录：记下现在在哪个界面（只记名字，不带书名、章名）。
    val top = stack.last()
    SideEffect { com.xycs.lingyuereader.CrashLog.screen = top.toString().substringBefore('(') }
}

@Composable
internal fun ShelfScreen(vm: AppViewModel) {
    val all by vm.shelf.collectAsStateWithLifecycle()
    val shelf by vm.visibleShelf.collectAsStateWithLifecycle()
    val shelfPrefs by vm.shelfPrefs.collectAsStateWithLifecycle()
    val groups by vm.shelfGroups.collectAsStateWithLifecycle()
    val query by vm.shelfQuery.collectAsStateWithLifecycle()
    val loaded by vm.shelfLoaded.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val pending by vm.pendingImport.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val continueReading = shelf.filter { it.progress != null }.maxByOrNull { it.openedAt }
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = MiuixScrollBehavior()
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    var moreMenu by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.pick(it) }
    // 各家文件管理器给 .md/.epub 报的类型不一，干脆不按类型过滤，选完再按扩展名判断。
    val pickFiles = { picker.launch(arrayOf("*/*")) }

    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }

    GlassScaffold(
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                modifier = Modifier.glassBar(),
                title = "书架",
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) vm.setShelfQuery("")
                    }) {
                        Icon(LingYueIcons.Search, contentDescription = "搜索书架")
                    }
                    // “+”：从手机里选文件导入，或到书源（OPDS、WebDAV）里下载。
                    Box {
                        var addMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { addMenu = true }, enabled = !busy) {
                            Icon(LingYueIcons.Add, contentDescription = "添加书")
                        }
                        OverlayListPopup(show = addMenu, alignment = PopupPositionProvider.Align.End, onDismissRequest = { addMenu = false }) {
                            GlassMenu {
                                ListPopupColumn {
                                    LingYueMenuItem("导入手机里的文件", selected = false) {
                                        addMenu = false
                                        pickFiles()
                                    }
                                    LingYueMenuItem("从书源下载", selected = false) {
                                        addMenu = false
                                        vm.open(Screen.Catalogs)
                                    }
                                }
                            }
                        }
                    }
                    Box(Modifier.padding(end = 12.dp)) {
                        IconButton(onClick = { moreMenu = true }) {
                            Icon(LingYueIcons.Sort, contentDescription = "排序与显示")
                        }
                        // 只放排序和显示方式；其他功能在底部的“我的”里。
                        val options = ShelfSort.entries.map { "按${it.label}" } + (if (shelfPrefs.list) "网格显示" else "列表显示")
                        OverlayListPopup(
                            show = moreMenu,
                            alignment = PopupPositionProvider.Align.End,
                            onDismissRequest = { moreMenu = false },
                        ) {
                            GlassMenu {
                                ListPopupColumn {
                                    options.forEachIndexed { index, label ->
                                        LingYueMenuItem(label, selected = index == shelfPrefs.sort.ordinal) {
                                            moreMenu = false
                                            if (index < ShelfSort.entries.size) {
                                                vm.updateShelfPrefs { it.copy(sort = ShelfSort.entries[index]) }
                                            } else {
                                                vm.updateShelfPrefs { it.copy(list = !it.list) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                bottomContent = {
                    Column {
                        GroupChips(vm, groups, shelfPrefs.group, hasUngrouped = all.any { it.groups.isEmpty() })
                        if (searching) {
                            TextField(
                                value = query,
                                onValueChange = vm::setShelfQuery,
                                label = "搜索书名或作者",
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { if (query.isNotBlank()) vm.openLibrarySearch(query) }),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                            )
                            if (query.isNotBlank()) {
                                // 书名、作者之外，还可以在所有书的正文里找。
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 20.dp)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(Radius.Medium))
                                        .clickable { vm.openLibrarySearch(query) }
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        "在所有书的正文里搜索“${query.trim()}”",
                                        color = MiuixTheme.colorScheme.primary,
                                        style = MiuixTheme.textStyles.body2,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        if (busy) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LinearProgressIndicator(Modifier.weight(1f))
                                if (importing) {
                                    TextButton(text = "取消导入", onClick = vm::cancelImport, modifier = Modifier.heightIn(min = 48.dp))
                                }
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = LocalTabBarInset.current)) },
    ) { padding ->
        val listModifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
        val contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + LocalTabBarInset.current + 20.dp,
        )
        // 列表 ↔ 网格、空状态 ↔ 有书时原地淡出淡入（fadeThrough），不一下子换掉；减少动效时直接换。
        // 同一种样子里（筛选、排序、删书）不换内容，由各项自己的 animateItem 挪位置。
        val view = when {
            !loaded -> ShelfView.LOADING
            all.isEmpty() -> ShelfView.EMPTY
            shelf.isEmpty() -> if (query.isNotBlank()) ShelfView.NO_MATCH else ShelfView.EMPTY_GROUP
            shelfPrefs.list -> ShelfView.LIST
            else -> ShelfView.GRID
        }
        AnimatedContent(
            view,
            // 刚打开、书架还没读出来时什么都不画：读出来直接显示，不在启动时多等一下淡入。
            transitionSpec = { if (initialState == ShelfView.LOADING) EnterTransition.None togetherWith ExitTransition.None else Motion.fadeThrough() },
            label = "shelfView",
        ) { shown ->
            when (shown) {
                ShelfView.LOADING -> Unit
                ShelfView.EMPTY -> EmptyShelf(Modifier.padding(padding), pickFiles)
                ShelfView.NO_MATCH -> EmptyState(
                    LingYueIcons.Search,
                    "书名和作者里没有“${query.trim()}”",
                    "可以到所有书的正文里找找。",
                    Modifier.padding(padding),
                    action = "在正文里搜索" to { vm.openLibrarySearch(query) },
                )
                ShelfView.EMPTY_GROUP -> EmptyState(LingYueIcons.Book, "这个分组里还没有书", "长按书，选“分组…”把它加进来。", Modifier.padding(padding))
                ShelfView.LIST -> LazyColumn(
                    modifier = listModifier,
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    overscrollEffect = null,
                ) {
                    if (continueReading != null) {
                        item(key = "continue-reading") {
                            ContinueReading(continueReading, vm, Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()))
                        }
                    }
                    items(shelf, key = { it.id }) { entry ->
                        ShelfRow(entry, vm, Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()))
                    }
                }
                // 手机竖屏一行三本，平板、横屏按宽度多放几本。
                ShelfView.GRID -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(100.dp),
                    modifier = listModifier,
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    overscrollEffect = null,
                ) {
                    if (continueReading != null) {
                        item(key = "continue-reading", span = { GridItemSpan(maxLineSpan) }) {
                            ContinueReading(continueReading, vm, Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()))
                        }
                    }
                    items(shelf, key = { it.id }) { entry ->
                        // 排序、分组筛选、删书时各本书挪到新位置，而不是一下子跳过去。
                        ShelfItem(entry, vm, Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()))
                    }
                }
            }
        }
    }

    pending?.let { files -> ImportDialog(files, vm) }
}

/** 书架这一页正显示哪种样子：换样子时原地淡出淡入。 */
private enum class ShelfView { LOADING, EMPTY, NO_MATCH, EMPTY_GROUP, LIST, GRID }

/**
 * 书架顶部的分组筛选条：全部、各分组、未分组。长按分组可改名或删除。没有分组时不显示。
 * 样子同胶囊按钮（[PillButton]）：选中的是淡色胶囊（淡强调色底、强调色字），其余灰底；看着 36dp 高，能点的范围 48dp。
 * [PillButton] 不能长按，这里照它的取值自己接一个长按。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupChips(vm: AppViewModel, groups: List<String>, selected: String?, hasUngrouped: Boolean) {
    if (groups.isEmpty()) return
    var editing by remember { mutableStateOf<String?>(null) }
    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            @Composable
            fun Chip(label: String, value: String?, onLongClick: (() -> Unit)? = null) {
                val on = selected == value
                val source = remember { MutableInteractionSource() }
                val fill by animateColorAsState(if (on) c.tint.copy(alpha = 0.12f) else c.fill, Motion.standard(Motion.SHORT), label = "groupChipFill")
                val text by animateColorAsState(if (on) c.tint else c.label, Motion.standard(Motion.SHORT), label = "groupChipText")
                Box(
                    Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .combinedClickable(
                            interactionSource = source,
                            indication = null,
                            role = Role.Button,
                            onClick = { vm.selectGroup(value) },
                            onLongClick = onLongClick,
                        )
                        .semantics { this.selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .pressScale(source, 0.95f)
                            .heightIn(min = 36.dp)
                            .clip(CircleShape)
                            .background(fill)
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = text, fontSize = TypeScale.Body, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
            Chip("全部", null)
            groups.forEach { g -> Chip(g, g) { editing = g } }
            if (hasUngrouped) Chip("未分组", ShelfPrefs.UNGROUPED)
        }
    }
    editing?.let { g ->
        var name by remember(g) { mutableStateOf(g) }
        GlassDialog(show = true, title = "分组“$g”", summary = "改名，或删除这个分组（书不会被删除）。", onDismissRequest = { editing = null }) {
            TextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "删除分组",
                    onClick = {
                        vm.renameGroup(g, null)
                        editing = null
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "改名",
                    enabled = name.isNotBlank() && name.trim() != g,
                    onClick = {
                        vm.renameGroup(g, name)
                        editing = null
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EmptyShelf(modifier: Modifier, onImport: () -> Unit) {
    EmptyState(
        LingYueIcons.Book,
        "书架还是空的",
        "支持 TXT、EPUB、Markdown、MOBI / AZW3、FB2，以及 PDF 和漫画 CBZ。一次选多个文件，可以合成一本书；也可以在“我的 → 书源”里从 OPDS 书库、WebDAV 文件夹下载。",
        modifier,
        action = "导入书籍" to onImport,
    )
}

internal fun progressLabel(entry: BookEntry): String {
    val progress = entry.progress ?: return "未读 · ${entry.chapterCount.coerceAtLeast(0)} 章"
    if (entry.chapterCount <= 0) return "继续阅读"
    val chapter = progress.chapterIndex.coerceIn(0, entry.chapterCount - 1) + 1
    return "读到第 $chapter/${entry.chapterCount} 章"
}

/** 当前筛选结果里最近读过的书，提供一眼能找到的继续阅读入口。 */
@Composable
private fun ContinueReading(entry: BookEntry, vm: AppViewModel, modifier: Modifier = Modifier) {
    val open = rememberOpenFromCover(entry.id, "featured") { vm.open(Screen.Reader(entry.id)) }
    GlassCard(
        modifier.fillMaxWidth(),
        insideMargin = PaddingValues(12.dp),
        pressFeedbackType = shelfPressFeedback(),
        onClick = open.click,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(52.dp)) {
                BookCover(
                    entry.title,
                    "",
                    modifier = open.track,
                    cover = entry.cover?.let { vm.bookFile(entry.id, it) },
                    compact = true,
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("接着读", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                Text(entry.title, style = MiuixTheme.textStyles.title4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    progressLabel(entry),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 3.dp),
                )
                entry.progress?.chapterTitle?.takeIf { it.isNotBlank() }?.let { chapter ->
                    Text(
                        chapter,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("继续阅读", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                    Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** 封面、书架卡片按下时下沉（miuix Sink）；墨水屏 / 省电、系统关了动画时不动，免得留残影。 */
private fun shelfPressFeedback(): PressFeedbackType = if (Effects.noMotion) PressFeedbackType.None else PressFeedbackType.Sink

/** 封面在窗口里的位置，点开时交给 [OpenFrom]，阅读页从这里放大出来。 */
private class OpenFromCover(val track: Modifier, val click: () -> Unit)
private var activeCoverSource: String? = null

@Composable
private fun rememberOpenFromCover(bookId: String, source: String, open: () -> Unit): OpenFromCover {
    // 用根视图的大小（和 boundsInWindow 同一坐标系）。
    val view = LocalView.current
    val bounds = remember { object { var rect = androidx.compose.ui.geometry.Rect.Zero } }
    val current by rememberUpdatedState(open)
    return remember(bookId, source) {
        OpenFromCover(
            track = Modifier.onGloballyPositioned {
                bounds.rect = it.boundsInWindow()
                // 正从这本书退回书架：封面一排好就更新位置，阅读页缩到这里。
                if (OpenFrom.bookId == bookId && activeCoverSource == source) {
                    OpenFrom.set(bookId, bounds.rect, view.width.toFloat(), view.height.toFloat())
                }
            },
            click = {
                activeCoverSource = source
                OpenFrom.set(bookId, bounds.rect, view.width.toFloat(), view.height.toFloat())
                current()
            },
        )
    }
}

/** 网格里的一本书：封面，下面书名、进度和一个小小的“更多”。点封面阅读，长按封面或点“更多”管理。 */
@Composable
private fun ShelfItem(entry: BookEntry, vm: AppViewModel, modifier: Modifier = Modifier) {
    val dialogs = remember { BookDialogState() }
    var menu by remember { mutableStateOf(false) }
    val open = rememberOpenFromCover(entry.id, "grid") { vm.open(Screen.Reader(entry.id)) }
    val haptic = LocalHapticFeedback.current
    Column(modifier) {
        BookCover(
            entry.title,
            entry.author.orEmpty(),
            modifier = open.track,
            cover = entry.cover?.let { vm.bookFile(entry.id, it) },
            onClick = open.click,
            onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                menu = true
            },
        )
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    shelfProgress(entry),
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (entry.progress != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box {
                // 看着是 18dp 的小“⋯”，能点的范围是整整 48dp（测试里量过），不用对准那几个点。
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(Radius.Full)
                        .clickable(role = Role.Button) { menu = true }
                        .semantics { contentDescription = "操作" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(LingYueIcons.More, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(18.dp))
                }
                BookMenu(entry, vm, menu, dialogs) { menu = false }
            }
        }
    }
    BookDialogs(entry, vm, dialogs)
}

/** 书架格子里那行小字：读过的写读到百分之几，没读过写“未读”。 */
internal fun shelfProgress(entry: BookEntry): String {
    val progress = entry.progress ?: return "未读"
    if (entry.chapterCount <= 0) return "在读"
    return "读到 ${progress.chapterIndex.coerceIn(0, entry.chapterCount - 1) + 1}/${entry.chapterCount} 章"
}

/** 列表里的一本书：小封面、书名、作者、进度。 */
@Composable
private fun ShelfRow(entry: BookEntry, vm: AppViewModel, modifier: Modifier = Modifier) {
    val dialogs = remember { BookDialogState() }
    var menu by remember { mutableStateOf(false) }
    val open = rememberOpenFromCover(entry.id, "list") { vm.open(Screen.Reader(entry.id)) }
    val haptic = LocalHapticFeedback.current
    GlassCard(
        modifier = modifier,
        pressFeedbackType = shelfPressFeedback(),
        onClick = open.click,
        onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu = true
                },
        insideMargin = PaddingValues(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(52.dp)) {
                BookCover(entry.title, "", modifier = open.track, cover = entry.cover?.let { vm.bookFile(entry.id, it) }, compact = true)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(entry.title, style = MiuixTheme.textStyles.title4, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(entry.author, entry.format.label).joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    progressLabel(entry),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Box {
                // 能点的范围 48dp（miuix 的图标按钮默认只有 40dp），图形大小不变。
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(LingYueIcons.More, contentDescription = "操作", tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                BookMenu(entry, vm, menu, dialogs) { menu = false }
            }
        }
    }
    BookDialogs(entry, vm, dialogs)
}

/** 书的操作菜单与对话框，书架和详情页共用。 */
internal class BookDialogState {
    var renaming by mutableStateOf(false)
    var removing by mutableStateOf(false)
    var grouping by mutableStateOf(false)
}

@Composable
private fun BookMenu(entry: BookEntry, vm: AppViewModel, show: Boolean, dialogs: BookDialogState, onDismiss: () -> Unit) {
    // 只留三样：详情 / 分组… / 移除。从原文件刷新、重命名、净化与分章在详情页里。
    val actions = listOf<Pair<String, () -> Unit>>(
        "详情" to { vm.open(Screen.Detail(entry.id)) },
        "分组…" to { dialogs.grouping = true },
        "移除" to { dialogs.removing = true },
    )
    OverlayListPopup(show = show, onDismissRequest = onDismiss) {
        GlassMenu {
            ListPopupColumn {
                actions.forEach { (label, action) ->
                    LingYueMenuItem(label, selected = false) {
                        onDismiss()
                        action()
                    }
                }
            }
        }
    }
}

@Composable
internal fun BookDialogs(entry: BookEntry, vm: AppViewModel, dialogs: BookDialogState, onRemoved: () -> Unit = {}) {
    if (dialogs.grouping) {
        val all by vm.shelfGroups.collectAsStateWithLifecycle()
        var chosen by remember { mutableStateOf(entry.groups.toSet()) }
        var extra by remember { mutableStateOf(listOf<String>()) }
        var newName by remember { mutableStateOf("") }
        GlassDialog(show = true, title = "《${entry.title}》的分组", onDismissRequest = { dialogs.grouping = false }) {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                (all + extra).distinct().forEach { g ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.Medium)).clickable { chosen = if (g in chosen) chosen - g else chosen + g }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            state = ToggleableState(g in chosen),
                            onClick = { chosen = if (g in chosen) chosen - g else chosen + g },
                        )
                        Text(g, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = "新建分组",
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "添加",
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val g = newName.trim()
                        extra = extra + g
                        chosen = chosen + g
                        newName = ""
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            DialogButtons(
                onDismiss = { dialogs.grouping = false },
                onConfirm = {
                    vm.setBookGroups(entry, (all + extra).filter { it in chosen })
                    dialogs.grouping = false
                },
                confirmText = "确定",
            )
        }
    }
    if (dialogs.renaming) {
        var title by remember { mutableStateOf(entry.title) }
        GlassDialog(show = true, title = "重命名", onDismissRequest = { dialogs.renaming = false }) {
            TextField(value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DialogButtons(
                onDismiss = { dialogs.renaming = false },
                onConfirm = {
                    vm.rename(entry, title)
                    dialogs.renaming = false
                },
                confirmText = "确定",
            )
        }
    }
    if (dialogs.removing) {
        GlassDialog(
            show = true,
            title = "移除《${entry.title}》",
            summary = "会删除本机副本、阅读进度和书签批注，原文件不受影响。",
            onDismissRequest = { dialogs.removing = false },
        ) {
            DialogButtons(
                onDismiss = { dialogs.removing = false },
                onConfirm = {
                    vm.remove(entry)
                    dialogs.removing = false
                    onRemoved()
                },
                confirmText = "移除",
                destructive = true,
            )
        }
    }
}

/** 生成封面的底色：低饱和的书皮色，各自偏一点暖或冷，书架上并排不撞色。 */
private val coverColors = listOf(
    Color(0xFF8A4A3C), Color(0xFF3E5A73), Color(0xFF4E6A4F), Color(0xFF7A6142),
    Color(0xFF5B5274), Color(0xFF3F6B6A), Color(0xFF7B4A5E), Color(0xFF5A5A50),
)

/**
 * 没有封面图时按书名生成一张：布面色块、左边一道书脊、上方衬线书名和一道短线、下方作者。
 * [compact] 是列表里的小封面，只写书名第一个字。
 */
@Composable
fun BookCover(
    title: String,
    author: String,
    modifier: Modifier = Modifier,
    cover: java.io.File? = null,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val color = coverColors[Math.floorMod(title.hashCode(), coverColors.size)]
    val ink = Color(0xFFF6EEDF)
    Card(
        modifier = modifier.fillMaxWidth().aspectRatio(0.7f),
        // 生成的封面照实物书的圆角，比界面的圆角档小。
        cornerRadius = if (compact) 4.dp else 6.dp,
        insideMargin = PaddingValues(0.dp),
        onClick = onClick,
        onLongPress = onLongPress,
        pressFeedbackType = shelfPressFeedback(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(lerp(color, Color.White, 0.08f), lerp(color, Color.Black, 0.12f)))),
        ) {
            if (cover?.isFile == true) {
                CoverImage(cover, Modifier.fillMaxSize())
            } else if (compact) {
                Text(
                    title.take(1),
                    color = ink,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = TypeScale.Title,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column(
                    Modifier.fillMaxSize().padding(start = 16.dp, end = 10.dp, top = 18.dp, bottom = 12.dp),
                ) {
                    Text(
                        title,
                        color = ink,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = TypeScale.Body,
                        lineHeight = TypeScale.BodyLine,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(Modifier.padding(top = 8.dp).width(18.dp).height(1.5.dp).background(ink.copy(alpha = 0.6f)))
                    Spacer(Modifier.weight(1f))
                    if (author.isNotBlank()) {
                        // 封面上的作者名按封面大小定，比界面最小的字还小一号。
                        Text(author, color = ink.copy(alpha = 0.75f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // 书脊：左边一道暗色，紧挨着一线高光，像布面精装书的折口。实物封面上也压一道，书架上看着是一本本书。
            Box(Modifier.width(if (compact) 3.dp else 6.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.22f)))
            Box(Modifier.padding(start = if (compact) 3.dp else 6.dp).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.12f)))
        }
    }
}

@Composable
private fun ImportDialog(files: List<Library.Picked>, vm: AppViewModel) {
    var merge by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf(suggestTitle(files.map { it.name })) }
    val sorted = remember(files) { files.map { it.name }.sortedWith(BookParser.NaturalOrder) }

    GlassDialog(
        show = true,
        title = "导入 ${files.size} 个文件",
        summary = sorted.take(5).joinToString("\n") + if (sorted.size > 5) "\n……" else "",
        onDismissRequest = vm::dismissImport,
    ) {
        ImportModeChoices(files.size, merge) { merge = it }
        if (merge) {
            Text(
                "按章号排序 · 同章以后面的文件为准",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 10.dp),
            )
            TextField(
                value = title,
                onValueChange = { title = it },
                label = "书名",
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
        DialogButtons(
            onDismiss = vm::dismissImport,
            onConfirm = {
                vm.importBooks(files, merge, title)
            },
            confirmText = "导入",
        )
    }
}

/** 纸页移动直接展示导入后的书架结果；重选当前卡片也会重播一次。 */
@Composable
internal fun ImportModeChoices(fileCount: Int, merge: Boolean, onSelect: (Boolean) -> Unit) {
    var replay by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ImportModeChoice(
            combine = true, selected = merge, replay = replay,
            label = "合成 1 本",
            description = "合成一本书：按章号排序，同一章以后面的文件为准",
            modifier = Modifier.weight(1f),
        ) { replay++; onSelect(true) }
        ImportModeChoice(
            combine = false, selected = !merge, replay = replay,
            label = "分别导入",
            description = "分别导入 $fileCount 本书：每个文件成为一本书",
            modifier = Modifier.weight(1f),
        ) { replay++; onSelect(false) }
    }
}

@Composable
private fun ImportModeChoice(
    combine: Boolean,
    selected: Boolean,
    replay: Int,
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = MiuixTheme.colorScheme.primary
    val surface = MiuixTheme.colorScheme.secondaryContainer
    val fill by animateColorAsState(if (selected) lerp(surface, accent, 0.08f) else surface,
        Motion.standard(Motion.SHORT), label = "importModeFill")
    val outline by animateColorAsState(if (selected) accent else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        Motion.standard(Motion.SHORT), label = "importModeOutline")
    val shape = RoundedCornerShape(Radius.Large)
    Column(
        modifier
            .heightIn(min = 112.dp)
            .clip(shape)
            .background(fill)
            .border(if (selected) 2.dp else 1.dp, outline, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ImportPaperPreview(combine, selected, replay)
        Text(label, color = if (selected) accent else MiuixTheme.colorScheme.onSurface,
            fontSize = TypeScale.Body, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun ImportPaperPreview(combine: Boolean, selected: Boolean, replay: Int) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(selected, replay, Effects.noMotion) {
        if (selected && !Effects.noMotion) {
            progress.snapTo(0f)
            progress.animateTo(1f, Motion.standard(Motion.MEDIUM))
        } else progress.snapTo(1f)
    }
    val paper = Color(0xFFF7F0E3)
    val ink = Color(0xFF9A8066)
    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
        repeat(3) { i ->
            val spread = (i - 1) * 31f
            val stacked = (i - 1) * 3f
            val from = if (combine) spread else stacked
            val to = if (combine) stacked else spread
            val x = from + (to - from) * progress.value
            Box(
                Modifier.offset(x = x.dp, y = (i - 1).dp)
                    .width(29.dp).height(43.dp)
                    // 实物封面图的圆角，同生成的封面。
                    .clip(RoundedCornerShape(3.dp))
                    .background(paper)
                    .border(0.5.dp, ink.copy(alpha = 0.28f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(ink.copy(alpha = 0.55f)))
                    Box(Modifier.width(13.dp).height(1.dp).background(ink.copy(alpha = 0.4f)))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(ink.copy(alpha = 0.4f)))
                }
            }
        }
        if (combine) {
            Box(Modifier.offset(x = (-16).dp).width(3.dp).height(45.dp)
                .graphicsLayer { alpha = progress.value }
                .background(ink.copy(alpha = 0.65f)))
        }
    }
}

/** 取文件名的公共前缀作书名，如“已定正文_第1-5章”“已定正文_第6-10章”→“已定正文”。 */
internal fun suggestTitle(names: List<String>): String {
    val titles = names.map(BookParser::titleOf)
    val prefix = titles.reduce { a, b -> a.commonPrefixWith(b) }
        .trimEnd { it.isDigit() || it in " _-—·.、（(第" }
    return prefix.ifEmpty { titles.first() }
}
