package com.xycs.lingyuereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.data.WritingScene
import com.xycs.lingyuereader.data.WritingScenePlan
import com.xycs.lingyuereader.ui.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

/**
 * The proposal is separate from the approved plan: changing a card only stages a new version.
 * [onSavePlan] is called solely by the explicit confirmation action, never during generation.
 */
@Composable
internal fun WritingScenePlanSection(
    plan: WritingScenePlan?,
    approved: Boolean,
    draftStarted: Boolean,
    loading: Boolean,
    error: String?,
    canGenerate: Boolean,
    canWrite: Boolean,
    onGenerate: () -> Unit,
    onChangePlan: (WritingScenePlan) -> Unit,
    onSavePlan: (WritingScenePlan) -> Unit,
    onWriteNext: () -> Unit,
    onReset: () -> Unit,
) {
    val c = LocalGroupedColors.current
    val started = plan != null && (draftStarted || plan.completed > 0 || plan.draftHash.isNotBlank())
    val selected = plan?.scenes?.count { it.selected } ?: 0
    var editing by remember { mutableStateOf<Pair<Int, WritingScene>?>(null) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    GroupedSection(
        header = "场景安排",
        footer = when {
            started -> "已开始写作，场景安排只读。正文仍是候选稿，通读并审定后才会进入正式章节。"
            plan == null && draftStarted -> "已有候选稿。处理或舍弃这版稿件后，可以再拟场景安排。"
            plan != null && !approved -> "AI 给的是可修改的建议，不是已发生的故事事实。选好、改好后再确认；重启前未确认的修改不会保留。"
            plan != null -> "可修改场景顺序中的内容和取舍。更改后要再次确认；不按固定场次数改写章界。"
            else -> "可先让 AI 依据本章细纲拟场景，也可沿用“写本章”直接创作。建议须由你确认后才会用于正文。"
        },
    ) {
        if (plan == null) {
            row {
                Text(
                    "先看每场的目标、阻力、转折与退出点，再决定要写哪些。",
                    color = c.secondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        } else {
            row {
                GroupedRow {
                    Text(
                        if (started) "已完成 ${plan.completed} / $selected 场" else "已选 $selected / ${plan.scenes.size} 场",
                        color = c.label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Tag(if (started) "只读" else if (approved) "已确认" else "待确认", if (approved) c.success else c.warning)
                }
            }
            plan.scenes.forEachIndexed { index, scene ->
                val selectedOrdinal = plan.scenes.take(index + 1).count { it.selected }
                val completed = scene.selected && selectedOrdinal <= plan.completed
                row {
                    WritingSceneCard(
                        index = index,
                        scene = scene,
                        completed = completed,
                        editable = !started && !loading,
                        canDeselect = selected > 1,
                        onToggle = {
                            onChangePlan(plan.copy(scenes = plan.scenes.mapIndexed { at, item ->
                                if (at == index) item.copy(selected = !item.selected) else item
                            }))
                        },
                        onEdit = { editing = index to scene },
                    )
                }
            }
        }
        if (loading) {
            row {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spinner()
                    Text("正在拟场景建议……", color = c.secondary, fontSize = 14.sp)
                }
            }
        }
        if (!error.isNullOrBlank()) row { ErrorText(error, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        if (plan == null && error?.startsWith("场景安排读取失败") == true) {
            row { ActionRow("移除无法读取的场景安排", destructive = true) { confirmReset = true } }
        }
        if (!started && !loading) {
            row {
                ActionRow(if (plan == null) "生成场景建议" else "重新生成场景建议",
                    enabled = canGenerate && error?.startsWith("场景安排读取失败") != true) {
                    if (plan == null) onGenerate() else confirmRegenerate = true
                }
            }
            if (plan != null) {
                if (!approved) {
                    row { ActionRow("确认场景安排", enabled = selected > 0 && plan.scenes.all { it.fieldsReady() }) { onSavePlan(plan) } }
                } else if (plan.completed < selected) {
                    row { ActionRow("按安排写第 ${plan.completed + 1} 场", enabled = canWrite) { onWriteNext() } }
                }
                row { ActionRow(if (approved) "不用这份安排" else "放弃这份建议", destructive = true) { confirmReset = true } }
            }
        } else if (started && !loading && approved) {
            if (plan.completed < selected) {
                row { ActionRow("按安排写第 ${plan.completed + 1} 场", enabled = canWrite) { onWriteNext() } }
            } else {
                row { Text("已按选定安排写完；请通读候选稿并完成审读。", color = c.secondary, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
            }
            row { ActionRow("退出场景安排") { confirmReset = true } }
        }
    }

    editing?.let { (index, original) ->
        WritingSceneEditDialog(
            number = index + 1,
            initial = original,
            onDismiss = { editing = null },
            onSave = { changed ->
                plan?.let { current ->
                    if (!started && index in current.scenes.indices) {
                        onChangePlan(current.copy(scenes = current.scenes.mapIndexed { at, item ->
                            if (at == index) changed else item
                        }))
                    }
                }
                editing = null
            },
        )
    }
    if (confirmRegenerate) {
        GlassDialog(
            show = true,
            title = "重新生成场景建议",
            summary = "当前选择和手工修改会被新建议替换；已写正文不会改变。",
            onDismissRequest = { confirmRegenerate = false },
        ) {
            DialogButtons(onDismiss = { confirmRegenerate = false }, onConfirm = {
                confirmRegenerate = false
                onGenerate()
            }, confirmText = "重新生成")
        }
    }
    if (confirmReset) {
        GlassDialog(
            show = true,
            title = when {
                plan == null && error?.startsWith("场景安排读取失败") == true -> "移除无法读取的场景安排"
                started -> "退出场景安排"
                else -> "不用这份场景安排"
            },
            summary = when {
                plan == null && error?.startsWith("场景安排读取失败") == true ->
                    "无法读取的安排会被移除；现有正文与候选稿不变。请核对候选稿后重新审读。"
                !approved -> "未确认的建议会被放弃；若此前已有确认的安排，它仍会保留。现有正文与候选稿不变。"
                else -> "安排会被移除；现有正文与候选稿不变。候选稿仍须通过原有审读后才能采纳。"
            },
            onDismissRequest = { confirmReset = false },
        ) {
            DialogButtons(onDismiss = { confirmReset = false }, onConfirm = {
                confirmReset = false
                onReset()
            }, confirmText = if (started) "退出安排" else "移除安排", destructive = !started)
        }
    }
}

private fun WritingScene.fieldsReady(): Boolean =
    listOf(goal, conflict, turn, exit).all { it.isNotBlank() && it.length <= 240 }

@Composable
private fun WritingSceneCard(
    index: Int,
    scene: WritingScene,
    completed: Boolean,
    editable: Boolean,
    canDeselect: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    val c = LocalGroupedColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(c.fill, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${index + 1}", color = c.tint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 5.dp))
            Text(scene.goal, color = c.label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!editable) {
                Tag(if (!scene.selected) "未选" else if (completed) "已写" else "待写", if (completed) c.success else c.secondary)
            } else {
                PillButton(
                    if (scene.selected) "已选" else "选用",
                    onClick = onToggle,
                    style = if (scene.selected) PillStyle.Tinted else PillStyle.Plain,
                    enabled = !scene.selected || canDeselect,
                    modifier = Modifier.semantics {
                        contentDescription = if (scene.selected && !canDeselect) "第 ${index + 1} 场已选，至少保留一场"
                            else "第 ${index + 1} 场${if (scene.selected) "取消选用" else "选用"}"
                    },
                )
                IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                    Icon(LingYueIcons.Write, contentDescription = "编辑第 ${index + 1} 场安排", tint = c.tint, modifier = Modifier.size(18.dp))
                }
            }
        }
        Text("阻力：${scene.conflict}", color = c.secondary, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("转折：${scene.turn} · 退出：${scene.exit}", color = c.secondary, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WritingSceneEditDialog(number: Int, initial: WritingScene, onDismiss: () -> Unit, onSave: (WritingScene) -> Unit) {
    var scene by remember(number, initial) { mutableStateOf(initial) }
    GlassDialog(show = true, title = "第 $number 场安排", summary = "修改的是场景计划，不会改动正文；每项最多 240 字。", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(value = scene.goal, onValueChange = { scene = scene.copy(goal = it.take(240)) },
                label = "目标：这一场要达成什么", modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
            TextField(value = scene.conflict, onValueChange = { scene = scene.copy(conflict = it.take(240)) },
                label = "阻力：什么挡住了目标", modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
            TextField(value = scene.turn, onValueChange = { scene = scene.copy(turn = it.take(240)) },
                label = "转折：局面怎样改变", modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
            TextField(value = scene.exit, onValueChange = { scene = scene.copy(exit = it.take(240)) },
                label = "退出点：怎样进入下一场", modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
        }
        DialogButtons(onDismiss = onDismiss, onConfirm = { onSave(scene) }, confirmText = "保存修改", confirmEnabled = scene.fieldsReady())
    }
}
