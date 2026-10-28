package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import com.xycs.lingyuereader.SummaryProgress
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.ExportFormat
import com.xycs.lingyuereader.WriteAiTask
import com.xycs.lingyuereader.ai.IdeaKind
import com.xycs.lingyuereader.ai.InlineMode
import com.xycs.lingyuereader.ai.WriteAction
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.Beats
import com.xycs.lingyuereader.data.beats
import com.xycs.lingyuereader.data.withBeatDone
import com.xycs.lingyuereader.data.ChapterSyncs
import com.xycs.lingyuereader.data.CheckRules
import com.xycs.lingyuereader.data.AiCliche
import com.xycs.lingyuereader.ai.SuggestKind
import com.xycs.lingyuereader.data.ReferenceAnalysis
import com.xycs.lingyuereader.data.FormatKind
import com.xycs.lingyuereader.data.FormatResult
import com.xycs.lingyuereader.data.FormatSettings
import com.xycs.lingyuereader.data.TextFormat
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.RuleReport
import com.xycs.lingyuereader.data.RuleSet
import com.xycs.lingyuereader.data.Snapshot
import com.xycs.lingyuereader.data.Volume
import com.xycs.lingyuereader.data.WordCount
import com.xycs.lingyuereader.data.Work
import com.xycs.lingyuereader.data.WorkChapter
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassMenu
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.time.LocalDate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import com.xycs.lingyuereader.InlineWrite
import com.xycs.lingyuereader.RealtimeState
import com.xycs.lingyuereader.WriteAiState
import com.xycs.lingyuereader.ai.WriteIssue
import com.xycs.lingyuereader.data.RuleHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 每天码字目标的选项。 */
private val GOALS = listOf(0, 1000, 2000, 3000, 4000, 6000, 8000, 10000)

private fun words(n: Int): String = if (n >= 10_000) "%.1f 万字".format(n / 10_000f) else "$n 字"

/** “刚刚”“5 分钟前”“昨天 21:30”“10月3日”。 */
internal fun whenLabel(at: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - at) / 60_000
    val zone = java.time.ZoneId.systemDefault()
    val then = java.time.Instant.ofEpochMilli(at).atZone(zone)
    val today = LocalDate.now(zone)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        then.toLocalDate() == today -> "今天 %02d:%02d".format(then.hour, then.minute)
        then.toLocalDate() == today.minusDays(1) -> "昨天 %02d:%02d".format(then.hour, then.minute)
        then.year == today.year -> "${then.monthValue}月${then.dayOfMonth}日"
        else -> "${then.year}年${then.monthValue}月${then.dayOfMonth}日"
    }
}

/** 页面上方显示 [AppViewModel.messages] 的提示条。 */
@Composable
internal fun rememberWritingMessages(vm: AppViewModel): SnackbarHostState {
    val snackbar = remember { SnackbarHostState() }
    val message by vm.messages.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }
    return snackbar
}

/**
 * 首页的“写作”：今天写了多少、每天的目标、连续达标天数，下面是作品列表。
 */
@Composable
fun WritingHomeScreen(vm: AppViewModel) {
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val stats by vm.writing.stats.collectAsStateWithLifecycle()
    val loaded by vm.writing.loaded.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val snackbar = rememberWritingMessages(vm)
    val today = LocalDate.now()

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        val scrollBehavior = MiuixScrollBehavior()
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                TopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "写作",
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = { creating = true }, modifier = Modifier.padding(end = 12.dp)) {
                            Icon(LingYueIcons.Add, contentDescription = "新建作品")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = LocalTabBarInset.current)) },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + LocalTabBarInset.current + 24.dp),
                overscrollEffect = null,
            ) {
                item(key = "today") {
                    val written = stats.on(today).coerceAtLeast(0)
                    val streak = stats.streak(today)
                    GroupedSection(
                        footer = when {
                            stats.goal > 0 && streak > 0 -> "连续 $streak 天达标。字数按每天净增算，删掉的字会扣掉。"
                            streak > 0 -> "连续 $streak 天在写。字数按每天净增算，删掉的字会扣掉。"
                            else -> "字数按每天净增算，删掉的字会扣掉；不算空白，标点算字。"
                        },
                    ) {
                        row {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("今天", fontSize = 13.sp, color = c.secondary)
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text("$written", color = c.label, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (stats.goal > 0) " / ${stats.goal} 字" else " 字",
                                        color = c.secondary,
                                        fontSize = 15.sp,
                                        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                                    )
                                }
                                if (stats.goal > 0) {
                                    // 进度条打开时从 0 长到今天写到的地方（和统计页一样）；减少动效时直接画好。
                                    val fraction = animateFromZero((written.toFloat() / stats.goal).coerceIn(0f, 1f))
                                    Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.fill)) {
                                        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(3.dp)).background(c.tint))
                                    }
                                    Text(
                                        if (written >= stats.goal) "今天的目标完成了" else "还差 ${stats.goal - written} 字",
                                        color = c.secondary,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                        row {
                            PickerRow("每天写", GOALS.map { it to if (it == 0) "不设目标" else "$it 字" }, stats.goal) { vm.writing.setGoal(it) }
                        }
                    }
                }
                if (loaded && works.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            LingYueIcons.Write,
                            "还没有作品",
                            "新建一部作品，按卷和章来写。写好的章节可以当书预览、听一遍，也能导出成 TXT 或 EPUB。",
                            modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()),
                            action = "新建作品" to { creating = true },
                        )
                    }
                } else if (works.isNotEmpty()) {
                    // 作品一本一格，连成一张卡片：新建、删除时其余的平滑让位，新的淡入。
                    item(key = "worksHeader") { GroupedListHeader("作品", Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) }
                    items(works, key = { it.id }, contentType = { "work" }) { w ->
                        GroupedListCell(w === works.first(), w === works.last(), Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
                            NavRow(
                                w.title,
                                subtitle = "${w.chapters.size} 章 · ${words(w.words)} · ${whenLabel(w.updatedAt)}编辑",
                            ) { vm.open(Screen.Work(w.id)) }
                        }
                    }
                }
            }
            if (creating) {
                WorkInfoDialog(
                    title = "新建作品",
                    initial = Work(id = "", title = ""),
                    confirm = "新建",
                    onDismiss = { creating = false },
                ) { title, author, _ ->
                    creating = false
                    vm.writing.create(title, author) { vm.open(Screen.Work(it.id)) }
                }
            }
        }
    }
}

/** 新建作品、改作品信息：书名、笔名、简介（新建时不填简介）。 */
@Composable
private fun WorkInfoDialog(
    title: String,
    initial: Work,
    confirm: String,
    withSynopsis: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (title: String, author: String, synopsis: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial.title) }
    var author by remember { mutableStateOf(initial.author) }
    var synopsis by remember { mutableStateOf(initial.synopsis) }
    GlassDialog(show = true, title = title, onDismissRequest = onDismiss) {
        TextField(value = name, onValueChange = { name = it }, label = "书名", singleLine = true, modifier = Modifier.fillMaxWidth())
        TextField(value = author, onValueChange = { author = it }, label = "笔名（可不填）", singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        if (withSynopsis) {
            TextField(
                value = synopsis,
                onValueChange = { synopsis = it },
                label = "简介",
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 96.dp, max = 200.dp),
            )
        }
        DialogButtons(onDismiss = onDismiss, onConfirm = { onConfirm(name, author, synopsis) }, confirmText = confirm, confirmEnabled = name.isNotBlank())
    }
}

/** 返回按钮。 */
@Composable
private fun BackButton(vm: AppViewModel) {
    IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) {
        Icon(LingYueIcons.Back, contentDescription = "返回")
    }
}

/**
 * 一部作品：字数、继续写，按卷列出各章（点开写，长按弹出菜单：改名、状态、细纲卡、挪位置、删除），
 * 右上角菜单里预览、听稿、导出、加卷、改信息、删除。
 *
 * 章节一章一格懒加载（长篇几百章也不卡），同一卷的格子连成一张卡片；新建、删除、挪动时其余的平滑让位，新的淡入、删掉的淡出。
 */
@Composable
fun WorkScreen(vm: AppViewModel, workId: String) {
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val loaded by vm.writing.loaded.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val work = works.firstOrNull { it.id == workId }
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    val assist = assists[workId]
    val packs by vm.writing.packs.collectAsStateWithLifecycle()
    val pack = packs[workId]
    LaunchedEffect(workId) {
        vm.writingAi.loadAssist(workId)
        vm.writing.loadPack(workId)
        vm.writing.loadChanges(workId)
        vm.writing.loadLedger(workId)
    }
    val ledgers by vm.writing.ledgers.collectAsStateWithLifecycle()
    val snackbar = rememberWritingMessages(vm)
    var menu by remember { mutableStateOf(false) }
    var editingInfo by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var addingVolume by remember { mutableStateOf(false) }
    // 长按哪一章 / 点了哪一卷弹出菜单；菜单里选了改名、删除再弹对话框。
    var chapterMenu by remember { mutableStateOf<String?>(null) }
    var volumeMenu by remember { mutableStateOf<String?>(null) }
    var collapsedVolumeIds by rememberWritingTreeIds(workId, "volumes")
    var expandedCardIds by rememberWritingTreeIds(workId, "cards")
    var reorderMode by rememberSaveable(workId) { mutableStateOf(false) }
    var renamingChapter by remember { mutableStateOf<WorkChapter?>(null) }
    var deletingChapter by remember { mutableStateOf<WorkChapter?>(null) }
    var renamingVolume by remember { mutableStateOf<Volume?>(null) }
    var deletingVolume by remember { mutableStateOf<Volume?>(null) }
    var statusFor by remember { mutableStateOf<WorkChapter?>(null) }
    val allChanges by vm.writing.changes.collectAsStateWithLifecycle()
    val pendingChanges = allChanges[workId].orEmpty().count { it.pending }
    // 检查规则有几条（设定包改过就重读）；改状态时顺便看看这一章合不合规则。
    var ruleCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(workId, pack) { ruleCount = vm.writing.rules(workId).rules.size }
    val summaryProgress by vm.writingAi.summaries.collectAsStateWithLifecycle()
    // 整理进设定包：批量整理的进度、哪些章还没整理（正文改过的也算），标为定稿后的提示。
    val syncAll by vm.writingAi.sync.collectAsStateWithLifecycle()
    val syncing = syncAll?.takeIf { it.workId == workId && it.batch }
    val lastSyncing = remember { arrayOfNulls<com.xycs.lingyuereader.SyncSession>(1) }
    if (syncing != null) lastSyncing[0] = syncing
    var unsynced by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(workId, work, assist?.synced) { assist?.let { unsynced = vm.writing.unsynced(workId, it.synced) } }
    var syncConfirm by remember { mutableStateOf(false) }
    var syncTipFor by remember { mutableStateOf<WorkChapter?>(null) }
    var foreshadowNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(workId, pack, ledgers[workId]) {
        val (legacy, latest) = vm.writing.foreshadows(workId)
        val list = mergeWritingForeshadows(legacy, ledgers[workId].orEmpty()).map { it.display }
        val open = list.filter { !it.done }
        val due = open.count { it.payoff != null && it.payoff <= latest }
        foreshadowNote = when {
            list.isEmpty() -> null
            due > 0 -> "$due 条该回收了"
            else -> "${open.size} 条没回收"
        }
    }
    var statusHits by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(statusFor) { statusHits = statusFor?.let { ch -> vm.writing.checkChapter(workId, ch.id)?.hits?.size } }
    // 摘要写完时那一组要收起来：收起的那一下还显示最后一次的进度，不先变空。
    val summary = summaryProgress?.takeIf { it.workId == workId }
    val lastSummary = remember { arrayOfNulls<SummaryProgress>(1) }
    if (summary != null) lastSummary[0] = summary

    // 作品被删了（或从备份恢复时没了）：退回去。
    LaunchedEffect(loaded, work == null) { if (loaded && work == null) vm.back() }
    if (work == null) return

    val launchers = ExportFormat.entries.associateWith { f ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(f.mime)) { uri -> uri?.let { vm.exportWork(workId, f, it) } }
    }

    SubPageScaffold(
        work.title,
        onBack = { vm.back() },
        snackbar = snackbar,
        actions = {
            Box(Modifier.padding(end = 12.dp)) {
                IconButton(onClick = { menu = true }, enabled = !busy) { Icon(LingYueIcons.More, contentDescription = "更多") }
                OverlayListPopup(show = menu, alignment = PopupPositionProvider.Align.End, onDismissRequest = { menu = false }) {
                    GlassMenu {
                        ListPopupColumn {
                            val items = listOf<Pair<String, () -> Unit>>(
                                "当书预览" to { vm.previewWork(workId, null) },
                                "新建一卷" to { addingVolume = true },
                                "作品信息" to { editingInfo = true },
                                "补写章节摘要" to { vm.writingAi.summarizeAll(workId) },
                                "全书排版" to { vm.open(Screen.WorkFormat(workId)) },
                                "排版设置" to { vm.open(Screen.WorkFormatSettings(workId)) },
                                "整理没整理的章节" to {
                                    val blocked = vm.writingAi.syncBlocked
                                    when {
                                        blocked != null -> vm.showMessage(blocked)
                                        unsynced.isEmpty() -> vm.showMessage("有正文的章都整理过了")
                                        else -> syncConfirm = true
                                    }
                                },
                            ) + ExportFormat.entries.map { f -> "导出 ${f.label}" to { launchers.getValue(f).launch("${work.title}.${f.ext}") } } +
                                ("删除作品" to { deleting = true })
                            items.forEach { (label, action) ->
                                LingYueMenuItem(label, selected = false) {
                                    menu = false
                                    action()
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        // 配色在骨架提供的配色里面读。
        val c = LocalGroupedColors.current
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item(key = "head") {
                    // 正在补写摘要：那一组从上面长出来，写完收起。
                    AnimatedVisibility(
                        visible = summary != null,
                        enter = Effects.enter(expandVertically(Motion.standard(Motion.MEDIUM), expandFrom = Alignment.Top) + fadeIn(Motion.standard(Motion.SHORT))),
                        exit = Effects.exit(shrinkVertically(Motion.exit(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())),
                    ) {
                        val p = summary ?: lastSummary[0]
                        if (p != null) {
                            GroupedSection(footer = "写好的摘要存在各章细纲卡里，可以改。写后面的章时，AI 会把前面几章的摘要当前情。") {
                                row { TwoLine("正在写章节摘要 ${p.done + 1}/${p.total}", p.title, trailing = { Spinner() }) }
                                row { ActionRow("停止", destructive = true) { vm.writingAi.stopSummaries() } }
                            }
                        }
                    }
                    // 正在批量整理进设定包：那一组从上面长出来；整理完（或停下）换成“看整理结果”。
                    AnimatedVisibility(
                        visible = syncing != null,
                        enter = Effects.enter(expandVertically(Motion.standard(Motion.MEDIUM), expandFrom = Alignment.Top) + fadeIn(Motion.standard(Motion.SHORT))),
                        exit = Effects.exit(shrinkVertically(Motion.exit(), shrinkTowards = Alignment.Top) + fadeOut(Motion.exit())),
                    ) {
                        val p = syncing ?: lastSyncing[0]
                        if (p != null) {
                            GroupedSection(footer = "每章一次请求，带着设定包；整理完统一勾选，勾上的才写进设定包。") {
                                if (p.running != null) {
                                    row { TwoLine("正在整理进设定包 ${p.done + 1}/${p.total}", p.running, trailing = { Spinner() }) }
                                    row { ActionRow("停止", destructive = true) { vm.writingAi.stopSync() } }
                                } else {
                                    row { NavRow("看整理结果", value = "${p.results.size} 章 · ${p.items.size} 条") { vm.open(Screen.ChapterSync(workId)) } }
                                    row { ActionRow("不要这次的结果", destructive = true) { vm.writingAi.clearSync() } }
                                }
                            }
                        }
                    }
                    WritingWorkOverview(
                        work = work,
                        packCount = pack?.size,
                        ledgerCount = ledgers[workId]?.size,
                        aiAdviceEnabled = assist?.realtime == true,
                        pendingChanges = pendingChanges,
                        unsyncedChapters = unsynced.size,
                        foreshadowNote = foreshadowNote,
                        ruleCount = ruleCount,
                        busy = busy,
                        actions = WritingWorkOverviewActions(
                            openChapter = { ch -> vm.open(Screen.Editor(workId, ch.id)) },
                            newChapter = {
                                work.volumes.firstOrNull()?.let { v ->
                                    vm.writing.addChapter(workId, v.id) { vm.open(Screen.Editor(workId, it.id)) }
                                }
                            },
                            openPack = { vm.open(Screen.WorkPack(workId)) },
                            openLedger = { vm.open(Screen.WorkLedger(workId)) },
                            openAi = { vm.open(Screen.WorkAi(workId)) },
                            preview = { vm.previewWork(workId, null) },
                            openChanges = { vm.open(Screen.WorkChanges(workId)) },
                            openForeshadows = { vm.open(Screen.Foreshadows(workId)) },
                            openRules = { vm.open(Screen.WorkRules(workId)) },
                            organizeUnsynced = {
                                val blocked = vm.writingAi.syncBlocked
                                when {
                                    blocked != null -> vm.showMessage(blocked)
                                    unsynced.isEmpty() -> vm.showMessage("有正文的章都整理过了")
                                    else -> syncConfirm = true
                                }
                            },
                        ),
                    )
                }
                writingPlanningTreeItems(
                    work = work,
                    unsyncedIds = unsynced,
                    showUnsynced = pack?.isNotEmpty() == true,
                    collapsedVolumeIds = collapsedVolumeIds,
                    expandedCardIds = expandedCardIds,
                    reorderMode = reorderMode,
                    volumeMenuId = volumeMenu,
                    chapterMenuId = chapterMenu,
                    actions = WritingTreeActions(
                        onOpenOutline = { vm.open(Screen.WorkPack(workId)) },
                        onToggleVolume = { id ->
                            collapsedVolumeIds = if (id in collapsedVolumeIds) collapsedVolumeIds - id else collapsedVolumeIds + id
                        },
                        onOpenVolumeMenu = { volumeMenu = it.id },
                        onDismissVolumeMenu = { volumeMenu = null },
                        onRenameVolume = { renamingVolume = it },
                        onDeleteVolume = { deletingVolume = it },
                        onOpenChapter = { vm.open(Screen.Editor(workId, it.id)) },
                        onToggleCard = { id ->
                            expandedCardIds = if (id in expandedCardIds) expandedCardIds - id else expandedCardIds + id
                        },
                        onOpenCard = { vm.open(Screen.ChapterCard(workId, it.id)) },
                        onOpenChapterMenu = { chapterMenu = it.id },
                        onDismissChapterMenu = { chapterMenu = null },
                        onRenameChapter = { renamingChapter = it },
                        onStatusChapter = { statusFor = it },
                        onMoveChapter = { ch, delta -> vm.writing.moveChapter(workId, ch.id, delta) },
                        onDeleteChapter = { deletingChapter = it },
                        onAddChapter = { v ->
                            vm.writing.addChapter(workId, v.id) { vm.open(Screen.Editor(workId, it.id)) }
                        },
                        onToggleReorder = { reorderMode = !reorderMode },
                    ),
                )
            }
            // 预览、导出时的忙碌条浮在标题栏下面，不占地方：出现、消失时页面不被往下顶。
            AnimatedVisibility(
                visible = busy,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
                enter = Effects.enter(fadeIn(Motion.standard(Motion.SHORT))),
                exit = Effects.exit(fadeOut(Motion.exit())),
            ) {
                top.yukonga.miuix.kmp.basic.LinearProgressIndicator(Modifier.padding(horizontal = 32.dp, vertical = 4.dp))
            }
        }
        if (editingInfo) {
            WorkInfoDialog("作品信息", work, "保存", withSynopsis = true, onDismiss = { editingInfo = false }) { t, a, s ->
                editingInfo = false
                vm.writing.updateInfo(workId, t, a, s)
            }
        }
        if (addingVolume) {
            NameDialog("新建一卷", "第${work.volumes.size + 1}卷", "新建", onDismiss = { addingVolume = false }) {
                addingVolume = false
                vm.writing.addVolume(workId, it)
            }
        }
        if (deleting) {
            GlassDialog(
                show = true,
                title = "删除《${work.title}》",
                summary = "作品的全部章节和历史版本都会删除，不能恢复。要留底的话先导出一份。",
                onDismissRequest = { deleting = false },
            ) {
                DialogButtons(
                    onDismiss = { deleting = false },
                    onConfirm = {
                        deleting = false
                        vm.writing.delete(workId)
                    },
                    confirmText = "删除",
                    destructive = true,
                )
            }
        }
        renamingChapter?.let { ch ->
            NameDialog("章名", ch.title, "好", onDismiss = { renamingChapter = null }) { name ->
                renamingChapter = null
                if (name.trim() != ch.title) vm.writing.renameChapter(workId, ch.id, name)
            }
        }
        deletingChapter?.let { ch ->
            GlassDialog(
                show = true,
                title = "删除“${ch.title}”",
                summary = (if (ch.locked) "这一章已定稿。" else "") + "这一章（${words(ch.words)}）会从作品里删除。有正文时仅留在本机历史文件中，应用内暂不能直接找回。",
                onDismissRequest = { deletingChapter = null },
            ) {
                DialogButtons(
                    onDismiss = { deletingChapter = null },
                    onConfirm = {
                        deletingChapter = null
                        vm.writing.deleteChapter(workId, ch.id)
                    },
                    confirmText = "删除",
                    destructive = true,
                )
            }
        }
        statusFor?.let { ch ->
            StatusDialog(ch, ruleHits = statusHits, onDismiss = { statusFor = null }) { st, reason, canon ->
                statusFor = null
                vm.writing.setStatus(workId, ch.id, st, reason, canon)
                if (st == ChapterStatus.FINAL && assist?.syncTip != false && vm.writingAi.syncBlocked == null && ch.id in unsynced) syncTipFor = ch
            }
        }
        syncTipFor?.let { ch ->
            SyncTipDialog(
                onDismiss = { never ->
                    syncTipFor = null
                    if (never) vm.writingAi.updateAssist(workId) { it.copy(syncTip = false) }
                },
                onSync = { never ->
                    syncTipFor = null
                    if (never) vm.writingAi.updateAssist(workId) { it.copy(syncTip = false) }
                    vm.writingAi.syncChapter(workId, ch.id)
                    vm.open(Screen.ChapterSync(workId))
                },
            )
        }
        if (syncConfirm) {
            GlassDialog(
                show = true,
                title = "整理没整理的章节",
                summary = "有 ${unsynced.size} 章还没整理（整理后正文改过的也算）。按目录顺序每章发一次请求，带着设定包；" +
                    "整理完统一勾选再写进设定包，中途可以停。",
                onDismissRequest = { syncConfirm = false },
            ) {
                DialogButtons(
                    onDismiss = { syncConfirm = false },
                    onConfirm = {
                        syncConfirm = false
                        vm.writingAi.syncAll(workId)
                    },
                    confirmText = "开始",
                )
            }
        }
        renamingVolume?.let { v ->
            NameDialog("卷名", v.title, "好", onDismiss = { renamingVolume = null }) { name ->
                renamingVolume = null
                if (name.trim() != v.title) vm.writing.renameVolume(workId, v.id, name)
            }
        }
        deletingVolume?.let { v ->
            GlassDialog(
                show = true,
                title = "删除“${v.title}”",
                summary = "这一卷和卷里的 ${v.chapters.size} 章都会删除。",
                onDismissRequest = { deletingVolume = null },
            ) {
                DialogButtons(
                    onDismiss = { deletingVolume = null },
                    onConfirm = {
                        deletingVolume = null
                        vm.writing.deleteVolume(workId, v.id)
                    },
                    confirmText = "删除",
                    destructive = true,
                )
            }
        }
    }
}

/** 弹出菜单里的一项。[color] 在调用处读好传进来（菜单画在最外层的弹层宿主里，读不到设置页配色）；删除类传删除色。 */
@Composable
private fun MenuAction(label: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .widthIn(min = 200.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = color, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
    }
}

/**
 * 卷名一行：名字，右边章数和字数，最右“⋯”；点了弹出菜单（改名；不止一卷时还能删除，红色放最后）。
 * 字号、行高照 [NavRow]。
 */
@Composable
private fun VolumeRow(
    v: Volume,
    showMenu: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalGroupedColors.current
    Box {
        GroupedRow(onClick = onClick) {
            Text(v.title, color = c.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${v.chapters.size} 章 · ${words(v.chapters.sumOf { it.words })}", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
            Icon(LingYueIcons.More, contentDescription = "卷设置", tint = c.tertiary, modifier = Modifier.padding(start = 4.dp).size(16.dp))
        }
        OverlayListPopup(show = showMenu, alignment = PopupPositionProvider.Align.End, onDismissRequest = onDismissMenu) {
            GlassMenu {
                ListPopupColumn {
                    MenuAction("改名", c.label) { onDismissMenu(); onRename() }
                    if (canDelete) MenuAction("删除本卷", c.destructive) { onDismissMenu(); onDelete() }
                }
            }
        }
    }
}

/**
 * 一章一行：章名，右边字数和状态小标签、小箭头；点开写，长按弹出菜单：
 * 改名、状态、细纲卡、上移、下移，删除单独放最后、红色（还要再确认）。
 */
@Composable
private fun ChapterRow(
    ch: WorkChapter,
    unsynced: Boolean,
    showMenu: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onStatus: () -> Unit,
    onCard: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalGroupedColors.current
    Box {
        NavRow(
            ch.title,
            // 有正文、还没整理进设定包的（整理后又改过的也算）：字数后面写“未整理”。
            value = if (ch.words == 0) "空" else words(ch.words) + if (unsynced) " · 未整理" else "",
            trailing = { StatusLabel(ch.status) },
            onLongClick = onLongClick,
            onClick = onClick,
        )
        OverlayListPopup(show = showMenu, alignment = PopupPositionProvider.Align.End, onDismissRequest = onDismissMenu) {
            GlassMenu {
                ListPopupColumn {
                    MenuAction("改名", c.label) { onDismissMenu(); onRename() }
                    MenuAction("状态：${ch.status.label}", c.label) { onDismissMenu(); onStatus() }
                    MenuAction("细纲卡", c.label) { onDismissMenu(); onCard() }
                    MenuAction("上移", c.label) { onDismissMenu(); onMove(-1) }
                    MenuAction("下移", c.label) { onDismissMenu(); onMove(1) }
                    MenuAction("删除", c.destructive) { onDismissMenu(); onDelete() }
                }
            }
        }
    }
}

/** 一个名字的输入框（新建卷、改名）。 */
@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    GlassDialog(show = true, title = title, onDismissRequest = onDismiss) {
        TextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        DialogButtons(onDismiss = onDismiss, onConfirm = { onConfirm(name) }, confirmText = confirm, confirmEnabled = name.isNotBlank())
    }
}

/** 键盘上方一排常用的中文标点：成对的引号插进去后光标停在中间。 */
private val QUICK_INSERTS = listOf("「」", "“”", "『』", "……", "——", "，", "。", "？", "！", "：", "、")

/**
 * 写一章：上方章名和字数，中间正文（一行一段），键盘上方一排常用标点。
 * 停笔不到一秒自动保存，离开这一章、切到后台时也存；每写十分钟和每次离开存一个历史版本。
 */
@OptIn(FlowPreview::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(vm: AppViewModel, workId: String, chapterId: String, check: Boolean = false) {
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val work = works.firstOrNull { it.id == workId }
    val chapter = work?.chapter(chapterId)
    val snackbar = rememberWritingMessages(vm)
    var state by remember(workId, chapterId) { mutableStateOf<TextFieldState?>(null) }
    var savedText by remember(workId, chapterId) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    // 旧 AI / 标点操作仍可切换；平时显示更省空间的上下文推荐。
    var aiBar by rememberSaveable { mutableStateOf(false) }
    var punctuationBar by rememberSaveable { mutableStateOf(false) }
    var toolsOpen by remember(workId, chapterId) { mutableStateOf(false) }
    var toolGroup by remember(workId, chapterId) { mutableStateOf(EditorToolGroup.WRITE) }
    var asking by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var issuesOpen by remember { mutableStateOf(false) }
    var companionOpen by rememberSaveable(workId, chapterId) { mutableStateOf(false) }
    var companionChoosing by remember(workId, chapterId) { mutableStateOf(false) }
    var companionAnalyses by remember(workId, chapterId) { mutableStateOf<List<ReferenceAnalysis>>(emptyList()) }
    var companionSelection by remember(workId, chapterId) { mutableStateOf(vm.companionReference(workId, chapterId)) }
    val chosenCompanionAnalysis = companionAnalyses.firstOrNull { it.id == companionSelection }
    val aiState by vm.writingAi.state.collectAsStateWithLifecycle()
    val aiLookup by vm.writingAi.lookup.collectAsStateWithLifecycle()
    // AI 在编辑器里写的那一段（这一章的才显示）；写的时候正文只读。
    val inlineAll by vm.writingAi.inline.collectAsStateWithLifecycle()
    val inline = inlineAll?.takeIf { it.workId == workId && it.chapterId == chapterId }
    // “AI 写”面板：在光标处让 AI 写正文只从这里进（AI 条第一项、智能推荐）。
    var aiWriteOpen by remember { mutableStateOf(false) }
    // 书灵里选了范本时，“AI 写”面板和推荐的“写第 N 拍”带不带它；默认带，作者在面板里关了这一章就不带。
    var useReference by remember(workId, chapterId) { mutableStateOf(true) }
    // 本章细纲卡：“AI 写”面板列节拍、推荐“写第 N 拍”要知道有哪几拍、哪几拍写过。进来读一次，
    // 打开菜单、打开面板、AI 写完（采纳或撤回）再读，细纲卡页改过也跟得上。
    var beatCard by remember(workId, chapterId) { mutableStateOf<ChapterCard?>(null) }
    LaunchedEffect(workId, chapterId, menu, aiWriteOpen, inline == null) {
        try { beatCard = mergeBeatsDone(vm.writing.cards(workId, chapterId).first.card, beatCard) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { }
    }
    val realtime by vm.writingAi.realtime.collectAsStateWithLifecycle()
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val locked = chapter?.locked == true
    // 定稿锁定、AI 在写（或写完等采纳）时正文只读：快捷标点、撤销重做也不能改。
    val readOnly = locked || inline != null
    var statusDialog by remember { mutableStateOf(false) }
    // 标为定稿以后问一句“要不要顺手整理进设定包”。
    var syncTip by remember { mutableStateOf(false) }
    // 规则检查：规则从设定包读（文档改过就重读），正文停笔 0.4 秒重新检查，都在本机算。
    var rulesOpen by remember(workId, chapterId) { mutableStateOf(check) }
    val packs by vm.writing.packs.collectAsStateWithLifecycle()
    var ruleSet by remember(workId) { mutableStateOf<RuleSet?>(null) }
    LaunchedEffect(workId) { vm.writing.loadPack(workId) }
    LaunchedEffect(workId, packs[workId]) { ruleSet = vm.writing.rules(workId) }
    var report by remember(workId, chapterId) { mutableStateOf<RuleReport?>(null) }
    // 点同一条，依次选中它在正文里的下一处。
    var lastJump by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // 定稿的章不给写作建议（反正不能改）。
    val realtimeOn = assists[workId]?.realtime == true && aiConfig.ready && !locked
    // 正文里显示的建议：写作建议（开了才有）加上作者点的去 AI 腔（写作建议关着也显示）；别的章、定稿的章不显示。
    val shownRealtime = remember(realtime, realtimeOn, chapterId, locked) {
        if (locked || realtime.chapterId != chapterId) RealtimeState()
        else realtime.copy(issues = realtime.issues.filter { realtimeOn || it.kind == SuggestKind.CLICHE.name })
    }
    val clicheOn = shownRealtime.cliche || shownRealtime.clicheError != null || shownRealtime.clicheNote != null ||
        shownRealtime.issues.any { it.kind == SuggestKind.CLICHE.name }
    LaunchedEffect(workId) { vm.writingAi.loadAssist(workId) }
    // 自动排版：先在键盘上方给预览（按类勾掉的不排），排好作为一步编辑写进正文。
    val formatSettings = assists[workId]?.format ?: FormatSettings()
    var formatOpen by remember(workId, chapterId) { mutableStateOf(false) }
    var formatSkip by remember(workId, chapterId) { mutableStateOf(emptySet<FormatKind>()) }
    // 定稿了、AI 开始写了：收起排版预览（这时不能排）。
    LaunchedEffect(locked, inline != null) { if (locked || inline != null) formatOpen = false }
    LaunchedEffect(inline, aiState) {
        if (inline != null || aiState?.let { it.workId == workId && it.chapterId == chapterId } == true) toolsOpen = false
    }

    LaunchedEffect(workId, chapterId) {
        val text = vm.writing.open(workId, chapterId)
        savedText = text
        // 写作建议只查打开以后改过、新写的段：已有的段落算查过。
        vm.writingAi.baseline(workId, chapterId, text)
        state = TextFieldState(text).apply { edit { placeCursorAtEnd() } }
    }
    LaunchedEffect(workId, chapterId) {
        try { companionAnalyses = vm.writing.referenceAnalyses(workId, chapterId) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { vm.showMessage("读取范本分析失败：${e.message}") }
    }
    val current = state
    // 自动保存：停笔 800 毫秒存一次。
    if (current != null) {
        LaunchedEffect(current) {
            snapshotFlow { current.text.toString() }.drop(1).debounce(800).collect { text ->
                vm.writing.save(workId, chapterId, text, onSaved = { savedText = text })
            }
        }
    }
    // 写作建议（作品里开了才有）：停笔 3 秒，把改过的段落发给 AI 看。
    if (current != null && realtimeOn) {
        LaunchedEffect(current, realtimeOn) {
            snapshotFlow { current.text.toString() }.debounce(3_000).collect { text ->
                vm.writingAi.checkRealtime(workId, chapterId, text)
            }
        }
    }
    // 计划在这一章回收的伏笔：编辑器上方提醒一句。
    var due by remember(workId, chapterId) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(workId, chapterId, packs[workId]) { due = vm.writing.brief(workId, chapterId)?.due?.map { it.what }.orEmpty() }
    // 开了“查 AI 腔”时内置清单、还有我的黑名单，和设定包的检查规则合在一起查（命中照样标在正文里，来源分开显示）。
    val assistNow = assists[workId]
    val voiceRules = remember(assistNow?.aiCliche, assistNow?.banned) { AiCliche.rules(assistNow) }
    val rules = ruleSet?.let { it + voiceRules }
    LaunchedEffect(current, rules) {
        if (current == null || rules == null || rules.isEmpty) {
            report = null
            return@LaunchedEffect
        }
        snapshotFlow { current.text.toString() }.debounce(400).collectLatest { text ->
            report = withContext(Dispatchers.Default) { CheckRules.check(text, rules) }
        }
    }
    val latest by rememberUpdatedState(current)
    // 切到后台（可能被系统杀掉）时马上存。
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        latest?.text?.toString()?.let { vm.writing.save(workId, chapterId, it) }
    }
    // 离开这一章：存，并存一个历史版本。
    DisposableEffect(workId, chapterId) {
        onDispose {
            latest?.text?.toString()?.let { vm.writing.save(workId, chapterId, it, closing = true) }
            vm.writingAi.stopRealtime()
            // 只停下、收起：作者没看完就离开（或旋转屏幕），不算“不采纳”。
            if (vm.writingAi.state.value?.chapterId == chapterId) vm.writingAi.dismiss()
        }
    }

    /** 跑一个 AI 任务：用编辑框里现在的全文（可能还没存盘）。 */
    fun runAi(task: WriteAiTask) {
        val st = current ?: return
        issuesOpen = false
        vm.writingAi.run(workId, chapterId, st.text.toString(), task)
    }

    /** 改稿类：选中的文字或光标所在的段。 */
    fun runEdit(action: WriteAction, instruction: String = "", reviewFix: com.xycs.lingyuereader.ReviewFix? = null) {
        val st = current ?: return
        if (locked) {
            vm.showMessage("这一章已定稿：要改先解锁并登记原因")
            return
        }
        val target = editTarget(st.text.toString(), st.selection, action, instruction)
        if (target == null) vm.showMessage(if (action == WriteAction.CONTINUE) "先写一点，再让 AI 接着写" else "先选中文字，或把光标放在要改的那段")
        else runAi(target.copy(reviewFix = reviewFix))
    }

    /** 从光标处让 AI 接着写（写的字先显示在光标处，采纳才进正文）。 */
    fun startInline(mode: InlineMode, instruction: String = "", referenceAnalysisId: String? = null, beat: Int? = null, variants: Boolean = false) {
        val st = current ?: return
        if (locked) {
            vm.showMessage("这一章已定稿：要改先解锁并登记原因")
            return
        }
        val text = st.text.toString()
        val at = st.selection.max
        vm.writingAi.writeInline(workId, chapterId, text, at, mode, instruction, referenceAnalysisId, beat, variants)
    }

    /** 按“AI 写”面板选好的写；开着“照范本写法”且书灵里选的范本还在时带上范本（各种写法都能带，写完照样核验）。 */
    fun writeChoice(choice: AiWriteChoice, instruction: String = "") {
        val reference = chosenCompanionAnalysis?.id?.takeIf { choice.reference }
        startInline(choice.mode, instruction, referenceAnalysisId = reference, beat = choice.beat, variants = choice.variants)
    }

    /** 打开“AI 写”面板。 */
    fun openAiWrite() {
        if (locked) vm.showMessage("这一章已定稿：要改先解锁并登记原因") else aiWriteOpen = true
    }

    /**
     * 推荐的“写第 N 拍”：不弹面板，按细纲卡现在的样子写第一拍没写的；3 个方向按作品记住的，范本按开关。
     * 先重读一次细纲卡（细纲卡页可能改过节拍）。
     */
    fun writeNextBeat(scope: CoroutineScope) {
        if (locked) {
            vm.showMessage("这一章已定稿：要改先解锁并登记原因")
            return
        }
        scope.launch {
            val disk = try { vm.writing.cards(workId, chapterId).first.card }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
            val card = disk?.let { mergeBeatsDone(it, beatCard) } ?: beatCard
            beatCard = card
            val n = card?.let { Beats.firstUndone(it) }
            if (n == null) vm.showMessage("细纲卡的节拍都写过了")
            else writeChoice(AiWriteChoice(InlineMode.BEAT, n, variants = assists[workId]?.beatVariants == true,
                reference = chosenCompanionAnalysis != null && useReference))
        }
    }

    /** 书灵里问主意：起名、随便问先问一句，别的直接问；结果在 AI 面板里看，可以插到光标处。 */
    fun askIdea(kind: IdeaKind) {
        companionOpen = false
        when (kind) {
            IdeaKind.NAMES -> asking = "给什么起名？" to { what -> runAi(WriteAiTask.Idea(kind, what)) }
            IdeaKind.ASK -> asking = "想问什么？" to { q -> runAi(WriteAiTask.Idea(kind, q)) }
            else -> runAi(WriteAiTask.Idea(kind))
        }
    }

    /** 预览、审稿、草稿页要使用这一刻的编辑框，而不是等 800ms 自动保存。 */
    fun saveNow() { current?.text?.toString()?.let { vm.writing.save(workId, chapterId, it) } }

    /** 整理本章进设定包：用编辑框里现在的正文；同一份正文刚整理好（单章或批量里的）、还没写进去的，直接看结果。 */
    fun startSync() {
        val text = current?.text?.toString() ?: return
        if (text.isBlank()) {
            vm.showMessage("这一章还没有内容")
            return
        }
        vm.writingAi.syncBlocked?.let {
            vm.showMessage(it)
            return
        }
        if (!locked) vm.writing.save(workId, chapterId, text)
        val s = vm.writingAi.sync.value
        val done = s != null && s.workId == workId && s.running == null &&
            s.results.any { it.chapterId == chapterId && it.hash == ChapterSyncs.hash(text) && it.error == null }
        if (!done) vm.writingAi.syncChapter(workId, chapterId, text)
        vm.open(Screen.ChapterSync(workId))
    }

    /** 标为定稿时要不要问：作品里没关提示、AI 能整理、这一章有正文且还没整理过。 */
    fun wantsSyncTip(): Boolean {
        val text = current?.text?.toString().orEmpty()
        val assist = assists[workId]
        return assist?.syncTip != false && vm.writingAi.syncBlocked == null && text.isNotBlank() &&
            !ChapterSyncs.isSynced(assist?.synced?.get(chapterId), text)
    }

    /** 采纳一条建议：换掉正文里那几个字；去 AI 腔的还记进采纳过的修改，以后当例子学。 */
    fun fixIssue(st: TextFieldState, issue: WriteIssue) {
        if (!applyIssue(st, issue)) vm.showMessage("那段已经改动了")
        else if (issue.kind == SuggestKind.CLICHE.name) vm.writingAi.acceptCliche(workId, issue)
        vm.writingAi.dismissIssue(issue)
    }

    /** 去 AI 腔：按现在的正文重新数一遍标出的 AI 腔、黑名单（停笔检查可能还没跟上），把那几段发给 AI。 */
    fun startCliche() {
        val st = current ?: return
        if (locked) {
            vm.showMessage("这一章已定稿：要改先解锁并登记原因")
            return
        }
        val text = st.text.toString()
        val ranges = CheckRules.check(text, voiceRules).hits.filter(AiCliche::isVoice).flatMap { it.ranges }
        if (ranges.isNotEmpty() && vm.writingAi.ready) {
            rulesOpen = false
            issuesOpen = true
        }
        vm.writingAi.decliche(workId, chapterId, text, ranges)
    }

    fun onAiItem(item: AiBarItem) {
        val st = current ?: return
        when (item) {
            AiBarItem.Cliche -> startCliche()
            AiBarItem.Write -> openAiWrite()
            is AiBarItem.Edit -> runEdit(item.action)
            AiBarItem.Custom -> {
                if (editTarget(st.text.toString(), st.selection, WriteAction.CUSTOM) == null) vm.showMessage("先选中文字，或把光标放在要改的那段")
                else asking = "想怎么改？" to { req -> runEdit(WriteAction.CUSTOM, req) }
            }
            AiBarItem.Review, AiBarItem.Consistency ->
                if (st.text.isBlank()) vm.showMessage("这一章还没有内容")
                else runAi(if (item == AiBarItem.Review) WriteAiTask.Review else WriteAiTask.Consistency)
        }
    }

    val chapters = work?.chapters.orEmpty()
    val index = chapters.indexOfFirst { it.id == chapterId }
    val currentAi = aiState?.takeIf { it.workId == workId && it.chapterId == chapterId }
    val pendingAi = inline != null || currentAi != null
    val recommendation = editorRecommendation(
        text = current?.text ?: "",
        selection = current?.selection ?: TextRange(0),
        status = if (locked) ChapterStatus.FINAL else chapter?.status,
        aiReady = aiConfig.ready,
        inline = inline,
        aiState = currentAi,
        nextBeat = beatCard?.let { card -> Beats.firstUndone(card)?.let { n -> beats(card).firstOrNull { it.index == n } } },
    )
    val scheme = MiuixTheme.colorScheme

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = scheme.surface,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().glassBar().statusBarsPadding().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton(vm)
                    Column(
                        Modifier.weight(1f).padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp)).combinedClickableCompat { renaming = true },
                    ) {
                        Text(chapter?.title.orEmpty(), color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val text = current?.text
                        val count = remember(text) { text?.let { WordCount.of(it.toString()) } ?: 0 }
                        val head = (chapter?.status?.label?.let { "$it · " } ?: "") + "$count 字 · "
                        val saving = if (locked) "已锁定" else if (text == null) "载入中" else if (text.toString() == savedText) "已保存" else "编辑中"
                        // 作者自己的规则按条数，AI 腔按标出的处数，分开写：一眼看出哪些是 AI 腔。
                        val ruleCount = report?.hits?.count { !AiCliche.isVoice(it) } ?: 0
                        val clicheCount = report?.hits?.let(AiCliche::count) ?: 0
                        val tail = (if (ruleCount > 0) " · $ruleCount 条不合规则" else "") + (if (clicheCount > 0) " · $clicheCount 处 AI 腔" else "") +
                            if (realtimeOn) " · 写作建议已开" else ""
                        // 保存状态“编辑中 / 已保存”只淡入淡出（[Motion.SHORT]），不挪动别的字（几种状态都是三个字宽）；读屏照旧念一整行。
                        Row(Modifier.clearAndSetSemantics { this.text = AnnotatedString(head + saving + tail) }) {
                            Text(head, color = c.secondary, fontSize = 12.sp, maxLines = 1)
                            AnimatedContent(
                                saving,
                                transitionSpec = {
                                    ContentTransform(Effects.enter(fadeIn(Motion.standard(Motion.SHORT))), Effects.exit(fadeOut(Motion.exit())), sizeTransform = null)
                                },
                                label = "saveState",
                            ) { Text(it, color = c.secondary, fontSize = 12.sp, maxLines = 1) }
                            Text(tail, color = c.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        }
                    }
                    IconButton(onClick = { if (!readOnly) current?.undoState?.undo() }, enabled = !readOnly && current?.undoState?.canUndo == true) {
                        Icon(LingYueIcons.Undo, contentDescription = "撤销")
                    }
                    IconButton(onClick = { if (!readOnly) current?.undoState?.redo() }, enabled = !readOnly && current?.undoState?.canRedo == true) {
                        Icon(LingYueIcons.Redo, contentDescription = "重做")
                    }
                    Box(Modifier.padding(end = 8.dp)) {
                        IconButton(onClick = { menu = true }) { Icon(LingYueIcons.More, contentDescription = "更多") }
                        OverlayListPopup(show = menu, alignment = PopupPositionProvider.Align.End, onDismissRequest = { menu = false }) {
                            GlassMenu {
                                ListPopupColumn {
                                    // 预览、听稿要用刚打的字：不等自动保存，先存一次（排在预览前面落盘）。
                                    val items = buildList<Pair<String, () -> Unit>> {
                                        // 常用的在前：这一章的细纲和状态，再是 AI，最后是预览、历史、改名。
                                        add("细纲卡" to { vm.open(Screen.ChapterCard(workId, chapterId)) })
                                        add((if (locked) "解锁修改" else "章节状态") to { statusDialog = true })
                                        // 在光标处写（接着写、写这一拍、写到本章结尾）都在“AI 写”面板里，这里只留进草稿页的入口。
                                        add("AI 写整章草稿" to { saveNow(); vm.open(Screen.ChapterDraft(workId, chapterId)) })
                                        add("三道审稿" to { saveNow(); vm.open(Screen.ChapterReview(workId, chapterId)) })
                                        add("整理本章进设定包" to { startSync() })
                                        add("本章资料" to { vm.open(Screen.ChapterBrief(workId, chapterId)) })
                                        add("故事账本" to { vm.open(Screen.WorkLedger(workId, chapterId)) })
                                        add("从范本找写法" to { saveNow(); vm.open(Screen.WritingReference(workId, chapterId)) })
                                        add("规则检查" to { rulesOpen = true })
                                        add("自动排版" to {
                                            when {
                                                locked -> vm.showMessage("这一章已定稿：要排版先解锁")
                                                inline != null -> vm.showMessage("AI 写的还没采纳或撤回，先处理它再排版")
                                                else -> formatOpen = true
                                            }
                                        })
                                        // 先把现在的正文存成一版，历史列表里就有“刚才”这一版。
                                        add("历史版本" to {
                                            current?.text?.toString()?.let { vm.writing.save(workId, chapterId, it, closing = true) }
                                            vm.open(Screen.WorkHistory(workId, chapterId))
                                        })
                                        add("预览本章" to { saveNow(); vm.previewWork(workId, chapterId) })
                                        add("听本章" to { saveNow(); vm.previewWork(workId, chapterId, listen = true) })
                                        add("改章名" to { renaming = true })
                                        chapters.getOrNull(index - 1)?.let { p -> add("上一章" to { vm.replaceTop(Screen.Editor(workId, p.id)) }) }
                                        chapters.getOrNull(index + 1)?.let { n -> add("下一章" to { vm.replaceTop(Screen.Editor(workId, n.id)) }) }
                                        if (index == chapters.lastIndex && index >= 0) {
                                            val volume = work?.volumeOf(chapterId)
                                            if (volume != null) add("写下一章" to { vm.writing.addChapter(workId, volume.id) { vm.replaceTop(Screen.Editor(workId, it.id)) } })
                                        }
                                    }
                                    items.forEach { (label, action) ->
                                        LingYueMenuItem(label, selected = false) {
                                            menu = false
                                            action()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
                val focus = remember { FocusRequester() }
                // 从审稿页点回来：选中那一句。找不到整句（可能改过了）只提示，不按开头几个字猜，免得选中、改到别处。
                if (current != null) {
                    LaunchedEffect(current) {
                        vm.writing.jump.collect { j ->
                            if (j == null || j.chapterId != chapterId) return@collect
                            vm.writing.jump.value = null
                            val text = current.text.toString()
                            val quote = j.quote
                            val at = text.indexOf(quote).takeIf { quote.isNotEmpty() && it >= 0 }
                            if (at == null) {
                                vm.showMessage("正文里没找到这一句，可能已经改过了" + if (j.instruction != null) "；没有让 AI 改" else "")
                            } else {
                                current.edit { selection = TextRange(at, at + minOf(quote.length, text.length - at)) }
                                runCatching { focus.requestFocus() }
                                // “按这条改”：选中的这句交给 AI 按意见改，结果照常先看对比再采纳。
                                j.instruction?.let { runEdit(WriteAction.CUSTOM, it, j.reviewFix) }
                            }
                        }
                    }
                }

                // 正文里标出来的：写作建议（按类上色、划线）、不合检查规则的（提醒色底）。只是显示，不改正文。
                // 一次检查出来的标记不整片冒出来、整片消失：新的淡入，去掉的在原处淡掉。
                val effects = rememberCoroutineScope()
                val accepted = remember(current) { AcceptSettle() }
                val textNow = current?.text?.toString().orEmpty()
                val issueSpans = remember(textNow, shownRealtime.issues) { locateIssues(textNow, shownRealtime.issues) }
                val ruleSpans = remember(textNow, report) {
                    report?.hits.orEmpty().flatMap { h -> h.ranges.filter { it.last < textNow.length }.map { it to h } }.take(300)
                }
                val marks = remember(issueSpans, ruleSpans, c) {
                    // 按“哪条建议”“哪条规则的第几处”认，不按位置：正文一改位置就变，淡入淡出不能跟着乱。
                    val nth = HashMap<String, Int>()
                    val ruleBack = c.warning.copy(alpha = 0.2f)
                    ruleSpans.map { (r, h) -> TextMark(h.key to nth.merge(h.key, 1) { a, b -> a + b }, r.first, r.last + 1, ruleBack) } +
                        issueSpans.map { (r, i) ->
                            val color = suggestColor(i.kind, c)
                            TextMark(i, r.first, r.last + 1, color.copy(alpha = 0.14f), line = suggestLine(i.kind), lineColor = color)
                        }
                }
                val markFades = remember(current) { MarkFades(effects) }
                SideEffect { markFades.update(marks) }
                // 排版预览：正文一改就重算（在后台算，长章也不卡）。
                val formatResult by androidx.compose.runtime.produceState<FormatResult?>(null, formatOpen, textNow, formatSettings) {
                    if (formatOpen) value = withContext(Dispatchers.Default) { TextFormat.format(textNow, formatSettings) }
                }
                // 输入时自动排版：定稿、只读时不换。
                val formatNow by rememberUpdatedState(formatSettings.takeIf { !readOnly })
                val typing = remember(current) { current?.let { st -> TypingFormatInput(st, effects) { formatNow } } }
                // AI 写的字：新来的一截淡入；采纳后那一段从浅色过渡到正文色。
                // 出几个版本时换一版：浅色预览整段重新淡入。
                val inlineFade = remember(current, inline?.selected) { InlineFade(effects) }
                val inlineLength = inline?.text?.length ?: 0
                SideEffect { inlineFade.update(inlineLength) }
                val pendingStyle = SpanStyle(color = scheme.primary, background = scheme.primary.copy(alpha = 0.08f))
                val inlineNow by rememberUpdatedState(inline)
                val pendingNow by rememberUpdatedState(pendingStyle)
                val bodyNow by rememberUpdatedState(scheme.onSurface)
                val output = remember(current) {
                    OutputTransformation {
                        val d = inlineNow
                        // 采纳那一下：正文已经插好，AI 那段的状态还没收起，不能再叠一遍（写的时候正文只读，长度对得上才是还没采纳）。
                        if (d != null && d.text.isNotEmpty() && d.at <= length && length == d.base.length) {
                            replace(d.at, d.at, d.text)
                            addStyle(pendingNow, d.at, d.at + d.text.length)
                            inlineFade.forEachFading(d.text.length) { from, to, alpha ->
                                addStyle(SpanStyle(color = pendingNow.color.copy(alpha = pendingNow.color.alpha * alpha)), d.at + from, d.at + to)
                            }
                        } else {
                            markFades.forEach { m, alpha -> if (alpha > 0f && m.start in 0 until m.end && m.end <= length) addStyle(m.style(alpha), m.start, m.end) }
                            accepted.range?.let { r ->
                                val start = r.first.coerceIn(0, length)
                                val end = (r.last + 1).coerceIn(start, length)
                                val f = accepted.progress.value
                                if (start < end && f < 1f) {
                                    val back = pendingNow.background
                                    addStyle(SpanStyle(color = lerp(pendingNow.color, bodyNow, f), background = back.copy(alpha = back.alpha * (1f - f))), start, end)
                                }
                            }
                        }
                    }
                }
                // 光标落在一条建议、一处不合规则的地方：键盘上方显示它。
                val caret = current?.selection?.takeIf { it.collapsed }?.start
                val focusIssue = caret?.let { at -> issueSpans.firstOrNull { (r, _) -> at in r.first..r.last + 1 }?.second }
                val focusRule = caret?.takeIf { focusIssue == null }?.let { at -> ruleSpans.firstOrNull { (r, _) -> at in r.first..r.last + 1 }?.second }
                // 光标停稳一会儿（CARET_SETTLE）才换：光标掠过划线的地方不一闪一闪，挪开了也等停稳再收。
                // 那条已经改掉、忽略、规则不再命中了就马上收。规则按它的 key 认（正文一改，检查结果是新算的）。
                val pointed: Any? = focusIssue ?: focusRule?.let { RuleAt(it.key) }
                var settled by remember(workId, chapterId) { mutableStateOf<Any?>(null) }
                LaunchedEffect(pointed) {
                    if (pointed != settled) {
                        delay(CARET_SETTLE)
                        settled = pointed
                    }
                }
                val shownIssue = (settled as? WriteIssue)?.takeIf { s -> issueSpans.any { it.second == s } }
                val shownRule = (settled as? RuleAt)?.let { s -> focusRule?.takeIf { it.key == s.key } ?: ruleSpans.firstOrNull { it.second.key == s.key }?.second }

                val textScroll = remember(current) { androidx.compose.foundation.ScrollState(0) }
                var editorFocused by remember(current) { mutableStateOf(false) }
                var textLayout by remember(current) { mutableStateOf<TextLayoutResult?>(null) }
                var textCoordinates by remember(current) { mutableStateOf<LayoutCoordinates?>(null) }
                var viewportCoordinates by remember(current) { mutableStateOf<LayoutCoordinates?>(null) }
                var settledCompanionCaret by remember(current) { mutableStateOf<Int?>(null) }
                // 输入或移动光标时保留系统光标；停稳后书灵才出现，不遮住输入法合成文字或划选。
                val canShowCompanion = current != null && current.composition == null && !readOnly
                LaunchedEffect(current, caret, textNow, editorFocused, canShowCompanion) {
                    settledCompanionCaret = null
                    if (caret != null && editorFocused && canShowCompanion) {
                        delay(450)
                        settledCompanionCaret = caret
                    }
                }
                val companionCaret = settledCompanionCaret?.takeIf {
                    it == caret && editorFocused && canShowCompanion
                }
                val companionPosition = companionCaret?.let { at ->
                    val layout = textLayout
                    val text = textCoordinates
                    val viewport = viewportCoordinates
                    if (layout != null && text?.isAttached == true && viewport?.isAttached == true) {
                        editorCompanionPosition(layout, at, text, viewport)
                    } else null
                }

                Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { viewportCoordinates = it }) {
                    if (current != null) {
                        LaunchedEffect(current) { if (current.text.isEmpty()) runCatching { focus.requestFocus() } }
                        BasicTextField(
                            state = current,
                            // 定稿的正文锁定：只能看、能选中复制，不能改。AI 在写（或写完等采纳）时也先不能改。
                            readOnly = readOnly,
                            inputTransformation = typing,
                            outputTransformation = output,
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(focus)
                                .onFocusChanged { editorFocused = it.isFocused }
                                .semantics { contentDescription = "正文" }
                                .padding(start = 22.dp, end = 22.dp, top = padding.calculateTopPadding() + 12.dp, bottom = 12.dp),
                            // 每段首行空两格（只是显示，存的正文不带缩进），段和折行一眼分得清。
                            textStyle = TextStyle(color = scheme.onSurface, fontSize = 18.sp, lineHeight = 32.sp, textIndent = TextIndent(firstLine = 2.em)),
                            cursorBrush = SolidColor(if (companionPosition == null) scheme.primary else Color.Unspecified),
                            lineLimits = TextFieldLineLimits.MultiLine(),
                            scrollState = textScroll,
                            onTextLayout = { result -> textLayout = result() },
                            decorator = { inner ->
                                Box(
                                    Modifier
                                        .onGloballyPositioned { textCoordinates = it }
                                        // 写作建议的划线（按类分线形）：照排版结果画在正文底下，滚动在绘制时再算，不重组、不重排。
                                        // AI 写的字插在正文里显示时位置对不上，先不画（底色那边同样不叠）。
                                        .drawBehind {
                                            if (inlineNow?.text.isNullOrEmpty()) drawMarkLines(markFades, textLayout, textScroll.value.toFloat())
                                        },
                                ) {
                                    if (current.text.isEmpty()) {
                                        Text("从这里开始写……\n换行就是另起一段。", color = c.tertiary, fontSize = 18.sp, lineHeight = 32.sp)
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                    if (companionPosition != null) {
                        WritingCaretCompanion(companionPosition, textScroll, aiConfig, scheme.primary)
                    }
                }
                // 键盘上方的几样（定稿锁定条、AI 的面板、写作建议、伏笔提醒）出现、收起都平滑：高矮慢慢变，正文框跟着慢慢变高变矮，
                // 正在写的那一行不猛跳；只淡入、升起，不横着动。减少动效时直接出现、直接消失。
                AnimatedVisibility(locked, enter = barEnter(), exit = barExit()) {
                    Row(
                        Modifier.fillMaxWidth().background(c.background).padding(start = 18.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("已定稿，正文锁定。要改先登记原因。", color = c.secondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(text = "解锁修改", onClick = { statusDialog = true })
                    }
                }
                // AI 的结果面板、写作建议，都在键盘上方，不挡正在写的地方。一次放一样，按优先次序挑。
                val aiPanel = aiState?.takeIf { it.chapterId == chapterId && it.workId == workId }
                // AI 写完、还没采纳的那段：本机数一下有几处 AI 腔、黑名单（只提醒，不拦采纳）。
                val inlineCliche = remember(inline?.text, inline?.loading, voiceRules) {
                    val w = inline
                    if (w == null || w.loading || w.text.isBlank()) 0 else AiCliche.count(CheckRules.check(w.text, voiceRules).hits)
                }
                val panel: EditorPanel = when {
                    current == null -> EditorPanel.None
                    inline != null -> EditorPanel.Inline(inline)
                    formatOpen -> EditorPanel.Format
                    aiPanel != null -> EditorPanel.Ai(aiPanel)
                    issuesOpen && (realtimeOn || clicheOn) -> EditorPanel.Issues
                    rulesOpen -> EditorPanel.Rules
                    companionOpen -> EditorPanel.Companion
                    toolsOpen -> EditorPanel.Tools
                    shownIssue != null && !locked -> EditorPanel.Suggestion(shownIssue)
                    shownRule != null && !rulesOpen -> EditorPanel.Rule(shownRule)
                    shownRealtime.issues.isNotEmpty() ||
                        (realtimeOn && realtime.chapterId == chapterId && (realtime.checking || realtime.error != null || realtime.waiting != null)) ->
                        EditorPanel.Realtime
                    else -> EditorPanel.None
                }
                AnimatedContent(
                    panel,
                    transitionSpec = {
                        // 换了一种：旧的落回底边、新的从底边升起，高矮用标准缓动过去。同一种（换一条建议、结果长出来）
                        // 不在这里量高矮，交给面板自己平滑，免得两层一前一后地拖。
                        val sameKind = initialState::class == targetState::class
                        (Motion.edgeEnter() togetherWith Motion.edgeExit()) using (
                            if (Effects.noMotion) null
                            else SizeTransform(clip = true) { _, _ -> if (sameKind) snap() else Motion.standard(Motion.MEDIUM) }
                        )
                    },
                    contentAlignment = Alignment.BottomCenter,
                    contentKey = { it::class },
                    label = "editorPanel",
                ) { p ->
                    val st = current
                    if (st != null) when (p) {
                        EditorPanel.Companion -> WritingCompanionPanel(
                            source = chosenCompanionAnalysis?.let { "《${it.referenceTitle}》·${it.chapterTitle}（${it.part.label}）" },
                            model = if (aiConfig.ready) aiConfig.providerLabel else "AI 未设置",
                            ready = aiConfig.ready,
                            // 选过的范本已经不在了（删了、重新分析了）：说一声，范本那一行显示没选。
                            missing = companionSelection != null && chosenCompanionAnalysis == null,
                            onIdea = { kind -> askIdea(kind) },
                            onSelectReference = { companionChoosing = true },
                            onClearReference = {
                                companionSelection = null
                                vm.clearCompanionReference(workId, chapterId)
                            },
                            onConfigureAi = { vm.open(Screen.Ai) },
                            onClose = { companionOpen = false },
                        )
                        EditorPanel.Tools -> WritingEditorToolsPanel(
                            selected = toolGroup,
                            shortcuts = when (toolGroup) {
                                EditorToolGroup.WRITE -> listOf(
                                    // 在光标处写从 AI 条“AI 写…”和智能推荐进；这里只留进草稿页的入口。
                                    EditorToolShortcut("AI 写整章草稿", LingYueIcons.Write) {
                                        toolsOpen = false; saveNow(); vm.open(Screen.ChapterDraft(workId, chapterId))
                                    },
                                    EditorToolShortcut("润色", LingYueIcons.Polish) {
                                        toolsOpen = false; runEdit(WriteAction.POLISH)
                                    },
                                    EditorToolShortcut("快捷标点", LingYueIcons.Punctuation) {
                                        toolsOpen = false; aiBar = false; punctuationBar = true
                                    },
                                )
                                EditorToolGroup.CHECK -> listOf(
                                    EditorToolShortcut("三道审稿", LingYueIcons.Search) {
                                        toolsOpen = false; saveNow(); vm.open(Screen.ChapterReview(workId, chapterId))
                                    },
                                    EditorToolShortcut("规则检查", LingYueIcons.Check) {
                                        toolsOpen = false; rulesOpen = true
                                    },
                                    EditorToolShortcut("细纲卡", LingYueIcons.ListView) {
                                        toolsOpen = false; vm.open(Screen.ChapterCard(workId, chapterId))
                                    },
                                    EditorToolShortcut("本章资料", LingYueIcons.Info) {
                                        toolsOpen = false; vm.open(Screen.ChapterBrief(workId, chapterId))
                                    },
                                    EditorToolShortcut("设定包", LingYueIcons.Folder) {
                                        toolsOpen = false; vm.open(Screen.WorkPack(workId))
                                    },
                                )
                                EditorToolGroup.ORGANIZE -> listOf(
                                    EditorToolShortcut("整理入账", LingYueIcons.Download) {
                                        toolsOpen = false; startSync()
                                    },
                                    EditorToolShortcut("故事账本", LingYueIcons.Book) {
                                        toolsOpen = false; vm.open(Screen.WorkLedger(workId, chapterId))
                                    },
                                    EditorToolShortcut("章节状态", LingYueIcons.Flag) {
                                        toolsOpen = false; statusDialog = true
                                    },
                                    EditorToolShortcut("历史版本", LingYueIcons.History) {
                                        toolsOpen = false
                                        st.text.toString().let { vm.writing.save(workId, chapterId, it, closing = true) }
                                        vm.open(Screen.WorkHistory(workId, chapterId))
                                    },
                                )
                                EditorToolGroup.MORE -> listOf(
                                    // “全部工具”放在第一格：小屏换行后也直接看得到完整旧菜单。
                                    EditorToolShortcut("全部工具", LingYueIcons.More) {
                                        toolsOpen = false; menu = true
                                    },
                                    EditorToolShortcut("范本写法", LingYueIcons.Notes) {
                                        toolsOpen = false; saveNow(); vm.open(Screen.WritingReference(workId, chapterId))
                                    },
                                    EditorToolShortcut("自动排版", LingYueIcons.Format) {
                                        toolsOpen = false
                                        when {
                                            locked -> vm.showMessage("这一章已定稿：要排版先解锁")
                                            inline != null -> vm.showMessage("AI 写的还没采纳或撤回，先处理它再排版")
                                            else -> formatOpen = true
                                        }
                                    },
                                    EditorToolShortcut("预览本章", LingYueIcons.Preview) {
                                        toolsOpen = false; saveNow(); vm.previewWork(workId, chapterId)
                                    },
                                    EditorToolShortcut("听本章", LingYueIcons.VolumeUp) {
                                        toolsOpen = false; saveNow(); vm.previewWork(workId, chapterId, listen = true)
                                    },
                                )
                            },
                            onSelect = { toolGroup = it },
                            onClose = { toolsOpen = false },
                        )
                        is EditorPanel.Inline -> InlineBar(
                            p.write,
                            lookup = aiLookup,
                            onStop = { vm.writingAi.stopInline() },
                            onAccept = {
                                val w = p.write
                                val done = vm.writingAi.acceptInline(st.text.toString())
                                if (done == null) {
                                    vm.showMessage(vm.writingAi.inline.value?.error ?: if (w.referenceAnalysisId != null && !w.referenceValidated)
                                        "这一段未通过范本核验，请重写" else "正文或范本已经变化，请重新生成")
                                } else {
                                    st.edit {
                                        replace(0, length, done.first)
                                        selection = TextRange(done.second)
                                    }
                                    // 刚放进正文的那一段从浅色过渡到正文色。
                                    effects.launch { accepted.play(w.at until w.at + w.text.length) }
                                    // 写的是一拍：先在这里记成已写（细纲卡随后落盘），推荐马上换到下一拍。
                                    w.beat?.let { n -> beatCard = beatCard?.takeIf { beats(it).size == w.beatTotal }?.withBeatDone(n, true) ?: beatCard }
                                }
                            },
                            onRetry = { vm.writingAi.retryInline() },
                            onDiscard = { vm.writingAi.discardInline() },
                            cliche = inlineCliche,
                            onSelect = { vm.writingAi.selectInline(it) },
                        )
                        EditorPanel.Format -> FormatPanel(
                            formatResult,
                            skip = formatSkip,
                            onToggle = { k -> formatSkip = if (k in formatSkip) formatSkip - k else formatSkip + k },
                            onApply = apply@{
                                when {
                                    locked -> vm.showMessage("这一章已定稿：要排版先解锁")
                                    inline != null -> vm.showMessage("AI 写的还没采纳或撤回，先处理它再排版")
                                    else -> {
                                        val before = st.text.toString()
                                        val kinds = formatSettings.kinds - formatSkip
                                        val r = TextFormat.format(before, formatSettings, kinds)
                                        formatOpen = false
                                        if (!r.changed) return@apply
                                        // 一次编辑换掉改了的那一截（撤销一下整步回去），光标跟着挪到排好的位置。
                                        val sel = st.selection
                                        var head = 0
                                        val after = r.text
                                        while (head < before.length && head < after.length && before[head] == after[head]) head++
                                        var tail = 0
                                        while (tail < before.length - head && tail < after.length - head && before[before.length - 1 - tail] == after[after.length - 1 - tail]) tail++
                                        // 写作建议、规则的标记按位置画：先收掉旧的，等按新正文重算，不错位。
                                        markFades.reset()
                                        report = null
                                        st.edit {
                                            replace(head, before.length - tail, after.substring(head, after.length - tail))
                                            selection = TextRange(r.map(sel.start), r.map(sel.end))
                                        }
                                        vm.writing.applyFormat(workId, chapterId, before, after)
                                        vm.writingAi.afterFormat(workId, chapterId) { p -> TextFormat.formatParagraph(p, formatSettings, kinds) }
                                        vm.showMessage("排好了 ${r.changes.size} 处；撤销一下能回到排版前")
                                    }
                                }
                            },
                            onSettings = { vm.open(Screen.WorkFormatSettings(workId)) },
                            onClose = { formatOpen = false },
                        )
                        is EditorPanel.Ai -> WriteAiPanel(
                            p.state,
                            lookup = aiLookup,
                            model = "${aiConfig.providerLabel} · ${aiConfig.model}",
                            onAccept = accept@{ result ->
                                if (locked) {
                                    vm.showMessage("这一章已定稿：要改先解锁并登记原因")
                                    return@accept
                                }
                                val task = p.state.task as WriteAiTask.Edit
                                val before = st.text.toString()
                                when (applyEdit(st, task, result)) {
                                    EditApply.DONE -> {
                                        vm.writingAi.accepted(workId, task.action, task.original, result)
                                        val after = st.text.toString()
                                        task.reviewFix?.takeIf { before != after }?.let { fix ->
                                            // 成功替换并真正落盘以后，才处理来源审稿里的同一条问题。
                                            vm.writing.save(workId, chapterId, after, onSaved = {
                                                val report = vm.writingAi.reviews.value[workId to chapterId]?.review
                                                if (report != null && report.at == fix.reviewAt && report.textHash == fix.textHash &&
                                                    report.gate(fix.gate)?.issues?.any { it == fix.issue } == true
                                                ) {
                                                    vm.writingAi.markIssue(workId, chapterId, fix.gate, fix.issue, com.xycs.lingyuereader.data.ReviewIssue.FIXED)
                                                }
                                            })
                                        }
                                    }
                                    EditApply.MISSING -> vm.showMessage(
                                        if (task.action == WriteAction.CONTINUE) "正文已变化，接着写的光标位置可能失效；请重新生成或复制结果手动插入"
                                        else "那段原文已经改动，没找到要替换的地方"
                                    )
                                    EditApply.AMBIGUOUS -> vm.showMessage("原来的位置附近有几处一样的文字，不知道换哪一处，没有替换：可以复制结果自己改")
                                }
                            },
                            onInsert = { result ->
                                if (locked) {
                                    vm.showMessage("这一章已定稿：要改先解锁并登记原因")
                                } else {
                                    st.edit { replace(selection.min, selection.max, result) }
                                    vm.writingAi.close()
                                }
                            },
                            onRetry = { vm.writingAi.retry() },
                            onClose = { vm.writingAi.close() },
                        )
                        is EditorPanel.Suggestion -> SuggestionBar(
                            p.issue,
                            onFix = { fixIssue(st, p.issue) },
                            onIgnore = { vm.writingAi.ignoreIssue(p.issue) },
                            onMute = { vm.writingAi.muteKind(workId, p.issue.kind) }.takeIf { p.issue.kind != SuggestKind.CLICHE.name },
                        )
                        is EditorPanel.Rule -> {
                            val hit = p.hit
                            // 内置清单的命中：光标那一处的原文（2–20 字）可以一键加进黑名单。
                            val at = hit.ranges.firstOrNull { r -> caret != null && caret in r.first..r.last + 1 } ?: hit.ranges.firstOrNull()
                            val phrase = at?.takeIf { hit.rule.source.doc == AiCliche.DOC && it.last < textNow.length }
                                ?.let { AiCliche.bannable(textNow.substring(it.first, it.last + 1)) }
                            RuleNote(
                                hit.message,
                                tag = when {
                                    AiCliche.isBanned(hit) -> AiCliche.BANNED_DOC
                                    AiCliche.isVoice(hit) -> "AI 腔"
                                    else -> "规则"
                                },
                                onBan = phrase?.let { ph ->
                                    {
                                        vm.writingAi.updateAssist(workId) { it.ban(ph) }
                                        vm.showMessage("「$ph」加进了我的黑名单")
                                    }
                                },
                            ) { rulesOpen = true }
                        }
                        EditorPanel.Issues -> IssuesPanel(
                            shownRealtime,
                            onFix = { i -> fixIssue(st, i) },
                            onIgnore = { vm.writingAi.ignoreIssue(it) },
                            onClose = {
                                issuesOpen = false
                                vm.writingAi.clearClicheNote()
                            },
                        )
                        EditorPanel.Rules -> RulesPanel(
                            report,
                            hasRules = rules?.isEmpty == false,
                            onCliche = { startCliche() }.takeIf { !locked && report?.hits?.any(AiCliche::isVoice) == true },
                            onJump = jump@{ hit ->
                                if (hit.ranges.isEmpty()) return@jump
                                val i = lastJump?.takeIf { it.first == hit.key }?.second?.plus(1) ?: 0
                                val r = hit.ranges[i % hit.ranges.size]
                                lastJump = hit.key to i
                                st.edit { selection = clampRange(r, length) }
                                runCatching { focus.requestFocus() }
                            },
                            onOpenRules = { vm.open(Screen.WorkRules(workId)) },
                            onClose = { rulesOpen = false },
                        )
                        EditorPanel.Realtime -> RealtimeLine(if (realtimeOn) shownRealtime else shownRealtime.copy(checking = false, error = null, waiting = null)) {
                            if (shownRealtime.issues.isNotEmpty()) issuesOpen = true
                        }
                        EditorPanel.None -> Spacer(Modifier.fillMaxWidth())
                    }
                }
                AnimatedVisibility(due.isNotEmpty() && aiPanel == null && !rulesOpen && !issuesOpen && !companionOpen, enter = barEnter(), exit = barExit()) {
                    EditorNote("这一章计划回收的伏笔：" + due.joinToString("、")) { vm.open(Screen.ChapterBrief(workId, chapterId)) }
                }
                // 平时只占一行：两个带字的旧入口 + 上下文推荐 + 四组工具。旧 AI / 标点条仍可切换，功能不丢。
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                val stripMode = when {
                    aiBar -> "ai"
                    punctuationBar -> "punctuation"
                    else -> "smart"
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).background(c.background).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左边两个入口图标下面写着字，长按再给一句说明，不只靠 ✦ 和书本猜。
                    LabeledIconButton(
                        LingYueIcons.Sparkle,
                        label = if (aiBar) "标点" else "AI",
                        description = if (aiBar) "换回标点" else "AI 写作",
                        selected = aiBar,
                        longClickLabel = "说明",
                        onLongClick = {
                            vm.showMessage(if (aiBar) "换回快捷标点条" else "AI 写作：切到 AI 工具条，AI 写、润色、扩写、读者点评等")
                        },
                        onClick = {
                            if (aiBar) { aiBar = false; punctuationBar = true }
                            else { aiBar = true; punctuationBar = false }
                            toolsOpen = false
                        },
                    )
                    LabeledIconButton(
                        LingYueIcons.Book,
                        label = "书灵",
                        description = "书灵",
                        selected = companionOpen,
                        longClickLabel = "说明",
                        onLongClick = { vm.showMessage("书灵：问主意（接下来怎么写、起名、卡文了……），选写作范本") },
                        onClick = {
                            when {
                                // 问主意不改稿子：定稿的章也能打开书灵。
                                inline != null -> vm.showMessage("先处理正在写入的 AI 正文")
                                aiState?.let { it.workId == workId && it.chapterId == chapterId } == true ->
                                    vm.showMessage("先处理当前的 AI 结果，再打开书灵")
                                else -> {
                                    companionOpen = !companionOpen
                                    if (companionOpen) {
                                        toolsOpen = false
                                        formatOpen = false
                                        issuesOpen = false
                                        rulesOpen = false
                                    }
                                }
                            }
                        },
                    )
                    AnimatedContent(
                        stripMode,
                        modifier = Modifier.weight(1f),
                        transitionSpec = {
                            Effects.enter(fadeIn(Motion.standard(Motion.SHORT))) togetherWith
                                Effects.exit(fadeOut(Motion.exit()))
                        },
                        label = "editorToolStrip",
                    ) { mode ->
                        when (mode) {
                            "smart" -> {
                                val enabled = current != null && recommendation.enabled
                                val action = if (enabled) Modifier.combinedClickableCompat {
                                    when (recommendation.kind) {
                                        EditorRecommendationKind.PENDING_AI -> Unit // 状态行无点击；采纳等操作只在上方结果面板。
                                        EditorRecommendationKind.UNLOCK -> statusDialog = true
                                        EditorRecommendationKind.CONFIGURE_AI -> vm.open(Screen.Ai)
                                        EditorRecommendationKind.DRAFT_CHAPTER -> {
                                            saveNow(); vm.open(Screen.ChapterDraft(workId, chapterId))
                                        }
                                        EditorRecommendationKind.REVISE_SELECTION -> runEdit(WriteAction.POLISH)
                                        EditorRecommendationKind.REVIEW_CHAPTER -> {
                                            saveNow(); vm.open(Screen.ChapterReview(workId, chapterId))
                                        }
                                        EditorRecommendationKind.WRITE_BEAT -> writeNextBeat(effects)
                                        EditorRecommendationKind.CONTINUE -> openAiWrite()
                                    }
                                } else Modifier
                                val label = if (current == null) "载入中" else recommendation.label
                                // 正文才是主角：推荐用淡强调色底、强调色字，不铺整条实心色；
                                // 还没设置写作 AI 时只是一枚描边的次要按钮，不带副标题。
                                val setup = enabled && recommendation.kind == EditorRecommendationKind.CONFIGURE_AI
                                val shape = RoundedCornerShape(12.dp)
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clip(shape)
                                        .then(
                                            when {
                                                setup -> Modifier.border(1.dp, c.tertiary.copy(alpha = 0.45f), shape)
                                                enabled -> Modifier.background(c.tint.copy(alpha = 0.12f))
                                                else -> Modifier.background(c.fill)
                                            },
                                        )
                                        .then(action)
                                        .semantics {
                                            contentDescription = if (enabled) "智能推荐：$label，${recommendation.reason}"
                                                else "当前状态：$label，${recommendation.reason}"
                                            if (!enabled) disabled()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (setup) {
                                        Text(label, color = c.label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("✦ $label", color = if (enabled) c.tint else c.label,
                                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(recommendation.reason, color = c.secondary,
                                            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            else -> Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (mode == "ai") {
                                    val clicheHere = !locked && report?.hits?.any(AiCliche::isVoice) == true
                                    (if (clicheHere) AI_BAR.take(1) + AiBarItem.Cliche + AI_BAR.drop(1) else AI_BAR).forEach { item ->
                                        Box(
                                            Modifier
                                                .padding(horizontal = 2.dp)
                                                .widthIn(min = 48.dp).heightIn(min = 48.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .combinedClickableCompat { onAiItem(item) }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            contentAlignment = Alignment.Center,
                                        ) { Text(item.label, color = c.label, fontSize = 14.sp, maxLines = 1) }
                                    }
                                } else {
                                    Box(
                                        Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .combinedClickableCompat { punctuationBar = false }
                                            .semantics { contentDescription = "返回智能推荐" },
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(LingYueIcons.Sparkle, contentDescription = null, tint = c.tint, modifier = Modifier.size(18.dp)) }
                                    QUICK_INSERTS.forEach { s ->
                                        Box(
                                            Modifier
                                                .padding(horizontal = 2.dp)
                                                .widthIn(min = 48.dp).heightIn(min = 48.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .combinedClickableCompat {
                                                    when {
                                                        locked -> vm.showMessage("这一章已定稿：要改先解锁并登记原因")
                                                        inline != null -> vm.showMessage("AI 写的还没采纳或撤回，先处理它再改正文")
                                                        else -> current?.let { insert(it, s) }
                                                    }
                                                }
                                                .semantics { contentDescription = "插入$s" }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            contentAlignment = Alignment.Center,
                                        ) { Text(s, color = c.label, fontSize = 17.sp, maxLines = 1) }
                                    }
                                }
                            }
                        }
                    }
                    LabeledIconButton(
                        LingYueIcons.Tools,
                        label = "工具",
                        description = if (toolsOpen) "关闭四组工具" else "打开四组工具",
                        selected = toolsOpen,
                        onClick = {
                            if (pendingAi) vm.showMessage("先处理当前的 AI 结果，再打开工具")
                            else {
                                toolsOpen = !toolsOpen
                                if (toolsOpen) {
                                    companionOpen = false
                                    formatOpen = false
                                    issuesOpen = false
                                    rulesOpen = false
                                }
                            }
                        },
                    )
                }
            }
            if (renaming && chapter != null) {
                NameDialog("章名", chapter.title, "保存", onDismiss = { renaming = false }) {
                    renaming = false
                    vm.writing.renameChapter(workId, chapterId, it)
                }
            }
            if (companionChoosing) {
                WritingCompanionSelectionDialog(
                    analyses = companionAnalyses,
                    selectedId = companionSelection,
                    onSelect = { id ->
                        companionSelection = id
                        vm.chooseCompanionReference(workId, chapterId, id)
                        companionOpen = true
                        companionChoosing = false
                    },
                    onCreate = {
                        companionChoosing = false
                        current?.text?.toString()?.let { text ->
                            vm.writing.save(workId, chapterId, text, onSaved = {
                                vm.open(Screen.WritingReference(workId, chapterId))
                            })
                        }
                    },
                    onDismiss = { companionChoosing = false },
                )
            }
            if (statusDialog && chapter != null) {
                StatusDialog(chapter, ruleHits = report?.hits?.count { !AiCliche.isVoice(it) }, onDismiss = { statusDialog = false }) { st, reason, canon ->
                    statusDialog = false
                    vm.writing.setStatus(workId, chapterId, st, reason, canon, currentText = current?.text?.toString()) {
                        if (st == ChapterStatus.FINAL && wantsSyncTip()) syncTip = true
                    }
                }
            }
            if (syncTip) {
                SyncTipDialog(
                    onDismiss = { never ->
                        syncTip = false
                        if (never) vm.writingAi.updateAssist(workId) { it.copy(syncTip = false) }
                    },
                    onSync = { never ->
                        syncTip = false
                        if (never) vm.writingAi.updateAssist(workId) { it.copy(syncTip = false) }
                        startSync()
                    },
                )
            }
            if (aiWriteOpen) {
                AiWriteDialog(
                    card = beatCard,
                    variants = assists[workId]?.beatVariants == true,
                    onVariants = { on -> vm.writingAi.updateAssist(workId) { it.copy(beatVariants = on) } },
                    reference = chosenCompanionAnalysis?.let { "《${it.referenceTitle}》·${it.chapterTitle}" },
                    useReference = useReference,
                    onUseReference = { useReference = it },
                    onDismiss = { aiWriteOpen = false },
                ) { choice ->
                    aiWriteOpen = false
                    if (choice.mode == InlineMode.CUSTOM) asking = "想让 AI 怎么写？" to { req -> writeChoice(choice, req) }
                    else writeChoice(choice)
                }
            }
            asking?.let { (title, then) ->
                AskDialog(title, hint = if (title.startsWith("给")) "比如：女主角的师父、一座边城" else "", confirm = "好", onDismiss = { asking = null }) {
                    asking = null
                    then(it)
                }
            }
        }
    }
}

/**
 * 编辑器里记着的细纲卡和刚从盘上读的合起来：采纳一拍时编辑器先把它记成已写，细纲卡随后才落盘，
 * 这中间读到的卡还没记上。节拍没改过时两边的“已写”合起来算，免得推荐又回到刚写过的那一拍、重写一遍；
 * 节拍改过了以盘上的为准。离开编辑器再回来，编辑器记着的就清了，细纲卡页取消的“已写”照样生效。
 */
private fun mergeBeatsDone(disk: ChapterCard, local: ChapterCard?): ChapterCard =
    if (local != null && disk.beats == local.beats) disk.copy(beatsDone = (disk.beatsDone + local.beatsDone).distinct().sorted()) else disk

/** 光标在一处划线上停稳多久才显示那条建议（光标掠过时不一闪一闪）。 */
private const val CARET_SETTLE = 150L

/** 键盘上方那块放什么：一次放一样，换了种类才升起、落下，同一种只换里面的字。 */
private sealed interface EditorPanel {
    data object Companion : EditorPanel
    data object Tools : EditorPanel
    data class Inline(val write: InlineWrite) : EditorPanel
    data class Ai(val state: WriteAiState) : EditorPanel
    data class Suggestion(val issue: WriteIssue) : EditorPanel
    data class Rule(val hit: RuleHit) : EditorPanel
    data object Issues : EditorPanel
    data object Format : EditorPanel
    data object Rules : EditorPanel
    data object Realtime : EditorPanel
    data object None : EditorPanel
}

/** 光标指着的那条规则：按规则认（停笔重新检查后，命中的位置和对象都是新的）。 */
private data class RuleAt(val key: String)

/** 正文和键盘之间的一条（定稿锁定、伏笔提醒）：长高并淡入，正文框跟着慢慢变矮；减少动效时直接出现。 */
private fun barEnter(): EnterTransition =
    Effects.enter(expandVertically(Motion.standard(Motion.MEDIUM), expandFrom = Alignment.Bottom) + fadeIn(Motion.standard(Motion.SHORT)))

private fun barExit(): ExitTransition =
    Effects.exit(shrinkVertically(Motion.exit(), shrinkTowards = Alignment.Bottom) + fadeOut(Motion.exit()))

/** 写作建议的一行状态：几条建议、在查（带小转圈）、在等、没查成。 */
@Composable
private fun RealtimeLine(realtime: RealtimeState, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    Row(
        Modifier.fillMaxWidth().background(c.background).combinedClickableCompat(onClick).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (realtime.checking && realtime.issues.isEmpty()) Spinner() else Icon(LingYueIcons.Sparkle, contentDescription = null, tint = c.tint, modifier = Modifier.size(16.dp))
        val text = when {
            realtime.issues.isNotEmpty() -> "${realtime.issues.size} 条写作建议（正文里划线的地方，光标点上去看）"
            realtime.checking -> "正在看刚写的段落……"
            realtime.waiting != null -> realtime.waiting
            else -> null
        }
        if (text != null) {
            Text(
                text,
                color = if (realtime.issues.isNotEmpty()) c.tint else c.secondary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
        } else {
            ErrorText("写作建议没查成：${realtime.error}", Modifier.weight(1f).padding(start = 6.dp))
        }
        if (realtime.issues.isNotEmpty()) Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(16.dp))
    }
}

/**
 * 正文里的一处标记：[key] 认的是哪条建议、哪条规则的第几处，不是位置（正文一改位置就变）。
 * [color] 是底色；写作建议另有划线 [line]（按类分线形，颜色 [lineColor]），由 [drawMarkLines] 画，不走文字样式。
 */
private data class TextMark(
    val key: Any,
    val start: Int,
    val end: Int,
    val color: Color,
    val line: SuggestLine? = null,
    val lineColor: Color = Color.Unspecified,
) {
    fun style(alpha: Float) = SpanStyle(background = color.copy(alpha = color.alpha * alpha))
}

/**
 * 按排版结果 [layout] 给带划线的标记画线：每个折行一段，画在基线下方；只画看得见的那几行。
 * [scroll] 是正文现在滚了多少。透明度跟着底色一起淡入淡出（减少动效时直接是 1）。
 */
private fun DrawScope.drawMarkLines(fades: MarkFades, layout: TextLayoutResult?, scroll: Float) {
    if (layout == null) return
    val length = layout.layoutInput.text.length
    val below = 3.dp.toPx()
    clipRect {
        fades.forEach { m, alpha ->
            val line = m.line ?: return@forEach
            if (alpha <= 0f || m.start !in 0 until m.end || m.end > length) return@forEach
            val color = m.lineColor.copy(alpha = m.lineColor.alpha * alpha)
            for (l in layout.getLineForOffset(m.start)..layout.getLineForOffset(m.end - 1)) {
                if (layout.getLineBottom(l) - scroll < 0f) continue
                if (layout.getLineTop(l) - scroll > size.height) break
                val visibleEnd = layout.getLineEnd(l, visibleEnd = true)
                val from = maxOf(m.start, layout.getLineStart(l))
                val to = minOf(m.end, visibleEnd)
                if (to <= from) continue
                val x1 = layout.getHorizontalPosition(from, usePrimaryDirection = true)
                val x2 = if (to >= visibleEnd) layout.getLineRight(l) else layout.getHorizontalPosition(to, usePrimaryDirection = true)
                drawSuggestLine(line, color, minOf(x1, x2), maxOf(x1, x2), layout.getLineBaseline(l) - scroll + below)
            }
        }
    }
}

/**
 * 正文标记的淡入淡出：一次检查出来的新标记从无到有，去掉的留在原处淡掉再拿走（[Motion.SHORT]）；
 * 减少动效时直接出现、直接消失。透明度在 OutputTransformation 里读，一变正文的样式就跟着重画。
 */
private class MarkFades(private val scope: CoroutineScope) {
    private val alpha = mutableStateMapOf<Any, Animatable<Float, AnimationVector1D>>()
    /** 正在淡掉的，停在最后的位置。 */
    private val leaving = mutableStateMapOf<Any, TextMark>()
    private var marks by mutableStateOf<List<TextMark>>(emptyList())
    private var shown: Map<Any, TextMark> = emptyMap()

    fun update(next: List<TextMark>) {
        if (next === marks) return
        val now = next.associateBy { it.key }
        marks = next
        if (Effects.noMotion) {
            alpha.clear()
            leaving.clear()
        } else {
            now.keys.filter { it !in shown }.forEach { key ->
                leaving.remove(key)
                fade(key, 1f)
            }
            shown.filterKeys { it !in now }.forEach { (key, mark) ->
                leaving[key] = mark
                fade(key, 0f) {
                    leaving.remove(key)
                    alpha.remove(key)
                }
            }
        }
        shown = now
    }

    private fun fade(key: Any, to: Float, then: () -> Unit = {}) {
        val a = alpha.getOrPut(key) { Animatable(1f - to) }
        scope.launch {
            // 中途又反过来（刚去掉又回来）时这一次被打断，不收尾。
            a.animateTo(to, Motion.standard(Motion.SHORT))
            then()
        }
    }

    /** 正文整个换了样子（排版）：旧标记的位置都不算数了，直接收掉，不在原处淡出；新的按新正文淡入。 */
    fun reset() {
        alpha.clear()
        leaving.clear()
        marks = emptyList()
        shown = emptyMap()
    }

    /** 每处标记和它现在的透明度：还在的用现在的位置，去掉的用最后的位置。 */
    fun forEach(block: (TextMark, Float) -> Unit) {
        marks.forEach { block(it, alpha[it.key]?.value ?: 1f) }
        leaving.values.forEach { block(it, alpha[it.key]?.value ?: 0f) }
    }
}

/**
 * AI 写的字一截截来：每截从透明淡到浅色（[Motion.SHORT]），前面已经出来的不动，淡完就不再管。
 * 字变少了（重写、撤回）从头算；减少动效时直接出现。
 */
private class InlineFade(private val scope: CoroutineScope) {
    private val chunks = mutableStateListOf<Pair<Int, Animatable<Float, AnimationVector1D>>>()
    private var seen = 0

    fun update(length: Int) {
        if (length < seen) chunks.clear()
        if (length <= seen || Effects.noMotion) {
            seen = length
            return
        }
        val chunk = seen to Animatable(0f)
        seen = length
        chunks += chunk
        scope.launch {
            try {
                chunk.second.animateTo(1f, Motion.standard(Motion.SHORT))
            } finally {
                chunks -= chunk
            }
        }
    }

    /** 还在淡入的每一截：在 AI 写的字里的 [from, to) 和现在的透明度。 */
    fun forEachFading(length: Int, block: (Int, Int, Float) -> Unit) {
        val list = chunks.toList()
        list.forEachIndexed { i, (from, a) ->
            val to = minOf(list.getOrNull(i + 1)?.first ?: length, length)
            if (from < to) block(from, to, a.value)
        }
    }
}

/** 采纳 AI 写的那段：从浅色过渡到正文色（[Motion.MEDIUM]），看得出“写进正文”这一下；减少动效时直接变。 */
private class AcceptSettle {
    var range by mutableStateOf<IntRange?>(null)
        private set
    val progress = Animatable(1f)

    suspend fun play(r: IntRange) {
        range = r
        try {
            progress.snapTo(0f)
            progress.animateTo(1f, Motion.standard(Motion.MEDIUM))
        } finally {
            if (range == r) range = null
        }
    }
}

/**
 * 换章（上一章、下一章、写下一章）时往哪边滑：往后从右边进来，往前从左边进来，和进下一层、返回一样。
 * 不是同一部作品里换章时返回 null（照栈的深浅定方向）。
 */
internal fun editorStepForward(vm: AppViewModel, from: Screen, to: Screen): Boolean? {
    if (from !is Screen.Editor || to !is Screen.Editor || from.workId != to.workId || from.chapterId == to.chapterId) return null
    val chapters = vm.writing.works.value.firstOrNull { it.id == to.workId }?.chapters ?: return true
    val a = chapters.indexOfFirst { it.id == from.chapterId }
    val b = chapters.indexOfFirst { it.id == to.chapterId }
    // 刚写的下一章可能还没进列表：当往后。
    return a < 0 || b < 0 || b > a
}

/** 检查结果是停笔 0.4 秒后算的：刚删了一大段时，命中的位置可能已经超出正文，夹在正文里面。 */
internal fun clampRange(r: IntRange, length: Int): TextRange {
    val start = r.first.coerceIn(0, length)
    return TextRange(start, (r.last + 1).coerceIn(start, length))
}

/** 插入文字：成对的符号（「」“”『』）插入后光标停在中间；选中了文字时把它包起来。 */
private fun insert(state: TextFieldState, s: String) {
    state.edit {
        val start = minOf(selection.start, selection.end)
        val end = maxOf(selection.start, selection.end)
        val pair = s.length == 2 && s[0] != s[1]
        if (pair) {
            val inner = toString().substring(start, end)
            replace(start, end, "${s[0]}$inner${s[1]}")
            selection = androidx.compose.ui.text.TextRange(start + 1 + inner.length)
        } else {
            replace(start, end, s)
            selection = androidx.compose.ui.text.TextRange(start + s.length)
        }
    }
}

/** 点击：不带涟漪，按下时变灰。 */
@Composable
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return this.background(pressHighlight(pressed)).clickable(interactionSource = source, indication = null, onClick = onClick)
}

/**
 * 一章的历史版本：新的在前，写了时间和字数。点开看全文，可以换回那一版（现在的正文会先存一版）。
 */
@Composable
fun WorkHistoryScreen(vm: AppViewModel, workId: String, chapterId: String, docId: String? = null) {
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val packs by vm.writing.packs.collectAsStateWithLifecycle()
    // 设定包的文档和章节共用历史版本，只是键不同。
    val key = docId?.let(vm.writing::docKey) ?: chapterId
    val title = if (docId != null) packs[workId]?.firstOrNull { it.id == docId }?.title else works.firstOrNull { it.id == workId }?.chapter(chapterId)?.title
    var list by remember { mutableStateOf<List<Snapshot>?>(null) }
    var open by remember { mutableStateOf<Pair<Snapshot, String>?>(null) }
    var loading by remember { mutableStateOf<Snapshot?>(null) }
    LaunchedEffect(workId, key) { list = vm.writing.history(workId, key) }
    LaunchedEffect(loading) {
        val s = loading ?: return@LaunchedEffect
        open = vm.writing.snapshotText(workId, key, s.at)?.let { s to it }
        loading = null
    }
    val rule = (if (docId != null) "每次打开、离开这份文档，以及导入替换、修改中每隔十分钟，都会存一版。" else "每次打开、离开这一章，以及写作中每隔十分钟，都会存一版。") +
        "最多留 ${com.xycs.lingyuereader.data.WorkStore.KEEP_SNAPSHOTS} 版。"

    SubPageScaffold("历史版本", onBack = { vm.back() }) { padding ->
        // 配色在骨架提供的配色里面读。
        val c = LocalGroupedColors.current
        val snaps = list
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            when {
                snaps == null -> item(key = "loading") { LoadingState("正在读历史版本……") }
                snaps.isEmpty() -> item(key = "empty") { EmptyState(LingYueIcons.History, "还没有历史版本", rule) }
                else -> {
                    if (title != null) item(key = "header") { GroupedListHeader(title) }
                    // 一版一格懒加载（一章最多留几十版），连成一张卡片。
                    items(snaps, contentType = { "snapshot" }) { s ->
                        GroupedListCell(s === snaps.first(), s === snaps.last(), gap = 8.dp) {
                            NavRow(whenLabel(s.at), value = words(s.words)) { loading = s }
                        }
                    }
                    item(key = "footer") { GroupedListFooter(rule) }
                }
            }
        }
        open?.let { (s, text) ->
            GlassDialog(show = true, title = whenLabel(s.at), summary = words(s.words), onDismissRequest = { open = null }) {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(text.ifBlank { "（空）" }, color = c.label, fontSize = 15.sp, lineHeight = 24.sp)
                }
                DialogButtons(
                    onDismiss = { open = null },
                    onConfirm = {
                        open = null
                        if (docId != null) vm.writing.restoreDoc(workId, docId, s.at) { vm.back() }
                        else vm.writing.restore(workId, chapterId, s.at) { vm.back() }
                    },
                    confirmText = "换回这一版",
                )
            }
        }
    }
}
