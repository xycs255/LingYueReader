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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
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
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.ChapterSyncs
import com.xycs.lingyuereader.data.CheckRules
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
    LaunchedEffect(workId, pack) {
        val (list, latest) = vm.writing.foreshadows(workId)
        val open = list.filter { !it.done }
        val due = open.count { it.payoff != null && it.payoff <= latest }
        foreshadowNote = when {
            pack?.none { it.kind == PackKind.FORESHADOW } != false -> null
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
                    val last = work.chapters.maxByOrNull { it.updatedAt }
                    GroupedSection(footer = work.synopsis.ifBlank { null }) {
                        row {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(words(work.words), color = c.label, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    buildString {
                                        append("${work.chapters.size} 章")
                                        if (work.volumes.size > 1) append(" · ${work.volumes.size} 卷")
                                        if (work.author.isNotBlank()) append(" · ${work.author}")
                                        // 各状态多少章：定稿 12 · 修订 1 · 草稿 3 · 细纲 5
                                        val counts = ChapterStatus.entries.reversed().mapNotNull { st -> work.chapters.count { it.status == st }.takeIf { it > 0 }?.let { "${st.label} $it" } }
                                        if (counts.isNotEmpty()) append("\n").append(counts.joinToString(" · "))
                                    },
                                    color = c.secondary,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        if (last != null) {
                            row {
                                NavRow("继续写", value = last.title, icon = LingYueIcons.Write, iconBackground = Color(0xFFFF9500)) {
                                    vm.open(Screen.Editor(workId, last.id))
                                }
                            }
                        }
                        if (last != null) {
                            row {
                                NavRow("当书预览", value = "排版、听稿", icon = LingYueIcons.Book, iconBackground = Color(0xFF34C759), enabled = !busy) {
                                    vm.previewWork(workId, null)
                                }
                            }
                        }
                        row {
                            NavRow("设定包", value = pack?.let { if (it.isEmpty()) "空" else "${it.size} 份" }, icon = LingYueIcons.Notes, iconBackground = Color(0xFF007AFF)) {
                                vm.open(Screen.WorkPack(workId))
                            }
                        }
                        row {
                            NavRow(
                                "故事账本",
                                value = ledgers[workId]?.let { if (it.isEmpty()) "空" else "${it.size} 条" },
                                icon = LingYueIcons.ListView,
                                iconBackground = Color(0xFF5856D6),
                            ) { vm.open(Screen.WorkLedger(workId)) }
                        }
                        row {
                            NavRow("写作 AI", value = if (assist?.realtime == true) "写作建议已开" else null, icon = LingYueIcons.Sparkle, iconBackground = Color(0xFFAF52DE)) {
                                vm.open(Screen.WorkAi(workId))
                            }
                        }
                        row {
                            NavRow(
                                "变更登记",
                                value = if (pendingChanges > 0) "$pendingChanges 条待同步设定" else allChanges[workId]?.size?.takeIf { it > 0 }?.let { "$it 条" },
                                icon = LingYueIcons.History,
                                iconBackground = Color(0xFF8E8E93),
                            ) { vm.open(Screen.WorkChanges(workId)) }
                        }
                        row {
                            NavRow("伏笔", value = foreshadowNote, icon = LingYueIcons.Star, iconBackground = Color(0xFFFF9500)) { vm.open(Screen.Foreshadows(workId)) }
                        }
                        row {
                            NavRow(
                                "检查规则",
                                value = ruleCount?.let { if (it == 0) "未设" else "$it 条" },
                                icon = LingYueIcons.Check,
                                iconBackground = Color(0xFF30B0C7),
                            ) { vm.open(Screen.WorkRules(workId)) }
                        }
                    }
                }
                // 每卷一张卡片：卷名一格、各章一格一格、最后“新的一章”。格子各自懒加载，键是卷 / 章的 id，增删挪动都有动画。
                work.volumes.forEach { v ->
                    item(key = "volume:${v.id}", contentType = "volume") {
                        GroupedListCell(first = true, last = false, Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
                            VolumeRow(
                                v,
                                showMenu = volumeMenu == v.id,
                                canDelete = work.volumes.size > 1,
                                onClick = { volumeMenu = v.id },
                                onDismissMenu = { volumeMenu = null },
                                onRename = { renamingVolume = v },
                                onDelete = { deletingVolume = v },
                            )
                        }
                    }
                    items(v.chapters, key = { it.id }, contentType = { "chapter" }) { ch ->
                        GroupedListCell(first = false, last = false, Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
                            ChapterRow(
                                ch,
                                unsynced = pack?.isNotEmpty() == true && ch.id in unsynced,
                                showMenu = chapterMenu == ch.id,
                                onClick = { vm.open(Screen.Editor(workId, ch.id)) },
                                onLongClick = { chapterMenu = ch.id },
                                onDismissMenu = { chapterMenu = null },
                                onRename = { renamingChapter = ch },
                                onStatus = { statusFor = ch },
                                onCard = { vm.open(Screen.ChapterCard(workId, ch.id)) },
                                onMove = { vm.writing.moveChapter(workId, ch.id, it) },
                                onDelete = { deletingChapter = ch },
                            )
                        }
                    }
                    item(key = "add:${v.id}", contentType = "add") {
                        GroupedListCell(first = false, last = true, Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) {
                            ActionRow("新的一章") { vm.writing.addChapter(workId, v.id) { vm.open(Screen.Editor(workId, it.id)) } }
                        }
                    }
                }
                item(key = "tip") {
                    Text(
                        "长按章节可以改名、改状态、写细纲卡、调整顺序或删除。定稿的章节正文锁定，要改先登记原因。",
                        color = c.secondary,
                        fontSize = 13.sp,
                        modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()).padding(horizontal = 28.dp),
                    )
                }
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
    // AI：键盘上方的条换成 AI 操作；灵感、按要求改等要先问一句；写作建议的列表展开没有。
    var aiBar by rememberSaveable { mutableStateOf(false) }
    var ideas by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var issuesOpen by remember { mutableStateOf(false) }
    val aiState by vm.writingAi.state.collectAsStateWithLifecycle()
    val aiLookup by vm.writingAi.lookup.collectAsStateWithLifecycle()
    // AI 在编辑器里写的那一段（这一章的才显示）；写的时候正文只读。
    val inlineAll by vm.writingAi.inline.collectAsStateWithLifecycle()
    val inline = inlineAll?.takeIf { it.workId == workId && it.chapterId == chapterId }
    var inlineDialog by remember { mutableStateOf(false) }
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
    LaunchedEffect(workId) { vm.writingAi.loadAssist(workId) }
    // 自动排版：先在键盘上方给预览（按类勾掉的不排），排好作为一步编辑写进正文。
    val formatSettings = assists[workId]?.format ?: FormatSettings()
    var formatOpen by remember(workId, chapterId) { mutableStateOf(false) }
    var formatSkip by remember(workId, chapterId) { mutableStateOf(emptySet<FormatKind>()) }
    // 定稿了、AI 开始写了：收起排版预览（这时不能排）。
    LaunchedEffect(locked, inline != null) { if (locked || inline != null) formatOpen = false }

    LaunchedEffect(workId, chapterId) {
        val text = vm.writing.open(workId, chapterId)
        savedText = text
        // 写作建议只查打开以后改过、新写的段：已有的段落算查过。
        vm.writingAi.baseline(workId, chapterId, text)
        state = TextFieldState(text).apply { edit { placeCursorAtEnd() } }
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
    val rules = ruleSet
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
    fun runEdit(action: WriteAction, instruction: String = "") {
        val st = current ?: return
        if (locked) {
            vm.showMessage("这一章已定稿：要改先解锁并登记原因")
            return
        }
        val target = editTarget(st.text.toString(), st.selection, action, instruction)
        if (target == null) vm.showMessage(if (action == WriteAction.CONTINUE) "先写一点，再让 AI 接着写" else "先选中文字，或把光标放在要改的那段")
        else runAi(target)
    }

    /** 从光标处让 AI 接着写（写的字先显示在光标处，采纳才进正文）。 */
    fun startInline(mode: InlineMode, instruction: String = "", atEnd: Boolean = false) {
        val st = current ?: return
        if (locked) {
            vm.showMessage("这一章已定稿：要改先解锁并登记原因")
            return
        }
        val text = st.text.toString()
        val at = if (atEnd) text.length else st.selection.max
        vm.writingAi.writeInline(workId, chapterId, text, at, mode, instruction)
    }

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

    fun onAiItem(item: AiBarItem) {
        val st = current ?: return
        when (item) {
            AiBarItem.Write -> if (locked) vm.showMessage("这一章已定稿：要改先解锁并登记原因") else inlineDialog = true
            is AiBarItem.Edit -> runEdit(item.action)
            AiBarItem.Custom -> {
                if (editTarget(st.text.toString(), st.selection, WriteAction.CUSTOM) == null) vm.showMessage("先选中文字，或把光标放在要改的那段")
                else asking = "想怎么改？" to { req -> runEdit(WriteAction.CUSTOM, req) }
            }
            AiBarItem.Ideas -> ideas = true
            AiBarItem.Review, AiBarItem.Consistency ->
                if (st.text.isBlank()) vm.showMessage("这一章还没有内容")
                else runAi(if (item == AiBarItem.Review) WriteAiTask.Review else WriteAiTask.Consistency)
        }
    }

    val chapters = work?.chapters.orEmpty()
    val index = chapters.indexOfFirst { it.id == chapterId }
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
                        val tail = (report?.hits?.size?.takeIf { it > 0 }?.let { " · $it 条不合规则" } ?: "") + if (realtimeOn) " · 写作建议已开" else ""
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
                                    val saveNow = { current?.text?.toString()?.let { vm.writing.save(workId, chapterId, it) } }
                                    val items = buildList<Pair<String, () -> Unit>> {
                                        // 常用的在前：这一章的细纲和状态，再是 AI，最后是预览、历史、改名。
                                        add("细纲卡" to { vm.open(Screen.ChapterCard(workId, chapterId)) })
                                        add((if (locked) "解锁修改" else "章节状态") to { statusDialog = true })
                                        // AI 写本章：在正文末尾接着写到本章结尾，写的字先显示，采纳才进正文。
                                        add("AI 写本章" to { startInline(InlineMode.TO_END, atEnd = true) })
                                        add("AI 写一版草稿" to { saveNow(); vm.open(Screen.ChapterDraft(workId, chapterId)) })
                                        add("三道审稿" to { saveNow(); vm.open(Screen.ChapterReview(workId, chapterId)) })
                                        add("整理本章进设定包" to { startSync() })
                                        add("本章资料" to { vm.open(Screen.ChapterBrief(workId, chapterId)) })
                                        add("故事账本" to { vm.open(Screen.WorkLedger(workId, chapterId)) })
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
                                j.instruction?.let { runEdit(WriteAction.CUSTOM, it) }
                            }
                        }
                    }
                }

                // 正文里标出来的：写作建议（按类上色、划线）、不合检查规则的（提醒色底）。只是显示，不改正文。
                // 一次检查出来的标记不整片冒出来、整片消失：新的淡入，去掉的在原处淡掉。
                val effects = rememberCoroutineScope()
                val accepted = remember(current) { AcceptSettle() }
                val textNow = current?.text?.toString().orEmpty()
                val issueSpans = remember(textNow, realtime.issues, realtimeOn) {
                    if (!realtimeOn || realtime.chapterId != chapterId) emptyList() else locateIssues(textNow, realtime.issues)
                }
                val ruleSpans = remember(textNow, report) {
                    report?.hits.orEmpty().flatMap { h -> h.ranges.filter { it.last < textNow.length }.map { it to h } }.take(300)
                }
                val marks = remember(issueSpans, ruleSpans, c) {
                    // 按“哪条建议”“哪条规则的第几处”认，不按位置：正文一改位置就变，淡入淡出不能跟着乱。
                    val nth = HashMap<String, Int>()
                    val ruleBack = c.warning.copy(alpha = 0.2f)
                    ruleSpans.map { (r, h) -> TextMark(h.key to nth.merge(h.key, 1) { a, b -> a + b }, r.first, r.last + 1, ruleBack, underline = false) } +
                        issueSpans.map { (r, i) -> TextMark(i, r.first, r.last + 1, suggestColor(i.kind, c).copy(alpha = 0.14f), underline = true) }
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
                val inlineFade = remember(current) { InlineFade(effects) }
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

                Box(Modifier.weight(1f).fillMaxWidth()) {
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
                                .semantics { contentDescription = "正文" }
                                .padding(start = 22.dp, end = 22.dp, top = padding.calculateTopPadding() + 12.dp, bottom = 12.dp),
                            // 每段首行空两格（只是显示，存的正文不带缩进），段和折行一眼分得清。
                            textStyle = TextStyle(color = scheme.onSurface, fontSize = 18.sp, lineHeight = 32.sp, textIndent = TextIndent(firstLine = 2.em)),
                            cursorBrush = SolidColor(scheme.primary),
                            lineLimits = TextFieldLineLimits.MultiLine(),
                            decorator = { inner ->
                                Box {
                                    if (current.text.isEmpty()) {
                                        Text("从这里开始写……\n换行就是另起一段。", color = c.tertiary, fontSize = 18.sp, lineHeight = 32.sp)
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                }
                // 键盘上方的几样（定稿锁定条、AI 的面板、写作建议、伏笔提醒）出现、收起都平滑：高矮慢慢变，正文框跟着慢慢变高变矮，
                // 正在写的那一行不猛跳；只淡入、升起，不横着动。减少动效时直接出现、直接消失。
                AnimatedVisibility(locked && chapter != null, enter = barEnter(), exit = barExit()) {
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
                val panel: EditorPanel = when {
                    current == null -> EditorPanel.None
                    inline != null -> EditorPanel.Inline(inline)
                    formatOpen -> EditorPanel.Format
                    aiPanel != null -> EditorPanel.Ai(aiPanel)
                    shownIssue != null && !locked -> EditorPanel.Suggestion(shownIssue)
                    shownRule != null && !rulesOpen -> EditorPanel.Rule(shownRule)
                    issuesOpen && realtimeOn -> EditorPanel.Issues
                    rulesOpen -> EditorPanel.Rules
                    realtimeOn && (realtime.issues.isNotEmpty() || realtime.checking || realtime.error != null || realtime.waiting != null) && realtime.chapterId == chapterId ->
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
                        is EditorPanel.Inline -> InlineBar(
                            p.write,
                            lookup = aiLookup,
                            onStop = { vm.writingAi.stopInline() },
                            onAccept = {
                                val w = p.write
                                val done = vm.writingAi.acceptInline(st.text.toString())
                                if (done == null) {
                                    vm.showMessage("正文已经改动，没法插进去")
                                } else {
                                    st.edit {
                                        replace(0, length, done.first)
                                        selection = TextRange(done.second)
                                    }
                                    // 刚放进正文的那一段从浅色过渡到正文色。
                                    effects.launch { accepted.play(w.at until w.at + w.text.length) }
                                }
                            },
                            onRetry = { vm.writingAi.retryInline() },
                            onDiscard = { vm.writingAi.discardInline() },
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
                            onAccept = { result ->
                                val task = p.state.task as WriteAiTask.Edit
                                when (applyEdit(st, task, result)) {
                                    EditApply.DONE -> vm.writingAi.accepted(workId, task.action, task.original, result)
                                    EditApply.MISSING -> vm.showMessage("那段原文已经改动，没找到要替换的地方")
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
                            onFix = {
                                if (!applyIssue(st, p.issue)) vm.showMessage("那段已经改动了")
                                vm.writingAi.dismissIssue(p.issue)
                            },
                            onIgnore = { vm.writingAi.ignoreIssue(p.issue) },
                            onMute = { vm.writingAi.muteKind(workId, p.issue.kind) },
                        )
                        is EditorPanel.Rule -> RuleNote(p.hit.message) { rulesOpen = true }
                        EditorPanel.Issues -> IssuesPanel(
                            realtime,
                            onFix = { i ->
                                if (!applyIssue(st, i)) vm.showMessage("那段已经改动了")
                                vm.writingAi.dismissIssue(i)
                            },
                            onIgnore = { vm.writingAi.ignoreIssue(it) },
                            onClose = { issuesOpen = false },
                        )
                        EditorPanel.Rules -> RulesPanel(
                            report,
                            hasRules = rules?.isEmpty == false,
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
                        EditorPanel.Realtime -> RealtimeLine(realtime) { if (realtime.issues.isNotEmpty()) issuesOpen = true }
                        EditorPanel.None -> Spacer(Modifier.fillMaxWidth())
                    }
                }
                AnimatedVisibility(due.isNotEmpty() && aiPanel == null && !rulesOpen && !issuesOpen, enter = barEnter(), exit = barExit()) {
                    EditorNote("这一章计划回收的伏笔：" + due.joinToString("、")) { vm.open(Screen.ChapterBrief(workId, chapterId)) }
                }
                // 键盘上方的条：左边 ✦ 在标点和 AI 操作之间切换；上面一道细线和正文隔开。
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                Row(Modifier.fillMaxWidth().background(c.background), verticalAlignment = Alignment.CenterVertically) {
                    // ✦ 的底色、图标色渐变过去（[Motion.SHORT]）。
                    val toggleBack by animateColorAsState(if (aiBar) c.tint.copy(alpha = 0.15f) else c.tint.copy(alpha = 0f), Motion.standard(Motion.SHORT), label = "aiToggleBack")
                    val toggleTint by animateColorAsState(if (aiBar) c.tint else c.secondary, Motion.standard(Motion.SHORT), label = "aiToggleTint")
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(toggleBack)
                            .combinedClickableCompat { aiBar = !aiBar }
                            .semantics { contentDescription = if (aiBar) "换回标点" else "AI 写作" }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Icon(LingYueIcons.Sparkle, contentDescription = null, tint = toggleTint, modifier = Modifier.size(20.dp))
                    }
                    // 两排工具上下换：AI 操作从下面上来，换回标点时反过来（[Motion.MEDIUM]）。
                    AnimatedContent(
                        aiBar,
                        modifier = Modifier.weight(1f),
                        transitionSpec = {
                            val up = targetState
                            Effects.enter(slideInVertically(Motion.standard(Motion.MEDIUM)) { if (up) it else -it } + fadeIn(Motion.standard(Motion.SHORT))) togetherWith
                                Effects.exit(slideOutVertically(Motion.standard(Motion.MEDIUM)) { if (up) -it else it } + fadeOut(Motion.exit())) using
                                SizeTransform(clip = true) { _, _ -> snap() }
                        },
                        label = "toolRow",
                    ) { ai ->
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (ai) {
                                AI_BAR.forEach { item ->
                                    Box(
                                        Modifier
                                            .padding(horizontal = 2.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .combinedClickableCompat { onAiItem(item) }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                    ) {
                                        Text(item.label, color = c.label, fontSize = 15.sp)
                                    }
                                }
                            } else {
                                QUICK_INSERTS.forEach { s ->
                                    Box(
                                        Modifier
                                            .padding(horizontal = 2.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .combinedClickableCompat {
                                                when {
                                                    locked -> vm.showMessage("这一章已定稿：要改先解锁并登记原因")
                                                    inline != null -> vm.showMessage("AI 写的还没采纳或撤回，先处理它再改正文")
                                                    else -> current?.let { insert(it, s) }
                                                }
                                            }
                                            .semantics { contentDescription = "插入$s" }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        Text(s, color = c.label, fontSize = 17.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
            }
            if (renaming && chapter != null) {
                NameDialog("章名", chapter.title, "保存", onDismiss = { renaming = false }) {
                    renaming = false
                    vm.writing.renameChapter(workId, chapterId, it)
                }
            }
            if (statusDialog && chapter != null) {
                StatusDialog(chapter, ruleHits = report?.hits?.size, onDismiss = { statusDialog = false }) { st, reason, canon ->
                    statusDialog = false
                    vm.writing.setStatus(workId, chapterId, st, reason, canon)
                    if (st == ChapterStatus.FINAL && wantsSyncTip()) syncTip = true
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
            if (inlineDialog) {
                InlineModeDialog(onDismiss = { inlineDialog = false }) { mode ->
                    inlineDialog = false
                    if (mode == InlineMode.CUSTOM) asking = "想让 AI 怎么写？" to { req -> startInline(mode, req) }
                    else startInline(mode)
                }
            }
            if (ideas) {
                IdeasDialog(onDismiss = { ideas = false }) { kind ->
                    ideas = false
                    when (kind) {
                        IdeaKind.NAMES -> asking = "给什么起名？" to { what -> runAi(WriteAiTask.Idea(kind, what)) }
                        IdeaKind.ASK -> asking = "想问什么？" to { q -> runAi(WriteAiTask.Idea(kind, q)) }
                        else -> runAi(WriteAiTask.Idea(kind))
                    }
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

/** 光标在一处划线上停稳多久才显示那条建议（光标掠过时不一闪一闪）。 */
private const val CARET_SETTLE = 150L

/** 键盘上方那块放什么：一次放一样，换了种类才升起、落下，同一种只换里面的字。 */
private sealed interface EditorPanel {
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

/** 正文里的一处标记：[key] 认的是哪条建议、哪条规则的第几处，不是位置（正文一改位置就变）。 */
private data class TextMark(val key: Any, val start: Int, val end: Int, val color: Color, val underline: Boolean) {
    fun style(alpha: Float) = SpanStyle(
        background = color.copy(alpha = color.alpha * alpha),
        // 划线没法单独调透明度：淡到一半再画上，和底色一起出来、一起走。
        textDecoration = if (underline && alpha >= 0.5f) TextDecoration.Underline else null,
    )
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
