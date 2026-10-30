package com.xycs.lingyuereader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.LedgerChapter
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ChapterBrief
import com.xycs.lingyuereader.data.ChapterBriefs
import com.xycs.lingyuereader.data.Foreshadow
import com.xycs.lingyuereader.data.PackKind
import com.xycs.lingyuereader.data.LEDGER_ASPECTS
import com.xycs.lingyuereader.data.LedgerEntry
import com.xycs.lingyuereader.data.LedgerKind
import com.xycs.lingyuereader.data.LedgerOp
import com.xycs.lingyuereader.data.LedgerOrigin
import com.xycs.lingyuereader.data.LedgerVersion
import com.xycs.lingyuereader.data.StoryLedger
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 列表右边的来源小标签。 */
private val LedgerOrigin.short: String
    get() = when (this) {
        LedgerOrigin.IMPORT -> "导入"
        LedgerOrigin.SYNC -> "整理"
        LedgerOrigin.MANUAL -> "手动"
    }

/** 编辑对话框里选类别的分段控件（类别名太长放不下，用短名，下面写说明）。 */
private val KIND_TABS = listOf(
    LedgerKind.STATE to "状态",
    LedgerKind.EVENT to "事",
    LedgerKind.KNOW to "知情",
    LedgerKind.PROMISE to "伏笔",
    LedgerKind.TIME to "时间",
)

/** 账本页上叠着的一层：导入前的预览、历史版本、某个历史版本的条目。 */
private sealed interface LedgerLayer {
    val depth: Int

    data class Import(val entries: List<LedgerEntry>, val picked: Set<String>) : LedgerLayer {
        override val depth = 1
    }

    data object History : LedgerLayer {
        override val depth = 1
    }

    data class Version(val version: LedgerVersion, val entries: List<LedgerEntry>) : LedgerLayer {
        override val depth = 2
    }
}

/** 列表现在看的是什么：翻到哪一章（空是全部）、只看谁（空是所有人）。换了就淡出淡入。 */
private data class LedgerView(val chapter: LedgerChapter?, val person: String?)

/**
 * 故事账本：随章推进、会变的东西——人物状态、发生的事、知情、伏笔、时间线。
 * 伏笔标签合并了原来的“伏笔”页：该回收了 / 还没回收 / 已回收、设定包伏笔表里还没导入的、来源文档（[promises] 为真时直接翻到这一标签）。
 * 默认按类别列出全部条目（人物状态再按人物归在一起）；“看到第几章”选了某一章，就只看到这一章为止的状态（[StoryLedger.snapshot]），
 * 写前对照、改旧章时用。点一条改，右上角加一条；菜单里从设定包导入、导出到设定包、历史版本。
 */
@Composable
fun WorkLedgerScreen(vm: AppViewModel, workId: String, chapterId: String? = null, promises: Boolean = false) {
    val ledgers by vm.writing.ledgers.collectAsStateWithLifecycle()
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val work = works.firstOrNull { it.id == workId }
    val packs by vm.writing.packs.collectAsStateWithLifecycle()
    val pack = packs[workId]
    LaunchedEffect(workId) {
        vm.writing.loadLedger(workId)
        vm.writing.loadPack(workId)
    }
    val entries = ledgers[workId]
    // 伏笔标签：设定包伏笔表里的条目（还没导入的也列）和写到了第几章（算哪些该回收了）；设定包、账本变了重读。
    var foreshadowSource by remember { mutableStateOf<Pair<List<Foreshadow>, Int>?>(null) }
    LaunchedEffect(workId, pack, entries) { foreshadowSource = vm.writing.foreshadows(workId) }
    var closing by remember { mutableStateOf<WritingForeshadow?>(null) }
    // 能翻到的章（章号要读细纲卡，在后台算）；章节改了重算。
    var chapters by remember { mutableStateOf<List<LedgerChapter>?>(null) }
    LaunchedEffect(workId, work) { chapters = vm.writing.ledgerChapters(workId) }
    // 看到哪一章：空是全部。从编辑器进来时直接翻到那一章。
    var at by rememberSaveable { mutableStateOf(chapterId) }
    var person by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // 正在改的一条（新加的也在这里，[adding] 为真）；要删的一条。
    var editing by remember { mutableStateOf<LedgerEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LedgerEntry?>(null) }
    var visualTab by rememberSaveable {
        mutableStateOf(
            when {
                promises -> LedgerVisualTab.PROMISES
                chapterId == null -> LedgerVisualTab.PEOPLE
                else -> LedgerVisualTab.RECORDS
            },
        )
    }
    var quickState by remember { mutableStateOf<LedgerQuickState?>(null) }
    var quickKnowledge by remember { mutableStateOf<LedgerQuickKnowledge?>(null) }
    var graphUndo by remember { mutableStateOf<LedgerGraphUndo?>(null) }
    var layer by remember { mutableStateOf<LedgerLayer?>(null) }
    val snackbar = rememberWritingMessages(vm)
    val scope = rememberCoroutineScope()

    val chapter = chapters?.firstOrNull { it.id == at }
    val subjects = remember(entries) { entries.orEmpty().map { it.subject }.filter { it.isNotBlank() }.distinct().sorted() }

    fun startImport() {
        scope.launch {
            try {
                val found = vm.writing.ledgerImportPreview(workId)
                if (found.isEmpty()) vm.showMessage("设定包的察觉表、伏笔表里没有找到账本里还没有的条目")
                else layer = LedgerLayer.Import(found, found.map { it.id }.toSet())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.showMessage("读设定包失败：${e.message ?: "读取出错"}")
            }
        }
    }

    fun startAdding() {
        editing = LedgerEntry(kind = LedgerKind.STATE, text = "", from = chapter?.no)
        adding = true
    }

    // 改一条都带着界面上看到的旧样子：账本里那一条已经被整理、审稿页或别处改过时，存储层拒绝，不拿旧的覆盖。
    fun saveGraph(before: LedgerEntry?, after: LedgerEntry, label: String, failed: () -> Unit = {}) {
        val op = if (before == null) LedgerOp.Add(after) else LedgerOp.Update(after, expected = before)
        vm.writing.editLedger(workId, listOf(op), label, failed) { added ->
            val saved = vm.writing.ledgers.value[workId]?.firstOrNull { it.id == after.id }
            if (before == null && added == 0) vm.showMessage("账本里已有同样的一条")
            else if (saved == null || saved.copy(updatedAt = 0) != after.copy(updatedAt = 0)) vm.showMessage("条目已变化，刚才的改动没有写入")
            else {
                // 撤销时拿刚写进去的这一条（连同写入时间）去比对：之后又被改过就不撤。
                graphUndo = LedgerGraphUndo(before, saved, label)
                vm.showMessage("已保存；可在账本页撤销上一步")
            }
        }
    }

    fun undoGraph(change: LedgerGraphUndo) {
        val op = ledgerGraphUndoOp(vm.writing.ledgers.value[workId].orEmpty(), change.before, change.after)
        if (op == null) {
            graphUndo = null
            vm.showMessage("这条记录已有其他改动，请从历史版本核对")
        } else {
            vm.writing.editLedger(workId, listOf(op), "撤销" + change.label, failed = { if (graphUndo == change) graphUndo = null }) {
                if (graphUndo == change) graphUndo = null
                vm.showMessage("已撤销上一步")
            }
        }
    }

    fun back() {
        layer = when (layer) {
            null -> {
                vm.back()
                null
            }
            is LedgerLayer.Version -> LedgerLayer.History
            else -> null
        }
    }
    BackHandler(enabled = layer != null) { back() }

    SubPageScaffold(
        when (val l = layer) {
            null -> "故事账本"
            is LedgerLayer.Import -> "从设定包导入"
            LedgerLayer.History -> "历史版本"
            is LedgerLayer.Version -> whenLabel(l.version.at)
        },
        onBack = { back() },
        snackbar = snackbar,
        actions = {
            if (layer == null) {
                IconButton(onClick = { startAdding() }, enabled = entries != null) { Icon(LingYueIcons.Add, contentDescription = "添加一条") }
                Box(Modifier.padding(end = 12.dp)) {
                    IconButton(onClick = { menu = true }) { Icon(LingYueIcons.More, contentDescription = "更多") }
                    OverlayListPopup(show = menu, alignment = PopupPositionProvider.Align.End, onDismissRequest = { menu = false }) {
                        GlassMenu {
                            ListPopupColumn {
                                listOf<Pair<String, () -> Unit>>(
                                    "从设定包导入" to { startImport() },
                                    "导出到设定包" to { vm.writing.exportLedger(workId) { vm.showMessage("已导出为《${it.title}》") } },
                                    "历史版本" to { layer = LedgerLayer.History },
                                ).forEach { (label, action) ->
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
    ) { padding ->
        val contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp)
        // 账本 ↔ 导入预览 ↔ 历史版本：按层级左右滑（进去从右边推入，返回从左边回来），和标题一起换；减少动效时直接换。
        AnimatedContent(
            layer,
            contentKey = {
                when (it) {
                    null -> "list"
                    is LedgerLayer.Import -> "import"
                    LedgerLayer.History -> "history"
                    is LedgerLayer.Version -> "version:${it.version.at}"
                }
            },
            transitionSpec = {
                val forward = (targetState?.depth ?: 0) > (initialState?.depth ?: 0)
                Effects.enter(slideInHorizontally(Motion.standard(Motion.MEDIUM)) { if (forward) it else -it / 3 } + fadeIn(Motion.standard(Motion.SHORT)))
                    .togetherWith(Effects.exit(slideOutHorizontally(Motion.standard(Motion.MEDIUM)) { if (forward) -it / 3 else it } + fadeOut(Motion.exit())))
            },
            label = "ledgerLayer",
        ) { shown ->
            when (shown) {
                is LedgerLayer.Import -> LedgerImport(shown, contentPadding, onChange = { layer = it }) { picked ->
                    layer = null
                    vm.writing.importLedger(workId, picked) { n -> vm.showMessage(if (n > 0) "已导入 $n 条" else "要导入的条目账本里都有了") }
                }
                LedgerLayer.History -> LedgerHistory(vm, workId, contentPadding) { v ->
                    scope.launch {
                        val old = vm.writing.ledgerVersion(workId, v.at)
                        if (old == null) vm.showMessage("这个版本读不出来了") else layer = LedgerLayer.Version(v, old)
                    }
                }
                is LedgerLayer.Version -> LedgerVersionList(shown, contentPadding) {
                    vm.writing.restoreLedger(workId, shown.version.at) {
                        layer = null
                        vm.showMessage("已换回${whenLabel(shown.version.at)}的版本")
                    }
                }
                null -> {
                    val list = entries
                    when {
                        list == null || (at != null && chapters == null) ->
                            Box(Modifier.fillMaxSize().padding(contentPadding)) { LoadingState("正在读故事账本……") }
                        // 账本空、但设定包伏笔表里有条目时照常显示分段标签：伏笔标签里列着它们，能标为已回收。
                        list.isEmpty() && foreshadowSource?.first.isNullOrEmpty() -> LazyColumn(Modifier.fillMaxSize().overScrollVertical(), contentPadding = contentPadding, overscrollEffect = null) {
                            item(key = "empty") {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    EmptyState(
                                        LingYueIcons.ListView,
                                        "账本还是空的",
                                        "账本记随章推进、会变的东西：人物在哪、发生了什么、谁知道了什么、埋了哪些伏笔、故事里过了多久。" +
                                            "可以从设定包的察觉表、伏笔表导入，也可以自己一条条加；写完一章整理时也会记进来。",
                                        action = "从设定包导入" to { startImport() },
                                    )
                                    PillButton("手动添加", onClick = { startAdding() })
                                }
                            }
                        }
                        else -> {
                            // 换章、换人物时整块淡出淡入（过渡时新旧两份同时在）；减少动效时直接换。
                            AnimatedContent(
                                LedgerView(chapter, person?.takeIf { it in subjects }),
                                transitionSpec = { Motion.fadeThrough() },
                                label = "ledgerView",
                            ) { view ->
                                LazyColumn(Modifier.fillMaxSize().overScrollVertical(), contentPadding = contentPadding, overscrollEffect = null) {
                                    item(key = "head") {
                                        LedgerHead(view, list.size, subjects, onPick = { picking = true }, onPerson = { person = it })
                                    }
                                    item(key = "graph:tabs") {
                                        LedgerVisualTabs(visualTab) { visualTab = it }
                                    }
                                    graphUndo?.let { change ->
                                        item(key = "graph:undo") {
                                            GroupedSection {
                                                row { ActionRow("撤销上一步 · " + change.label) { undoGraph(change) } }
                                            }
                                        }
                                    }
                                    val open: (LedgerEntry) -> Unit = { e ->
                                        adding = false
                                        editing = e
                                    }
                                    when (visualTab) {
                                        LedgerVisualTab.PEOPLE -> ledgerPeopleGraph(
                                            list, view.chapter, view.person,
                                            onState = { quickState = it },
                                            onRecords = { visualTab = LedgerVisualTab.RECORDS },
                                        )
                                        LedgerVisualTab.PROMISES -> ledgerPromiseGraph(
                                            list, view.chapter, chapters.orEmpty(), view.person,
                                            onPayoff = { e, payoff, failed -> saveGraph(e, e.copy(payoff = payoff), "调整伏笔计划", failed) },
                                            onOpen = open,
                                            sources = LedgerPromiseSources(
                                                legacy = foreshadowSource?.first.orEmpty(),
                                                latest = foreshadowSource?.second ?: 0,
                                                docs = pack.orEmpty().filter { it.kind == PackKind.FORESHADOW }.map { it.id to it.title },
                                            ),
                                            onClose = { closing = it },
                                            onOpenDoc = { vm.open(Screen.PackDoc(workId, it)) },
                                        )
                                        LedgerVisualTab.KNOWLEDGE -> ledgerKnowledgeGraph(
                                            list, view.chapter, view.person,
                                            onKnown = open, onUnknown = { quickKnowledge = it },
                                        )
                                        LedgerVisualTab.RECORDS -> {
                                            if (view.chapter == null) allEntries(list, view.person, open)
                                            else snapshotEntries(list, view.chapter, view.person, open)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (picking) {
            ChapterPickDialog(chapters.orEmpty(), at, onDismiss = { picking = false }) {
                picking = false
                at = it
            }
        }
        quickState?.let { target ->
            LedgerQuickStateDialog(target, chapter, chapters.orEmpty(), onDismiss = { quickState = null }) { before, after ->
                quickState = null
                saveGraph(before, after, "手动登记人物状态")
            }
        }
        quickKnowledge?.let { target ->
            LedgerQuickKnowledgeDialog(target, chapter, chapters.orEmpty(), onDismiss = { quickKnowledge = null }) { after ->
                quickKnowledge = null
                saveGraph(null, after, "手动登记知情")
            }
        }
        editing?.let { e ->
            LedgerEntryDialog(
                e,
                isNew = adding,
                subjects = subjects,
                onDismiss = { editing = null },
                onDelete = if (adding) null else ({
                    editing = null
                    deleting = e
                }),
            ) { changed ->
                editing = null
                if (adding) {
                    vm.writing.editLedger(workId, listOf(LedgerOp.Add(changed.copy(origin = LedgerOrigin.MANUAL))), "手动添加") { n ->
                        if (n == 0) vm.showMessage("账本里已经有一样的一条")
                    }
                } else {
                    vm.writing.editLedger(workId, listOf(LedgerOp.Update(changed, expected = e)), "手动修改")
                }
            }
        }
        closing?.let { f ->
            GlassDialog(show = true, title = f.display.what, summary = ChapterBrief.foreshadowWhen(f.display).trim('（', '）').ifEmpty { null }, onDismissRequest = { closing = null }) {
                DialogButtons(
                    onDismiss = { closing = null },
                    onConfirm = {
                        closing = null
                        val source = foreshadowSource
                        val latest = source?.second?.takeIf { it > 0 }
                        val current = mergeWritingForeshadows(source?.first.orEmpty(), vm.writing.ledgers.value[workId].orEmpty())
                            .firstOrNull { ChapterBriefs.same(it.display.what, f.display.what) } ?: f
                        // 先写账本（当前状态）；伏笔表里那一行跟着标上，旧表镜像失败也不会让界面与 AI 用不同的回收状态。
                        vm.writing.editLedger(workId, listOf(current.close(latest)), "回收伏笔") {
                            current.legacy.forEach { old -> vm.writing.markForeshadow(workId, old) }
                        }
                    },
                    confirmText = "标为已回收",
                )
            }
        }
        deleting?.let { e ->
            GlassDialog(
                show = true,
                title = "删掉这一条",
                summary = "“${e.text.take(40)}”会从账本里删掉。删之前的账本存在历史版本里，可以换回来。",
                onDismissRequest = { deleting = null },
            ) {
                DialogButtons(
                    onDismiss = { deleting = null },
                    onConfirm = {
                        deleting = null
                        vm.writing.editLedger(workId, listOf(LedgerOp.Remove(e.id, expected = e)), "手动删除")
                    },
                    confirmText = "删除",
                    destructive = true,
                )
            }
        }
    }
}

/** 顶上一组：看到第几章、只看谁。 */
@Composable
private fun LedgerHead(view: LedgerView, count: Int, subjects: List<String>, onPick: () -> Unit, onPerson: (String?) -> Unit) {
    val ch = view.chapter
    GroupedSection(
        footer = if (ch == null) "共 $count 条，按类别列出。点一条可以改。"
        else "到「${ch.title}」为止：每个人在哪、知道什么、还欠着哪些伏笔。写这一章时 AI 拿到的就是这一份。",
    ) {
        row { NavRow("看到第几章", value = ch?.let { "第 ${it.no} 章" } ?: "全部") { onPick() } }
        if (subjects.isNotEmpty()) {
            row {
                PickerRow("人物", listOf("" to "全部") + subjects.map { it to it }, view.person.orEmpty()) { onPerson(it.ifEmpty { null }) }
            }
        }
    }
}

/** 一组条目：组名、连成一张卡片的各条、卡片下的说明。空的不出。键带上 [key]，同一条在别的组里也不重。 */
private fun LazyListScope.ledgerGroup(key: String, header: String, footer: String?, list: List<LedgerEntry>, onOpen: ((LedgerEntry) -> Unit)?) {
    if (list.isEmpty()) return
    // 增删、换位时其余的条目滑到新位置，新的淡入、删掉的淡出；减少动效时不动。
    item(key = "$key:head", contentType = "header") { GroupedListHeader(header, Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) }
    itemsIndexed(list, key = { _, e -> "$key:${e.id}" }, contentType = { _, _ -> "entry" }) { i, e ->
        GroupedListCell(i == 0, i == list.lastIndex, Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()), gap = if (footer != null) 8.dp else 12.dp) {
            LedgerRow(e, onOpen?.let { open -> { open(e) } })
        }
    }
    if (footer != null) item(key = "$key:foot", contentType = "footer") { GroupedListFooter(footer, Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) }
}

/** 同一人物的状态按方面排（[LEDGER_ASPECTS] 的顺序，自己写的在后），同一方面按章。 */
private fun aspectOrder(e: LedgerEntry) = LEDGER_ASPECTS.indexOf(e.aspect).let { if (it < 0) LEDGER_ASPECTS.size else it }

/** “全部”：按类别分组（[LedgerKind] 的顺序），组名是类别，组下写说明；人物状态再按人物分开。 */
private fun LazyListScope.allEntries(entries: List<LedgerEntry>, person: String?, onOpen: ((LedgerEntry) -> Unit)?) {
    val shown = if (person == null) entries else entries.filter { it.subject == person }
    LedgerKind.entries.forEach { kind ->
        val list = shown.filter { it.kind == kind }
        if (kind == LedgerKind.STATE) {
            // 没写是谁的放最后。
            val people = list.groupBy { it.subject }.toList().sortedWith(compareBy({ it.first.isBlank() }, { it.first }))
            people.forEachIndexed { i, (who, group) ->
                ledgerGroup(
                    "state:$who",
                    if (who.isBlank()) kind.label else "${kind.label} · $who",
                    if (i == people.lastIndex) kind.hint else null,
                    group.sortedWith(compareBy({ aspectOrder(it) }, { it.from ?: -1 })),
                    onOpen,
                )
            }
        } else {
            ledgerGroup(kind.name, kind.label, kind.hint, list.sortedBy { it.from ?: -1 }, onOpen)
        }
    }
}

/** 翻到某一章：到这一章为止的状态（[StoryLedger.snapshot]），选了人物时只看和他有关的。 */
private fun LazyListScope.snapshotEntries(entries: List<LedgerEntry>, chapter: LedgerChapter, person: String?, onOpen: (LedgerEntry) -> Unit) {
    val s = StoryLedger.snapshot(entries, chapter.no, chapter.volume, listOfNotNull(person))
    if (s.isEmpty) {
        item(key = "nothing") {
            GroupedSection(modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem())) { row { EmptyRow("到第 ${chapter.no} 章为止，账本里还没有记下什么") } }
        }
        return
    }
    ledgerGroup("time", "时间", null, s.time, onOpen)
    ledgerGroup("states", "人物状态", "同一人物同一方面只列最新的一条。", s.states, onOpen)
    ledgerGroup("due", "该收的伏笔", "计划在这一章收。", s.due, onOpen)
    ledgerGroup("overdue", "过期没收的伏笔", "计划收的章已经过了，还开着。", s.overdue, onOpen)
    ledgerGroup("events", "发生的事", null, s.events, onOpen)
    ledgerGroup("known", "知情", null, s.known, onOpen)
    ledgerGroup("open", "开着的伏笔", "埋下了还没收：别忘了，也别提前揭开。", s.open - s.due.toSet() - s.overdue.toSet(), onOpen)
    ledgerGroup("notYet", "还不知道的事", "到这一章人物还不知道，后面才知道：不要让人物提前知道或说破。", s.notYet, onOpen)
    ledgerGroup("secrets", "只有作者知道的", "只用来把握方向，不写进正文，旁白也不说破。", s.secrets, onOpen)
}

/**
 * 一条：内容；下面谁、哪方面、第几章起（伏笔是第几章埋、计划第几章收）；右边来源，伏笔收了的标“已收”，只有作者知道的标出来。
 * [onClick] 为空时只看不能点（历史版本里）。
 */
@Composable
private fun LedgerRow(e: LedgerEntry, onClick: (() -> Unit)?) {
    val c = LocalGroupedColors.current
    val subtitle = listOf(
        e.subject,
        e.aspect.takeIf { e.kind == LedgerKind.STATE }.orEmpty(),
        e.whenLabel,
        e.via.takeIf { e.kind == LedgerKind.KNOW && it.isNotBlank() }?.let { "消息来自$it" }.orEmpty(),
    ).filter { it.isNotBlank() }.joinToString(" · ")
    GroupedRow(onClick = onClick) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.text, color = c.label, fontSize = TypeScale.Body, fontWeight = FontWeight.Medium)
            if (subtitle.isNotEmpty()) Text(subtitle, color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine, maxLines = 2)
        }
        Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (e.secret) Tag("只有作者知道", c.warning)
            if (e.kind == LedgerKind.PROMISE && e.done) Tag("已收", c.success)
            Tag(e.origin.short, c.secondary)
        }
        if (onClick != null) {
            Icon(LingYueIcons.ChevronForward, contentDescription = null, tint = c.tertiary, modifier = Modifier.padding(start = 4.dp).size(16.dp))
        }
    }
}

/** 章多到一屏放不下时，选章对话框上方才出“快速定位”滑条。 */
private const val CHAPTER_PICK_QUICK = 6

/**
 * 选看到第几章：“全部”和作品的各章（章号按设定算）。几百章也不卡：一章一行懒加载，打开时滚到选中的那章。
 * 章多时上方一条“快速定位”滑条：拖到第几章，列表就滚到那一章（只是滚过去，点了那一行才算选上）；列表滚动时滑条跟着走。
 */
@Composable
private fun ChapterPickDialog(chapters: List<LedgerChapter>, selected: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val options = listOf<Pair<String?, String>>(null to "全部") + chapters.map { it.id to it.title }
    GlassDialog(show = true, title = "看到第几章", summary = "选了某一章，只看到这一章为止的状态。", onDismissRequest = onDismiss) {
        val state = rememberLazyListState(initialFirstVisibleItemIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0))
        if (chapters.size > CHAPTER_PICK_QUICK) {
            val scope = rememberCoroutineScope()
            // 拖动途中用手指的位置；松手后跟着列表顶上那一章（第 0 行是“全部”，按第 1 章算）。
            var dragging by remember { mutableStateOf<Float?>(null) }
            val top by remember { derivedStateOf { state.firstVisibleItemIndex.coerceIn(1, chapters.size) } }
            val at = dragging?.roundToInt()?.coerceIn(1, chapters.size) ?: top
            LabeledSliderRow(
                title = "快速定位",
                value = dragging ?: top.toFloat(),
                valueText = "第 ${chapters[at - 1].no} 章",
                valueRange = 1f..chapters.size.toFloat(),
                onValueChangeFinished = { dragging = null },
            ) { v ->
                dragging = v
                val target = v.roundToInt().coerceIn(1, chapters.size)
                // 拖到第 1 章时滚到最顶上，“全部”也露出来。
                scope.launch { state.scrollToItem(if (target == 1) 0 else target) }
            }
        }
        // 有滑条时列表矮一点，整个对话框的高矮不变。
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (chapters.size > CHAPTER_PICK_QUICK) 340.dp else 420.dp), state = state) {
            items(options, key = { it.first ?: "" }) { (id, label) ->
                val no = chapters.firstOrNull { it.id == id }?.no
                Box(Modifier.clip(RoundedCornerShape(Radius.Medium))) {
                    CheckRow(label, selected = id == selected, subtitle = no?.let { "第 $it 章" } ?: "列出所有条目") { onPick(id) }
                }
            }
        }
    }
}

/** 一排常用的选项（胶囊按钮），点了填进框里；放不下换行，不切掉最后一个。 */
@Composable
private fun Choices(options: List<String>, onPick: (String) -> Unit) {
    if (options.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { PillButton(it, onClick = { onPick(it) }) }
    }
}

/**
 * 加一条、改一条：类别、谁、哪方面、内容、第几章起止（伏笔是第几章埋、计划第几章收、收了没有）、消息从哪来、只有作者知道；
 * 依据原文只看不改。各类别用不到的格子不显示，保存时也清掉。
 */
@Composable
private fun LedgerEntryDialog(
    initial: LedgerEntry,
    isNew: Boolean,
    subjects: List<String>,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (LedgerEntry) -> Unit,
) {
    var kind by remember { mutableStateOf(initial.kind) }
    var subject by remember { mutableStateOf(initial.subject) }
    var aspect by remember { mutableStateOf(initial.aspect) }
    var text by remember { mutableStateOf(initial.text) }
    var from by remember { mutableStateOf(initial.from) }
    var until by remember { mutableStateOf(initial.until) }
    var payoff by remember { mutableStateOf(initial.payoff) }
    var done by remember { mutableStateOf(initial.done) }
    var closed by remember { mutableStateOf(initial.closed) }
    var via by remember { mutableStateOf(initial.via) }
    var secret by remember { mutableStateOf(initial.secret) }

    fun build(): LedgerEntry {
        val promise = kind == LedgerKind.PROMISE
        val ends = kind == LedgerKind.STATE || kind == LedgerKind.EVENT || kind == LedgerKind.KNOW
        return initial.copy(
            kind = kind,
            text = text.trim(),
            subject = if (kind == LedgerKind.TIME) "" else subject.trim(),
            aspect = if (kind == LedgerKind.STATE) aspect.trim() else "",
            from = from,
            until = if (ends) until else null,
            payoff = if (promise) payoff else null,
            done = promise && done,
            closed = if (promise && done) closed else null,
            via = if (kind == LedgerKind.KNOW) via.trim() else "",
            secret = kind != LedgerKind.TIME && secret,
        )
    }

    GlassDialog(show = true, title = if (isNew) "添加一条" else "改一条", onDismissRequest = onDismiss) {
        val c = LocalGroupedColors.current
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SegmentedControl(KIND_TABS, kind) { kind = it }
            Text(kind.label + "：" + kind.hint, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine, color = c.secondary)
            if (kind != LedgerKind.TIME) {
                val needed = kind == LedgerKind.STATE || kind == LedgerKind.KNOW
                GroupedTextField(value = subject, onValueChange = { subject = it }, label = if (needed) "谁" else "谁（可不填）", singleLine = true, modifier = Modifier.fillMaxWidth())
                Choices(subjects.filter { it != subject.trim() }) { subject = it }
            }
            if (kind == LedgerKind.STATE) {
                GroupedTextField(value = aspect, onValueChange = { aspect = it }, label = "哪方面（可不填）", singleLine = true, modifier = Modifier.fillMaxWidth())
                Choices(LEDGER_ASPECTS.filter { it != aspect.trim() }) { aspect = it }
            }
            GroupedTextField(value = text, onValueChange = { text = it }, label = "内容", modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 160.dp))
            // 章号用步进器：－ ＋ 一章章调，点数字直接输入；空着是“不填”。计划收章、写到哪章止可以超过现有的章。
            Column {
                val fromTitle = when (kind) {
                    LedgerKind.PROMISE -> "第几章埋"
                    LedgerKind.TIME -> "第几章"
                    else -> "从第几章起"
                }
                ChapterStepperRow(fromTitle, from, chapters = emptyList()) { from = it }
                when (kind) {
                    LedgerKind.STATE, LedgerKind.EVENT, LedgerKind.KNOW ->
                        ChapterStepperRow("到第几章止", until, chapters = emptyList(), start = from ?: 1) { until = it }
                    LedgerKind.PROMISE ->
                        ChapterStepperRow("计划第几章收", payoff, chapters = emptyList(), start = (from ?: 0) + 1) { payoff = it }
                    LedgerKind.TIME -> Unit
                }
            }
            if (initial.from == null && initial.volume != null && from == null) {
                Text("导入时只知道是第 ${initial.volume} 卷起；填了章号就按章算。", fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine, color = c.secondary)
            }
            if (kind == LedgerKind.PROMISE) {
                Box(Modifier.clip(RoundedCornerShape(Radius.Medium))) { ToggleRow("已经收了", done) { done = it } }
                if (done) ChapterStepperRow("第几章收的", closed, chapters = emptyList(), start = payoff ?: from ?: 1) { closed = it }
            }
            if (kind == LedgerKind.KNOW) {
                GroupedTextField(value = via, onValueChange = { via = it }, label = "消息从哪来（偷听、谁告诉的……）", singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (kind != LedgerKind.TIME) {
                Box(Modifier.clip(RoundedCornerShape(Radius.Medium))) {
                    ToggleRow("只有作者知道", secret, subtitle = "结局、对手的底牌这类：只给 AI 把握方向，不写进正文。") { secret = it }
                }
            }
            if (initial.quote.isNotBlank()) {
                Text("依据原文", fontSize = TypeScale.Caption, color = c.secondary)
                Text(initial.quote, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine, color = c.label)
            }
            if (onDelete != null) {
                Box(Modifier.clip(RoundedCornerShape(Radius.Medium))) { ActionRow("删除这一条", destructive = true, centered = true, onClick = onDelete) }
            }
        }
        DialogButtons(onDismiss = onDismiss, onConfirm = { onSave(build()) }, confirmText = if (isNew) "添加" else "保存", confirmEnabled = text.isNotBlank())
    }
}

/** 导入前看一眼：设定包的察觉表、伏笔表里找到的条目，默认全勾上，按类别分组；勾上的才导入。 */
@Composable
private fun LedgerImport(layer: LedgerLayer.Import, padding: PaddingValues, onChange: (LedgerLayer.Import) -> Unit, onImport: (List<LedgerEntry>) -> Unit) {
    val c = LocalGroupedColors.current
    val picked = layer.entries.filter { it.id in layer.picked }
    LazyColumn(Modifier.fillMaxSize().overScrollVertical(), contentPadding = padding, overscrollEffect = null) {
        LedgerKind.entries.forEach { kind ->
            val list = layer.entries.filter { it.kind == kind }
            if (list.isEmpty()) return@forEach
            item(key = "${kind.name}:head") { GroupedListHeader(kind.label) }
            itemsIndexed(list, key = { _, e -> e.id }) { i, e ->
                val on = e.id in layer.picked
                val toggle = { onChange(layer.copy(picked = if (on) layer.picked - e.id else layer.picked + e.id)) }
                GroupedListCell(i == 0, i == list.lastIndex) {
                    GroupedRow(onClick = toggle) {
                        LingYueCheckbox(on, onClick = toggle)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(e.text, color = if (on) c.label else c.tertiary, fontSize = TypeScale.Body, fontWeight = FontWeight.Medium)
                            val sub = listOf(e.subject, e.aspect, e.whenLabel).filter { it.isNotBlank() }.joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, color = c.secondary, fontSize = TypeScale.Caption)
                        }
                    }
                }
            }
        }
        item(key = "import") {
            GroupedSection(footer = "勾上的写进账本，标为“导入”；和账本里已有的一样的不会再加。导入前的账本存在历史版本里。") {
                row { ActionRow("导入 ${picked.size} 条", enabled = picked.isNotEmpty(), centered = true) { onImport(picked) } }
            }
        }
    }
}

/** 账本的历史版本：每次改之前的样子，新的在前；点开看那一版的条目。 */
@Composable
private fun LedgerHistory(vm: AppViewModel, workId: String, padding: PaddingValues, onOpen: (LedgerVersion) -> Unit) {
    var versions by remember { mutableStateOf<List<LedgerVersion>?>(null) }
    LaunchedEffect(workId) { versions = vm.writing.ledgerVersions(workId) }
    val rule = "每次改账本（手动改、导入、整理、换回）之前的样子都存一版，最多留 30 版。"
    val list = versions
    LazyColumn(Modifier.fillMaxSize().overScrollVertical(), contentPadding = padding, overscrollEffect = null) {
        when {
            list == null -> item(key = "loading") { LoadingState("正在读历史版本……") }
            list.isEmpty() -> item(key = "empty") { EmptyState(LingYueIcons.History, "还没有历史版本", rule) }
            else -> {
                itemsIndexed(list, key = { _, v -> v.at }) { i, v ->
                    GroupedListCell(i == 0, i == list.lastIndex, gap = 8.dp) {
                        NavRow(whenLabel(v.at), value = "${v.count} 条", subtitle = "${v.label}前的样子") { onOpen(v) }
                    }
                }
                item(key = "footer") { GroupedListFooter(rule) }
            }
        }
    }
}

/** 某个历史版本的条目（只看），最下面“换回这个版本”。 */
@Composable
private fun LedgerVersionList(layer: LedgerLayer.Version, padding: PaddingValues, onRestore: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().overScrollVertical(), contentPadding = padding, overscrollEffect = null) {
        if (layer.entries.isEmpty()) item(key = "empty") { GroupedSection { row { EmptyRow("这一版的账本是空的") } } }
        allEntries(layer.entries, null, null)
        item(key = "restore") {
            GroupedSection(footer = "现在的账本也会先存一版，换错了可以再换回来。") {
                row { ActionRow("换回这个版本", centered = true, onClick = onRestore) }
            }
        }
    }
}
