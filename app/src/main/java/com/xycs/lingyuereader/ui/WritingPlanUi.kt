package com.xycs.lingyuereader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.Beats
import com.xycs.lingyuereader.data.ChangeEntry
import com.xycs.lingyuereader.data.ChapterBriefs
import com.xycs.lingyuereader.data.ChapterCard
import com.xycs.lingyuereader.data.ChapterStatus
import com.xycs.lingyuereader.data.ForeshadowMoves
import com.xycs.lingyuereader.data.PlannedCard
import com.xycs.lingyuereader.data.outlineHash
import com.xycs.lingyuereader.data.withOutline
import com.xycs.lingyuereader.data.WorkChapter
import com.xycs.lingyuereader.data.beats
import com.xycs.lingyuereader.data.withBeatDone
import com.xycs.lingyuereader.data.withBeats
import com.xycs.lingyuereader.ui.glass.GlassDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 各状态的颜色：细纲用次要色，草稿用强调色，修订用提醒色，定稿用成功色——都从配色里取，深浅色、自选强调色下都读得清。
 * 小标签的字画在它自己的淡色底上，按卡片底和淡色底一起调到够对比度（强调色很浅时压深一点）。
 */
internal fun statusColor(status: ChapterStatus, c: GroupedColors): Color {
    val base = when (status) {
        ChapterStatus.OUTLINE -> c.secondary
        ChapterStatus.DRAFT -> c.tint
        ChapterStatus.REVISION -> c.warning
        ChapterStatus.FINAL -> c.success
    }
    // 字压深（或提亮）了，它的淡色底也跟着变：多调两遍，直到在卡片和淡色底上都够。
    var color = base
    repeat(3) { color = color.readableOn(listOf(c.cell, color.copy(alpha = TAG_FILL_ALPHA).compositeOver(c.cell))) }
    return color
}

/** [Tag] 的淡色底用的透明度（和 Grouped.kt 里一致）。 */
private const val TAG_FILL_ALPHA = 0.14f

/** 状态小标签。 */
@Composable
internal fun StatusLabel(status: ChapterStatus) {
    val c = LocalGroupedColors.current
    Tag(status.label, statusColor(status, c))
}

/**
 * 改章节状态。定稿后正文锁定；从定稿改出去要写原因，并说明改动涉不涉及设定（涉及的话登记里会提醒同步设定包）。
 */
@Composable
internal fun StatusDialog(chapter: WorkChapter, ruleHits: Int? = null, onDismiss: () -> Unit, onConfirm: (ChapterStatus, String, Boolean) -> Unit) {
    // 定稿的章打开这里多半是要解锁：先选好“修订”。
    var status by remember { mutableStateOf(if (chapter.locked) ChapterStatus.REVISION else chapter.status) }
    var reason by remember { mutableStateOf("") }
    var canon by remember { mutableStateOf(false) }
    val unlocking = chapter.locked && status != ChapterStatus.FINAL
    GlassDialog(show = true, title = "“${chapter.title}”的状态", onDismissRequest = onDismiss) {
        // 配色在对话框里面读（对话框把调用处的配色带进来了）。
        val c = LocalGroupedColors.current
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 四个互斥的短选项：分段控件。
            SegmentedControl(ChapterStatus.entries.map { it to it.label }, status) { status = it }
            Text(
                when {
                    unlocking -> "这一章已定稿。改之前写下为什么要改，会记进变更登记。"
                    status == ChapterStatus.FINAL && !chapter.locked -> "定稿后正文锁定，AI 改稿也不能动它；要改得先解锁并登记原因。定稿时会存一个历史版本。"
                    else -> "细纲：只有细纲卡；草稿：在写；修订：改稿中；定稿：锁定。"
                },
                color = c.secondary,
                fontSize = TypeScale.Caption,
                lineHeight = TypeScale.CaptionLine,
            )
            // 定稿前提醒一句检查规则的结果，不拦着。
            if (status == ChapterStatus.FINAL && !chapter.locked && (ruleHits ?: 0) > 0) {
                Text("按检查规则，这一章还有 $ruleHits 条不合。仍然可以定稿。", color = c.warning, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine)
            }
            if (unlocking) {
                GroupedTextField(value = reason, onValueChange = { reason = it }, label = "为什么要改（必填）", modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 140.dp))
                // 只改写法 / 涉及设定：一个开关（关着就是只改写法）。
                Box(Modifier.clip(RoundedCornerShape(Radius.Medium))) {
                    ToggleRow(
                        "涉及设定，要同步设定包",
                        checked = canon,
                        subtitle = "改动会动到不变量、细纲、人物档案或察觉表里的事实时打开。登记里会一直提醒，直到你把设定包改好、标为已同步。",
                    ) { canon = it }
                }
            }
        }
        DialogButtons(
            onDismiss = onDismiss,
            onConfirm = { onConfirm(status, reason, canon) },
            confirmText = if (unlocking) "解锁" else "好",
            confirmEnabled = !unlocking || reason.isNotBlank(),
        )
    }
}

private val CARD_FIELDS: List<Triple<String, String, (ChapterCard) -> String>> = listOf(
    Triple("本章事实与结果", "发生了什么、结果（数字、生死、得失）", { it.facts }),
    Triple("后文承接边界", "后面的章节不能和哪些点冲突", { it.boundary }),
    Triple("视角人物", "", { it.pov }),
    Triple("倒计时", "章首交代：离某件事还有多久", { it.countdown }),
    Triple("章末钩子", "一个动作、一句没说完的话、一点异样", { it.hook }),
    Triple("推进的线", "比如：明线、暗线、主角线，或某个配角的弧线", { it.threads }),
    Triple(CHANGE, "一两句：人物关系、处境、知道的事变了什么", { it.change }),
    Triple(MOVES, "一行一条：埋 / 推进 / 回收 / 暂缓：哪条伏笔", { ForeshadowMoves.format(it.moves) }),
    Triple("细纲章号", "发布章号顺延时填（比如拆成上下章）", { it.plan }),
)

/** 细纲卡新加的两张：本章改变了什么、伏笔动作。 */
internal const val CHANGE = "本章改变了什么"
internal const val MOVES = "伏笔动作"

/** 改一项。作者动了前六项，这份细纲就是作者认过的了：不再标“AI 拟的”。细纲章号不是 AI 拟的，改它不算。 */
private fun ChapterCard.with(field: String, value: String) = when (field) {
    "本章事实与结果" -> copy(facts = value, aiOutline = false)
    "后文承接边界" -> copy(boundary = value, aiOutline = false)
    "视角人物" -> copy(pov = value, aiOutline = false)
    "倒计时" -> copy(countdown = value, aiOutline = false)
    "章末钩子" -> copy(hook = value, aiOutline = false)
    "细纲章号" -> copy(plan = value)
    CHANGE -> copy(change = value, aiOutline = false)
    MOVES -> copy(moves = ForeshadowMoves.parse(value, moves), aiOutline = false)
    else -> copy(threads = value, aiOutline = false)
}

/**
 * 存细纲卡时按项合并：这一页上改过的项（[edited] 和打开、上次存时的 [base] 不一样的）用这一页的，
 * 没改过的用盘上最新的 [latest]（这期间“补写章节摘要”写进去的摘要之类，不能被这一页的旧卡片清掉）。
 * [storedAtOpen] 是打开页面时真正落盘的卡：细纲自动提取的字段只在 [base] 里，盘上还没有时也要带进第一次保存。
 */
internal fun mergeCard(base: ChapterCard, edited: ChapterCard, latest: ChapterCard, storedAtOpen: ChapterCard = base): ChapterCard {
    fun pick(get: (ChapterCard) -> String) = when {
        get(edited) != get(base) -> get(edited)
        get(latest) != get(storedAtOpen) -> get(latest)
        else -> get(base)
    }
    val beats = pick { it.beats }
    // 编辑器里采纳了一拍会记进“已写”：这一页没动过勾时用盘上的。勾和节拍条数对不上（另一边改了条数）就清空。
    val doneFrom = when {
        edited.beatsDone != base.beatsDone -> edited
        latest.beatsDone != storedAtOpen.beatsDone -> latest
        else -> base
    }
    return latest.copy(
        facts = pick { it.facts },
        boundary = pick { it.boundary },
        pov = pick { it.pov },
        countdown = pick { it.countdown },
        hook = pick { it.hook },
        threads = pick { it.threads },
        summary = pick { it.summary },
        plan = pick { it.plan },
        change = pick { it.change },
        moves = when {
            edited.moves != base.moves -> edited.moves
            latest.moves != storedAtOpen.moves -> latest.moves
            else -> base.moves
        },
        beats = beats,
        beatsDone = if (Beats.parse(doneFrom.beats).size == Beats.parse(beats).size) doneFrom.beatsDone else emptyList(),
        auto = when {
            edited.auto != base.auto -> edited.auto
            latest.auto != storedAtOpen.auto -> latest.auto
            else -> base.auto
        },
        // “AI 拟的 / AI 拆的”跟着它管的那几项走：这一页确认过（或改过）用这一页的，没动过用盘上最新的。
        aiOutline = when {
            edited.aiOutline != base.aiOutline -> edited.aiOutline
            latest.aiOutline != storedAtOpen.aiOutline -> latest.aiOutline
            else -> base.aiOutline
        },
        aiBeats = when {
            edited.aiBeats != base.aiBeats -> edited.aiBeats
            latest.aiBeats != storedAtOpen.aiBeats -> latest.aiBeats
            else -> base.aiBeats
        },
    )
}

/** Keep a leaving editor's last edits until its already-started save has finished. */
internal class CardSaveFlight {
    var submitted: ChapterCard? = null
    private var leavingWith: ChapterCard? = null

    fun leave(current: ChapterCard): Boolean {
        if (submitted == null) return false
        leavingWith = current
        return true
    }

    fun finish(): Pair<ChapterCard, ChapterCard>? {
        val followUp = submitted?.let { first -> leavingWith?.let { first to it } }
        submitted = null
        leavingWith = null
        return followUp
    }
}

/**
 * 一章的细纲卡：只划事实边界（发生了什么、后文不能冲突的点、视角、倒计时、钩子、推进哪几条线），不管怎么写。
 * 上面列出上一章留下的钩子和承接边界。写作 AI 改这一章时会带上它。边打边存。
 */
@Composable
fun ChapterCardScreen(vm: AppViewModel, workId: String, chapterId: String) {
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val chapter = works.firstOrNull { it.id == workId }?.chapter(chapterId)
    var card by remember(workId, chapterId) { mutableStateOf<ChapterCard?>(null) }
    var saved by remember(workId, chapterId) { mutableStateOf<ChapterCard?>(null) }
    var persisted by remember(workId, chapterId) { mutableStateOf<ChapterCard?>(null) }
    var saving by remember(workId, chapterId) { mutableStateOf(false) }
    var failedAttempt by remember(workId, chapterId) { mutableStateOf<ChapterCard?>(null) }
    val saveFlight = remember(workId, chapterId) { CardSaveFlight() }
    var previous by remember(chapterId) { mutableStateOf<Pair<WorkChapter, ChapterCard>?>(null) }
    var info by remember(chapterId) { mutableStateOf<PlannedCard?>(null) }
    var replacing by remember { mutableStateOf(false) }
    var planning by remember { mutableStateOf(false) }
    var proposal by remember { mutableStateOf<ChapterCard?>(null) }
    var statusDialog by remember { mutableStateOf(false) }
    var summarizing by remember { mutableStateOf(false) }
    var splitting by remember { mutableStateOf(false) }
    var askOverwriteBeats by remember { mutableStateOf(false) }
    var beatsProposal by remember { mutableStateOf<String?>(null) }
    // 就地展开改的那一张小卡片（项名；节拍卡是 [BEATS]），同时只开一张。
    var expandedField by remember(chapterId) { mutableStateOf<String?>(null) }
    // 伏笔动作的文本框原样留着作者正在打的字（存的是拆好的几条，拆完再拼回去，换行、半行字会被吞掉）。
    var movesDraft by remember(chapterId) { mutableStateOf<String?>(null) }
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // AI 拆节拍：按这一页上现在的细纲卡（可能还没存）拆；拆好的先给作者看、改，用了才进卡片。
    fun splitBeats() {
        val now = card ?: return
        splitting = true
        scope.launch {
            try {
                beatsProposal = vm.writingAi.splitBeats(workId, chapterId, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.showMessage(e.message ?: "拆节拍失败")
            } finally {
                splitting = false
            }
        }
    }
    val snackbar = rememberWritingMessages(vm)
    // 上一章的交接卡（“上一章交接：…”那一行，点了能改）。
    val handoff = rememberHandoff(vm, workId, chapterId)
    LaunchedEffect(workId, chapterId) {
        val snapshot = vm.writing.cardSnapshot(workId, chapterId)
        card = snapshot.planned.card
        saved = snapshot.planned.card
        persisted = snapshot.stored
        info = snapshot.planned
        previous = snapshot.previous
    }
    // 停一下再存；离开时存。存的时候以盘上最新的卡片为底，只换这一页改过的项。
    LaunchedEffect(card, saved, persisted, saving, failedAttempt) {
        val now = card ?: return@LaunchedEffect
        val base = saved ?: return@LaunchedEffect
        val storedAtOpen = persisted ?: return@LaunchedEffect
        if (now == base || saving || failedAttempt == now) return@LaunchedEffect
        delay(600)
        // 合并在写盘队列里、和读最新卡片在同一把锁里做，补写摘要这时写进来也不会丢。
        saveFlight.submitted = now
        saving = true
        failedAttempt = null
        vm.writing.updateCard(
            workId, chapterId,
            { latest -> mergeCard(base, now, latest, storedAtOpen) },
            then = { merged ->
                val followUp = saveFlight.finish()
                if (followUp != null) {
                    // The screen has left. Queue this after the first save, so a reverted
                    // field wins even if that first save finished after the author left.
                    vm.writing.updateCard(workId, chapterId,
                        { latest -> mergeCard(followUp.first, followUp.second, latest, storedAtOpen) })
                } else {
                    // 只在真正落盘后确认。若作者在等待时继续输入，把这些新改动移到落盘后的卡上。
                    val current = card ?: now
                    card = mergeCard(now, current, merged)
                    saved = merged
                    persisted = merged
                    saving = false
                }
            },
            onFailure = {
                val followUp = saveFlight.finish()
                if (followUp != null) {
                    // Also retry the full latest card when the first write itself failed.
                    vm.writing.updateCard(workId, chapterId,
                        { latest -> mergeCard(followUp.first, followUp.second, latest, storedAtOpen) })
                } else {
                    // 不确认失败的写入；停在未保存状态，允许点按重试或下一次编辑再次尝试。
                    failedAttempt = now
                    saving = false
                }
            },
        )
    }
    DisposableEffect(workId, chapterId) {
        onDispose {
            // These are remembered state delegates, read at disposal rather than a value
            // from the last composition: the save callback may have just rebased the card.
            val now = card
            val base = saved
            val storedAtOpen = persisted
            if (now != null && base != null && storedAtOpen != null) {
                if (!saveFlight.leave(now) && now != base) {
                    vm.writing.updateCard(workId, chapterId, { latest -> mergeCard(base, now, latest, storedAtOpen) })
                }
            }
        }
    }

    SubPageScaffold("细纲卡", onBack = { vm.back() }, snackbar = snackbar) { padding ->
        // 配色在骨架提供的配色里面读。
        val c = LocalGroupedColors.current
        Box {
            LazyColumn(
                Modifier.fillMaxSize().imePadding().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    if (chapter != null) {
                        GroupedSection {
                            // 点这一行改状态：章名，右边状态小标签。
                            row { NavRow(chapter.title, trailing = { StatusLabel(chapter.status) }) { statusDialog = true } }
                        }
                    }
                    previous?.let { (pc, prev) ->
                        val handed = handoff.view
                        if (prev.hook.isNotBlank() || prev.boundary.isNotBlank() || handed != null) {
                            GroupedSection(header = "上一章「${pc.title}」留下的") {
                                if (prev.hook.isNotBlank()) row { ValueRow("钩子", prev.hook) }
                                if (prev.boundary.isNotBlank()) row { ValueRow("承接边界", prev.boundary) }
                                if (handed != null) row {
                                    NavRow("上一章交接", value = handoffState(handed), subtitle = "上一章交接：" + handoffLine(handed.card),
                                        subtitleMaxLines = 3, enabled = !handoff.busy) { handoff.editing = true }
                                }
                            }
                        }
                    }
                    val now = card
                    if (now != null) {
                        val planned = info
                        val fromOutline = planned?.source != null && now.auto != 0 && now.auto == now.outlineHash()
                        val outline = planned?.outline?.takeIf { it.outlineHash() != now.outlineHash() }
                        GroupedSection(
                            header = "本章",
                            footer = "细纲只划事实边界，不管怎么写。写作 AI 改这一章、给灵感、审稿时都会带上它，以及上一章的钩子和承接边界。",
                        ) {
                            val note = when {
                                fromOutline -> "从${planned?.source}自动提取。细纲文档改了，这里跟着变；在这里改过以后就按你改的来。"
                                outline != null -> "改过了，不再跟着细纲变。细纲里这一章（${planned.outlineSource}）和这里不一样，可以换回去。"
                                planned?.missing != null -> "设定包的卷纲细纲里没找到这一章（${planned.missing}）。章号对不上时填下面的“细纲章号”。"
                                else -> null
                            }
                            if (note != null) row { Text(note, color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
                            // 一键写本章时 AI 拟的：挂个小标签，看过没问题点一下确认；直接改哪一项也算确认。
                            if (now.aiOutline) {
                                row { AiDraftedNote("AI 拟的", "写本章时 AI 按本章及以前的资料拟的，下面挂着“待确认”的几张就是。改哪一项都算确认。") }
                                row { ActionRow("确认这份细纲") { card = (card ?: now).copy(aiOutline = false) } }
                            }
                            if (failedAttempt != null && now != saved) row { ActionRow("细纲卡未保存 · 重试") { failedAttempt = null } }
                            // 细纲里没写到的章（往后要写的），或者想要另一种走法：让 AI 按总纲、前后几章拟一份，看过再用。
                            row {
                                // 在拟的时候换成转圈的一行，拟完淡回按钮。
                                BusyActionRow(planning, busyText = "AI 正在拟细纲……", title = "AI 拟这一章的细纲", enabled = aiConfig.ready) {
                                    planning = true
                                    scope.launch {
                                        try {
                                            proposal = vm.writingAi.planCard(workId, chapterId)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            vm.showMessage(e.message ?: "拟细纲失败")
                                        } finally {
                                            planning = false
                                        }
                                    }
                                }
                            }
                            if (outline != null) row { ActionRow("用细纲里的替换") { replacing = true } }
                        }
                        // 六项和细纲章号：一项一张彩色小卡片，两张一排；点哪张就地展开改，展开的那张独占一排。
                        CardFieldGrid(
                            now,
                            expanded = expandedField,
                            aiDrafted = now.aiOutline,
                            onToggle = { label -> expandedField = if (expandedField == label) null else label },
                            // 卡片被别处换掉了（用了 AI 拟的、换成细纲里的），文本框跟着换成卡片上的。
                            movesText = movesDraft?.takeIf { ForeshadowMoves.parse(it, now.moves) == now.moves },
                        ) { label, v ->
                            if (label == MOVES) movesDraft = v
                            card = now.with(label, v)
                        }
                        // 节拍单独一张卡：各拍一直列着，已写的打勾；点卡片头改节拍原文。
                        val done = now.beatsDone.toSet()
                        val list = beats(now)
                        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                            FieldCard(
                                "节拍",
                                LingYueIcons.Write,
                                categoryColor(CategoryTone.TINT, c),
                                summary = if (list.isEmpty()) "" else "${list.size} 拍 · 已写 ${list.count { it.index in done }}",
                                expanded = expandedField == BEATS,
                                onToggle = { expandedField = if (expandedField == BEATS) null else BEATS },
                                tag = if (now.aiBeats) AI_PENDING_BEATS to c.warning else null,
                                always = {
                                    list.forEach { b ->
                                        CheckboxRow(
                                            "${b.index}. ${b.text}",
                                            checked = b.index in done,
                                            subtitle = listOfNotNull(if (b.index in done) "已写进正文" else null, b.length?.let { "约 $it 字" }).joinToString(" · ").ifEmpty { null },
                                            titleMaxLines = 2,
                                        ) { on -> card = (card ?: now).withBeatDone(b.index, on) }
                                    }
                                    if (now.aiBeats) ActionRow("确认这些节拍") { card = (card ?: now).copy(aiBeats = false) }
                                    BusyActionRow(splitting, busyText = "AI 正在拆节拍……", title = "AI 拆节拍", enabled = aiConfig.ready) {
                                        if (now.beats.isNotBlank()) askOverwriteBeats = true else splitBeats()
                                    }
                                },
                            ) {
                                GroupedTextField(
                                    value = now.beats,
                                    onValueChange = { v -> card = now.withBeats(v) },
                                    label = "节拍 · 比如：沈默赶到渡口，发现船不见了（800字）",
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 260.dp),
                                )
                            }
                        }
                        GroupedListFooter(
                            "一行一拍，可以在行尾写篇幅，比如“（800字）”。AI 写整章草稿会一拍一拍写；编辑器里“AI 写…”面板可以从光标处只写其中一拍，采纳后在这里勾成已写。",
                            Modifier.padding(top = 8.dp),
                        )
                        GroupedSection(
                            header = "写完以后",
                            footer = "本章摘要：写后面的章时当前情带给 AI（最近 ${ChapterBriefs.RECENT_SUMMARIES} 章，以及更早写到出场人物的章；往卷有卷摘要时用卷摘要），AI 就知道前面写了什么。收尾时还会顺带汇总卷摘要和“故事至今”。" +
                                "可以自己写，也可以让 AI 概括正文再改。",
                        ) {
                            row {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    GroupedTextField(
                                        value = now.summary,
                                        onValueChange = { v -> card = now.copy(summary = v) },
                                        label = "本章摘要 · 发生了什么、结果、留下什么",
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 260.dp),
                                    )
                                }
                            }
                            row {
                                BusyActionRow(summarizing, busyText = "AI 正在概括……", title = "AI 概括正文", enabled = aiConfig.ready && (chapter?.words ?: 0) > 0) {
                                    summarizing = true
                                    scope.launch {
                                        try {
                                            val text = vm.writingAi.summarize(workId, chapterId)
                                            card = (card ?: now).copy(summary = text)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            vm.showMessage(e.message ?: "概括失败")
                                        } finally {
                                            summarizing = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            proposal?.let { p ->
                GlassDialog(show = true, title = "AI 拟的细纲", summary = "按本章及以前的资料、本章细纲和上一章结尾拟的，看过再用", onDismissRequest = { proposal = null }) {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CARD_FIELDS.dropLast(1).forEach { (label, _, get) ->
                            val v = get(p)
                            if (v.isNotBlank()) {
                                Text(label, color = LocalGroupedColors.current.secondary, fontSize = TypeScale.Caption)
                                Text(v, color = LocalGroupedColors.current.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
                            }
                        }
                    }
                    DialogButtons(
                        onDismiss = { proposal = null },
                        onConfirm = {
                            proposal = null
                            card = card?.copy(facts = p.facts, boundary = p.boundary, pov = p.pov, countdown = p.countdown, hook = p.hook, threads = p.threads,
                                change = p.change.ifBlank { card?.change.orEmpty() }, moves = p.moves.ifEmpty { card?.moves.orEmpty() }, auto = 0, aiOutline = false)
                            info = info?.copy(source = null)
                        },
                        confirmText = "用这份",
                        dismissText = "不用",
                    )
                }
            }
            if (askOverwriteBeats) {
                GlassDialog(show = true, title = "覆盖现有节拍？", onDismissRequest = { askOverwriteBeats = false }) {
                    Text(
                        "AI 拆好以后先给你看，用了才会换掉现在写的节拍；已写的勾会按新的条数重新算。",
                        color = LocalGroupedColors.current.secondary,
                        fontSize = TypeScale.Caption,
                        lineHeight = TypeScale.CaptionLine,
                    )
                    DialogButtons(
                        onDismiss = { askOverwriteBeats = false },
                        onConfirm = {
                            askOverwriteBeats = false
                            splitBeats()
                        },
                        confirmText = "重新拆",
                    )
                }
            }
            beatsProposal?.let { proposed ->
                var edited by remember(proposed) { mutableStateOf(proposed) }
                GlassDialog(show = true, title = "AI 拆的节拍", summary = "按细纲卡和本章资料拆的，可以先改，用了才放进细纲卡", onDismissRequest = { beatsProposal = null }) {
                    GroupedTextField(
                        value = edited,
                        onValueChange = { edited = it },
                        label = "一行一拍，行尾可写篇幅",
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp),
                    )
                    DialogButtons(
                        onDismiss = { beatsProposal = null },
                        onConfirm = {
                            beatsProposal = null
                            card = card?.withBeats(edited.trim())
                        },
                        confirmText = "用这份",
                        dismissText = "不用",
                        confirmEnabled = edited.isNotBlank(),
                    )
                }
            }
            val outline = info?.outline
            if (replacing && outline != null) {
                GlassDialog(show = true, title = "用细纲里的替换", summary = info?.outlineSource, onDismissRequest = { replacing = false }) {
                    Text(
                        "前六项换成细纲文档里这一章现在写的，你在这里改的会被覆盖。以后细纲再改，这里跟着变。摘要和细纲章号不动。",
                        color = LocalGroupedColors.current.secondary,
                        fontSize = TypeScale.Caption,
                        lineHeight = TypeScale.CaptionLine,
                    )
                    DialogButtons(
                        onDismiss = { replacing = false },
                        onConfirm = {
                            replacing = false
                            card = card?.withOutline(outline)
                            info = info?.copy(source = info?.outlineSource)
                        },
                        confirmText = "替换",
                    )
                }
            }
            HandoffEditDialog(vm, handoff, scope)
            if (statusDialog && chapter != null) {
                StatusDialog(chapter, onDismiss = { statusDialog = false }) { s, reason, canon ->
                    statusDialog = false
                    vm.writing.setStatus(workId, chapterId, s, reason, canon)
                }
            }
        }
    }
}

/** 细纲卡各项小卡片的图标和分类色：一项一个图标，颜色帮着认，墨水屏下靠图标和项名分。 */
private val CARD_LOOK: Map<String, Pair<ImageVector, CategoryTone>> = mapOf(
    "本章事实与结果" to (LingYueIcons.Notes to CategoryTone.TINT),
    "后文承接边界" to (LingYueIcons.Lock to CategoryTone.WARNING),
    "视角人物" to (LingYueIcons.ContactsCircle to CategoryTone.PURPLE),
    "倒计时" to (LingYueIcons.Timer to CategoryTone.CYAN),
    "章末钩子" to (LingYueIcons.Hook to CategoryTone.BLUE),
    "推进的线" to (LingYueIcons.Threads to CategoryTone.SUCCESS),
    CHANGE to (LingYueIcons.Bulb to CategoryTone.TINT),
    MOVES to (LingYueIcons.Flag to CategoryTone.CYAN),
    "细纲章号" to (LingYueIcons.ListView to CategoryTone.NEUTRAL),
)

/** 节拍卡在“展开的是哪张”里的名字。 */
private const val BEATS = "节拍"

/** 一键写本章时 AI 补的、作者还没确认的几张卡上挂的标签。 */
internal const val AI_PENDING = "AI 拟的·待确认"
internal const val AI_PENDING_BEATS = "AI 拆的·待确认"

/**
 * 细纲卡的几项排成彩色小卡片（[FieldCard]）：手机上两张一排、宽屏三张，同一排一样高；展开的那张独占一排，下面是文本框。
 * [aiDrafted] 为真时前六项里填了的挂“AI 拟的·待确认”（细纲章号不是 AI 拟的，不挂）。
 */
@Composable
private fun CardFieldGrid(
    card: ChapterCard,
    expanded: String?,
    aiDrafted: Boolean,
    onToggle: (String) -> Unit,
    /** 伏笔动作文本框里作者正在打的原文；为空时按卡片上存的几条拼出来。 */
    movesText: String? = null,
    onChange: (String, String) -> Unit,
) {
    val c = LocalGroupedColors.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val columns = if (maxWidth >= 600.dp) 3 else 2
        // 一排一排地排：展开的那张单独一排，其余凑够一排换行。
        val rows = mutableListOf<List<Int>>()
        var line = mutableListOf<Int>()
        CARD_FIELDS.indices.forEach { i ->
            if (CARD_FIELDS[i].first == expanded) {
                if (line.isNotEmpty()) rows += line
                rows += listOf(i)
                line = mutableListOf()
            } else {
                line += i
                if (line.size == columns) { rows += line; line = mutableListOf() }
            }
        }
        if (line.isNotEmpty()) rows += line
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { r ->
                val single = r.size == 1 && CARD_FIELDS[r[0]].first == expanded
                Row(
                    Modifier.fillMaxWidth().then(if (single) Modifier else Modifier.height(IntrinsicSize.Min)),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    r.forEach { i ->
                        val (label, hint, get) = CARD_FIELDS[i]
                        val (icon, tone) = CARD_LOOK.getValue(label)
                        val value = if (label == MOVES) movesText ?: get(card) else get(card)
                        FieldCard(
                            label,
                            icon,
                            categoryColor(tone, c),
                            summary = value,
                            expanded = label == expanded,
                            onToggle = { onToggle(label) },
                            modifier = if (single) Modifier else Modifier.weight(1f).fillMaxHeight(),
                            tag = if (aiDrafted && value.isNotBlank() && label != "细纲章号") AI_PENDING to c.warning else null,
                        ) {
                            GroupedTextField(
                                value = value,
                                onValueChange = { v -> onChange(label, v) },
                                label = if (hint.isNotEmpty()) "$label · $hint" else label,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 220.dp),
                            )
                        }
                    }
                    if (!single) repeat(columns - r.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 细纲卡、草稿页上“AI 拟的 / AI 拆的”那一行：左边小标签（提醒色，等作者确认），右边一句说明。 */
@Composable
private fun AiDraftedNote(tag: String, text: String) {
    val c = LocalGroupedColors.current
    GroupedRow {
        Tag(tag, c.warning)
        Text(text, color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine, modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
}

/** 一个要等 AI 的动作：在做的时候换成转圈的一行（[busyText]），做完淡回按钮；减少动效时直接换。 */
@Composable
internal fun BusyActionRow(busy: Boolean, busyText: String, title: String, enabled: Boolean = true, onClick: () -> Unit) {
    AnimatedContent(busy, transitionSpec = { Motion.fadeThrough() }, label = "busyAction") { now ->
        if (now) LoadingRow(busyText) else ActionRow(title, enabled = enabled, onClick = onClick)
    }
}

/**
 * 变更登记：定稿、解锁修改和设定变更的记录，新的在前。涉及设定的会一直标“待同步”，直到把设定包改好、标为已同步。
 */
@Composable
fun WorkChangesScreen(vm: AppViewModel, workId: String) {
    val all by vm.writing.changes.collectAsStateWithLifecycle()
    LaunchedEffect(workId) { vm.writing.loadChanges(workId) }
    val list = all[workId]
    var open by remember { mutableStateOf<ChangeEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val snackbar = rememberWritingMessages(vm)

    SubPageScaffold(
        "变更登记",
        onBack = { vm.back() },
        snackbar = snackbar,
        actions = {
            IconButton(onClick = { adding = true }, modifier = Modifier.padding(end = 12.dp)) { Icon(LingYueIcons.Add, contentDescription = "登记设定变更") }
        },
    ) { padding ->
        val c = LocalGroupedColors.current
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            item {
                when {
                    list == null -> LoadingState("正在读变更登记……")
                    list.isEmpty() -> EmptyState(
                        LingYueIcons.History,
                        "还没有登记",
                        "定稿、解锁修改会自动登记；改了设定（比如人数、生死、谁知道什么）也可以手动登记一条。",
                        action = "登记设定变更" to { adding = true },
                    )
                    else -> {
                        val pending = list.filter { it.pending }
                        if (pending.isNotEmpty()) {
                            GroupedSection(header = "待同步设定", footer = "这些改动涉及设定：把设定包里的不变量、细纲、人物档案、察觉表改好后，点开标为已同步。") {
                                pending.forEach { e -> row { ChangeRow(e) { open = e } } }
                            }
                        }
                        GroupedSection(
                            header = "全部",
                            footer = "定稿、解锁修改会自动登记；改了设定（比如人数、生死、谁知道什么）也可以点右上角手动登记一条。",
                        ) {
                            list.forEach { e -> row { ChangeRow(e) { open = e } } }
                        }
                    }
                }
            }
        }
        open?.let { e ->
            GlassDialog(
                show = true,
                title = e.kind.label + if (e.chapterTitle.isNotBlank()) " · ${e.chapterTitle}" else "",
                summary = whenLabel(e.at) + (if (e.canon) if (e.synced) " · 涉及设定，已同步" else " · 涉及设定，待同步" else ""),
                onDismissRequest = { open = null },
            ) {
                if (e.reason.isNotBlank()) Text(e.reason, color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine)
                if (e.canon) {
                    DialogButtons(
                        onDismiss = { open = null; vm.open(Screen.WorkPack(workId)) },
                        dismissText = "去改设定包",
                        onConfirm = { open = null; vm.writing.markSynced(workId, e.id, !e.synced) },
                        confirmText = if (e.synced) "标为未同步" else "标为已同步",
                    )
                } else {
                    DialogOkButton("好") { open = null }
                }
            }
        }
        if (adding) {
            AskDialog("登记设定变更", hint = "改了什么、为什么（比如：老周改为第九章战死）", confirm = "登记", onDismiss = { adding = false }) {
                adding = false
                vm.writing.addCanonChange(workId, it)
            }
        }
    }
}

/** 一条登记：种类和章名，下面时间和原因；涉及设定还没同步的右边挂“待同步”。 */
@Composable
private fun ChangeRow(e: ChangeEntry, onClick: () -> Unit) {
    val c = LocalGroupedColors.current
    NavRow(
        e.kind.label + if (e.chapterTitle.isNotBlank()) " · ${e.chapterTitle}" else "",
        subtitle = listOf(whenLabel(e.at), e.reason.take(40)).filter { it.isNotBlank() }.joinToString(" · "),
        subtitleMaxLines = 2,
        trailing = if (e.pending) ({ Tag("待同步", c.warning) }) else null,
        onClick = onClick,
    )
}
