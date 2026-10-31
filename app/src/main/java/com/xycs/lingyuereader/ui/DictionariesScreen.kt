package com.xycs.lingyuereader.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.dict.DictInfo
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 词典管理，苹果分组列表的样子：内置词典一个开关；导入的词典一行一本，开关启用；
 * 右上角“编辑”进入编辑状态，每行出现删除和上下移动（像苹果列表的编辑模式）。
 */
@Composable
fun DictionariesScreen(vm: AppViewModel) {
    val settings by vm.dictSettings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var removing by remember { mutableStateOf<DictInfo?>(null) }
    var editing by remember { mutableStateOf(false) }
    // 一次多选：.mdx 和同名的 .mdd、.css 一起选。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importDictionaries(it) }

    LaunchedEffect(Unit) { vm.reloadDicts() }
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }
    if (settings.dicts.isEmpty()) editing = false

    SubPageScaffold(
        title = "词典",
        onBack = { vm.back() },
        actions = {
            if (settings.dicts.isNotEmpty()) {
                Text(
                    if (editing) "完成" else "编辑",
                    color = LocalGroupedColors.current.tint,
                    fontSize = TypeScale.Headline,
                    fontWeight = if (editing) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(Radius.Medium)).clickable { editing = !editing }.padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }
            IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.padding(end = 12.dp)) {
                Icon(LingYueIcons.Add, contentDescription = "导入词典")
            }
        },
        snackbar = snackbar,
    ) { padding ->
        val c = LocalGroupedColors.current
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            overscrollEffect = null,
        ) {
            item {
                GroupedSection(header = "内置", footer = "Unihan 字典（读音、部首、笔画）与 CC-CEDICT 词典（英文释义），离线可用。") {
                    row { ToggleRow("基础字典与词典", settings.builtinEnabled, onChange = vm::setBuiltinDictEnabled) }
                }
                GroupedSection(
                    header = "导入的词典",
                    footer = if (settings.dicts.isEmpty()) {
                        "导入 MDict 词典（.mdx）。词典带的 .mdd（图片、发音）和 .css 样式请一起选上。"
                    } else {
                        "查词结果按这里的顺序排列，点“编辑”可以调整顺序或删除。在线查询会打开浏览器。"
                    },
                ) {
                    if (busy) row { LoadingRow("正在导入并建立索引……") }
                    settings.dicts.forEachIndexed { i, d ->
                        row {
                            val detail = buildString {
                                append("${d.entries} 个词条")
                                if (d.mdds.isNotEmpty()) append(" · 含资源")
                            }
                            if (editing) {
                                EditingDictRow(
                                    title = d.title,
                                    detail = detail,
                                    canMoveUp = i > 0,
                                    canMoveDown = i < settings.dicts.lastIndex,
                                    onMove = { up -> vm.moveDict(d.id, up) },
                                    onDelete = { removing = d },
                                )
                            } else {
                                ToggleRow(d.title, d.enabled, subtitle = detail) { vm.setDictEnabled(d.id, it) }
                            }
                        }
                    }
                    if (settings.dicts.isEmpty()) row { ActionRow("导入词典…", enabled = !busy) { picker.launch(arrayOf("*/*")) } }
                }
            }
        }
        // 弹窗要放在 Scaffold 里面才显示得出来。
        removing?.let { d ->
            GlassDialog(show = true, title = "删除词典", summary = "删除《${d.title}》及其索引？", onDismissRequest = { removing = null }) {
                DialogButtons(
                    onDismiss = { removing = null },
                    onConfirm = {
                        vm.removeDict(d.id)
                        removing = null
                    },
                    confirmText = "删除",
                    destructive = true,
                )
            }
        }
    }
}

/** 编辑状态下的一行：左边红色删除，右边上移、下移；名字和说明的字号、行高同 [NavRow]。 */
@Composable
private fun EditingDictRow(
    title: String,
    detail: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (up: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalGroupedColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete)
                .semantics { contentDescription = "删除" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(c.destructive), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 10.dp, height = 2.dp).background(Color.White))
            }
        }
        Column(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = c.label, fontSize = TypeScale.Body, fontWeight = FontWeight.Medium)
            Text(detail, color = c.secondary, fontSize = TypeScale.Caption, lineHeight = TypeScale.CaptionLine)
        }
        listOf(true to canMoveUp, false to canMoveDown).forEach { (up, enabled) ->
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled) { onMove(up) }
                    .semantics { contentDescription = if (up) "上移" else "下移" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    LingYueIcons.ChevronDown,
                    contentDescription = null,
                    tint = if (enabled) c.tint else c.tertiary,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (up) 180f else 0f },
                )
            }
        }
    }
}
