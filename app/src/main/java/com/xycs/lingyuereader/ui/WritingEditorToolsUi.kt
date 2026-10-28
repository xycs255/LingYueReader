package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.xycs.lingyuereader.InlineWrite
import com.xycs.lingyuereader.WriteAiState
import com.xycs.lingyuereader.WriteAiTask
import com.xycs.lingyuereader.data.Beat
import com.xycs.lingyuereader.data.ChapterStatus

internal enum class EditorToolGroup(val label: String) {
    WRITE("写"), CHECK("查"), ORGANIZE("整理"), MORE("更多")
}

internal data class EditorToolShortcut(val label: String, val icon: ImageVector, val onClick: () -> Unit)

internal enum class EditorRecommendationKind {
    PENDING_AI, UNLOCK, CONFIGURE_AI, DRAFT_CHAPTER, REVISE_SELECTION, REVIEW_CHAPTER, WRITE_BEAT, CONTINUE
}

internal data class EditorRecommendation(
    val kind: EditorRecommendationKind,
    val label: String,
    val reason: String,
    /** AI 面板在上方时，此处只显示状态；所有采纳和关闭操作仍由原面板完成。 */
    val enabled: Boolean = true,
)

/** 推荐是到现有安全流程的入口，不会自行采纳候选稿或把整理结果写入账本。 */
internal fun editorRecommendation(
    text: CharSequence,
    selection: TextRange,
    status: ChapterStatus?,
    aiReady: Boolean,
    inline: InlineWrite? = null,
    aiState: WriteAiState? = null,
    /** 本章细纲卡里第一拍没写的（序号和这一拍写什么）；没有节拍、都写过了为空。 */
    nextBeat: Beat? = null,
): EditorRecommendation {
    if (inline != null) {
        val label = when {
            inline.error != null -> "续写失败"
            inline.loading -> "AI 正在续写"
            else -> "核对续写结果"
        }
        val reason = when {
            inline.error != null -> "在上方重写或撤回"
            inline.loading -> "正文在上方生成"
            else -> "在上方采纳或撤回"
        }
        return EditorRecommendation(EditorRecommendationKind.PENDING_AI, label, reason, enabled = false)
    }
    if (aiState != null) {
        val task = aiState.task
        val label = when {
            aiState.error != null -> "${task.title}失败"
            aiState.loading -> when (task) {
                is WriteAiTask.Edit -> "正在${task.title}"
                is WriteAiTask.Idea -> "正在构思灵感"
                WriteAiTask.Review -> "正在生成点评"
                WriteAiTask.Consistency -> "正在检查矛盾"
            }
            else -> when (task) {
                is WriteAiTask.Edit -> "核对${task.title}候选"
                is WriteAiTask.Idea -> "查看灵感结果"
                WriteAiTask.Review -> "查看读者点评"
                WriteAiTask.Consistency -> "查看矛盾检查"
            }
        }
        val reason = when {
            aiState.error != null -> "在上方重试或关闭"
            aiState.loading -> "结果在上方面板生成"
            task is WriteAiTask.Edit -> "在上方采纳或放弃"
            task is WriteAiTask.Idea -> "在上方插入或关闭"
            else -> "在上方查看或关闭"
        }
        return EditorRecommendation(EditorRecommendationKind.PENDING_AI, label, reason, enabled = false)
    }
    if (status == ChapterStatus.FINAL) return EditorRecommendation(EditorRecommendationKind.UNLOCK, "解锁修改", "定稿正文已锁定")
    if (!aiReady) return EditorRecommendation(EditorRecommendationKind.CONFIGURE_AI, "设置写作 AI", "连接后才会发送请求")
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    if (start < end && text.subSequence(start, end).isNotBlank()) {
        return EditorRecommendation(EditorRecommendationKind.REVISE_SELECTION, "改写选中文字", "已选 ${end - start} 字")
    }
    if (text.isBlank()) return EditorRecommendation(EditorRecommendationKind.DRAFT_CHAPTER, "AI 写整章草稿", "先看细纲，再生成候选稿")
    if (status == ChapterStatus.REVISION) return EditorRecommendation(EditorRecommendationKind.REVIEW_CHAPTER, "三道审稿", "本章正在修订")
    // 细纲卡还有没写的拍：一点就从光标处写下一拍，不弹面板（3 个方向、范本按记住的来）。
    if (nextBeat != null) return EditorRecommendation(EditorRecommendationKind.WRITE_BEAT, "写第 ${nextBeat.index} 拍", nextBeat.text)
    return EditorRecommendation(EditorRecommendationKind.CONTINUE, "AI 接着写", "打开 AI 写面板，从光标处写")
}

/**
 * 四组工具是稳定的全量入口；上面的智能推荐可以随编辑状态改变。
 * 不另起标题栏：分段控件铺满一行、关闭钮在它右端，下面一格格工具等宽铺满（[ToolTileGrid]），
 * 整个面板压在两行左右，键盘上方仍给正文留出地方。
 */
@Composable
internal fun WritingEditorToolsPanel(
    selected: EditorToolGroup,
    shortcuts: List<EditorToolShortcut>,
    onSelect: (EditorToolGroup) -> Unit,
    onClose: () -> Unit,
) {
    InlinePanel(
        title = "写作工具",
        onClose = onClose,
        closeLabel = "关闭写作工具",
        header = {
            SegmentedControl(
                EditorToolGroup.entries.map { it to it.label },
                selected,
                modifier = Modifier.weight(1f),
                onSelect = onSelect,
            )
        },
    ) {
        ToolTileGrid(
            shortcuts.map { ToolTile(it.label, it.icon, it.onClick) },
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )
    }
}
