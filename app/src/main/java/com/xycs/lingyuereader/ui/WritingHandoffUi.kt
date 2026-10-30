package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.CrashLog
import com.xycs.lingyuereader.HandoffView
import com.xycs.lingyuereader.data.ChapterHandoff
import com.xycs.lingyuereader.data.HandoffOrigin
import com.xycs.lingyuereader.data.Handoffs
import com.xycs.lingyuereader.ui.glass.GlassDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 一章看到的上一章交接卡（草稿页“接上一章”、细纲卡页“上一章交接”共用）：在后台读（[com.xycs.lingyuereader.WritingAi.handoffView]），
 * 改过、重新整理过就再读一次。[view] 为空是没有上一章或上一章是空章。
 */
internal class HandoffHolder(val workId: String, val chapterId: String) {
    var view by mutableStateOf<HandoffView?>(null)
    var loaded by mutableStateOf(false)
    var editing by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var expanded by mutableStateOf(false)
    var version by mutableIntStateOf(0)

    fun save(vm: AppViewModel, scope: CoroutineScope, card: ChapterHandoff) {
        val target = view ?: return
        busy = true
        scope.launch {
            try {
                vm.writingAi.saveHandoff(workId, target.chapterId, card)
                version++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.showMessage("交接卡没存上：${e.message ?: "写入出错"}")
            } finally {
                busy = false
            }
        }
    }

    /** “重新整理”：作者改过的也不留，按上一章现在的正文重来（有 AI 时请 AI，没有就回到本机抽的）。 */
    fun refresh(vm: AppViewModel, scope: CoroutineScope) {
        busy = true
        scope.launch {
            try {
                vm.writingAi.refreshHandoff(workId, chapterId)
                version++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.showMessage("上一章的交接没整理成：${e.message ?: "请求失败"}")
            } finally {
                busy = false
            }
        }
    }
}

/** 读上一章的交接卡；[refresh] 变了（比如一键写本章刚整理过）再读一次。 */
@Composable
internal fun rememberHandoff(vm: AppViewModel, workId: String, chapterId: String, refresh: Any? = null): HandoffHolder {
    val holder = remember(workId, chapterId) { HandoffHolder(workId, chapterId) }
    LaunchedEffect(workId, chapterId, refresh, holder.version) {
        holder.view = try {
            vm.writingAi.handoffView(workId, chapterId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 读不出不挡写：当作没有交接卡。
            CrashLog.caught(e)
            null
        }
        holder.loaded = true
    }
    return holder
}

/** 交接卡右边的小字：怎么来的、要不要重新整理。 */
internal fun handoffState(view: HandoffView): String = when {
    view.stale -> "上一章改过了"
    view.outdated -> "你改过的 · 上一章又改了"
    else -> view.card.origin.label
}

/** 交接卡写成一行（细纲卡页“上一章交接：…”）。什么都没有时说清楚。 */
internal fun handoffLine(card: ChapterHandoff): String =
    card.lines().joinToString("；").ifBlank { "还没抽到什么，可以点开自己写" }

/**
 * 草稿页“按这些写的”卡片里的“接上一章”一栏：收着是一行，展开看交接卡的各项，能改、能重新整理。
 * [aiReady] 为真时写本章前会按需请 AI 整理（过期的、本机抽的），说明里照实写。
 */
internal fun GroupedSectionScope.handoffRows(holder: HandoffHolder, aiReady: Boolean, onEdit: () -> Unit, onRefresh: () -> Unit) {
    val view = holder.view ?: return
    row { ExpandRow("接上一章", holder.expanded, value = handoffState(view)) { holder.expanded = !holder.expanded } }
    if (!holder.expanded) return
    row {
        val c = LocalGroupedColors.current
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("上一章《${view.chapterTitle}》结尾时", color = c.secondary, fontSize = TypeScale.Caption)
            val lines = view.card.lines()
            if (lines.isEmpty()) Text("还没抽到什么（结尾里没认出人名、地点）。可以点“改”自己写几句。", color = c.tertiary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine)
            lines.forEach { Text(it, color = c.label, fontSize = TypeScale.Body, lineHeight = TypeScale.BodyLine) }
            val note = when {
                view.stale && aiReady -> "上一章在整理之后改过：写本章前 AI 会重新整理，这里先给本机从结尾抽的。"
                view.stale -> "上一章在整理之后改过：这里先给本机从结尾抽的。"
                view.outdated -> "你改过这张卡以后，上一章又改了：看看还对不对，或点“重新整理”。"
                view.card.origin == HandoffOrigin.LOCAL && aiReady -> "这是本机从结尾抽的：写本章前 AI 会整理一份更全的。"
                view.card.origin == HandoffOrigin.LOCAL -> "这是本机从结尾抽的：拿不准的项空着，可以自己补。"
                else -> null
            }
            if (note != null) Text(note, color = c.secondary, fontSize = TypeScale.Footnote, lineHeight = TypeScale.FootnoteLine)
        }
    }
    row { ActionRow("改", enabled = !holder.busy) { onEdit() } }
    row { ActionRow(if (holder.busy) "正在整理……" else "重新整理", enabled = !holder.busy) { onRefresh() } }
}

/**
 * 改交接卡的对话框：场景、在场人物（一行一人）、情绪基调、没了结的（一行一条，最多 [Handoffs.MAX_OPEN] 条）、章末钩子。
 * 存下的是“你改过的”：之后不被自动重新整理盖掉，除非点“重新整理”。[scope] 是页面的（对话框关了也要存完）。
 */
@Composable
internal fun HandoffEditDialog(vm: AppViewModel, holder: HandoffHolder, scope: CoroutineScope) {
    val view = holder.view
    if (!holder.editing || view == null) return
    val card = view.card
    var scene by remember(card) { mutableStateOf(card.scene) }
    var people by remember(card) { mutableStateOf(card.people.joinToString("\n") { it.line() }) }
    var mood by remember(card) { mutableStateOf(card.mood) }
    var open by remember(card) { mutableStateOf(card.open.joinToString("\n")) }
    var hook by remember(card) { mutableStateOf(card.hook) }
    GlassDialog(show = true, title = "上一章交接",
        summary = "上一章《${view.chapterTitle}》结尾时的样子，写本章开头时先接住它。改过的不会被自动重新整理盖掉。",
        onDismissRequest = { holder.editing = false }) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(value = scene, onValueChange = { scene = it }, label = "场景 · 在哪、什么时间", modifier = Modifier.fillMaxWidth())
            TextField(value = people, onValueChange = { people = it }, label = "在场人物 · 一行一人：林月：守在门口；左臂带伤",
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
            TextField(value = mood, onValueChange = { mood = it }, label = "情绪基调", modifier = Modifier.fillMaxWidth())
            TextField(value = open, onValueChange = { open = it }, label = "没说完的话、没解决的悬念 · 一行一条，最多 ${Handoffs.MAX_OPEN} 条",
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
            TextField(value = hook, onValueChange = { hook = it }, label = "章末钩子 · 细纲卡里写了就以细纲卡为准", modifier = Modifier.fillMaxWidth())
        }
        DialogButtons(onDismiss = { holder.editing = false }, onConfirm = {
            holder.editing = false
            holder.save(vm, scope, ChapterHandoff(
                scene = scene.trim(), people = people.lines().mapNotNull(Handoffs::person), mood = mood.trim(),
                open = open.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(Handoffs.MAX_OPEN), hook = hook.trim(),
            ))
        }, confirmText = "存下")
    }
}
