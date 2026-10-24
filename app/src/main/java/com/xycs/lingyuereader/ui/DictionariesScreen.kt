package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.ui.glass.GlassCard
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.dict.DictInfo
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 词典管理：内置字典词典的开关，导入的 MDict 词典的启用、排序、删除。 */
@Composable
fun DictionariesScreen(vm: AppViewModel) {
    val settings by vm.dictSettings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var removing by remember { mutableStateOf<DictInfo?>(null) }
    // 一次多选：.mdx 和同名的 .mdd、.css 一起选。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importDictionaries(it) }

    LaunchedEffect(Unit) { vm.reloadDicts() }
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }

    GlassScaffold(
        topBar = {
            SmallTopAppBar(
                color = Color.Transparent,
                modifier = Modifier.glassBar(),
                title = "词典",
                navigationIcon = {
                    IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) {
                        Icon(LingYueIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.padding(end = 12.dp)) {
                        Icon(LingYueIcons.Add, contentDescription = "导入词典")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            overscrollEffect = null,
        ) {
            item {
                SmallTitle("内置")
                GlassCard(Modifier.padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                    SwitchPreference(
                        checked = settings.builtinEnabled,
                        onCheckedChange = vm::setBuiltinDictEnabled,
                        title = "基础字典与词典",
                        summary = "Unihan 字典（读音、部首、笔画）与 CC-CEDICT 词典（英文释义），离线可用",
                    )
                }
                SmallTitle("导入的词典")
            }
            if (busy) {
                item {
                    Row(Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) {
                        InfiniteProgressIndicator()
                        Text("正在导入并建立索引……", modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            if (settings.dicts.isEmpty()) {
                item {
                    GlassCard(Modifier.padding(horizontal = 12.dp)) {
                        Text(
                            "点右上角 + 导入 MDict 词典（.mdx）。词典带的 .mdd（图片、发音）和 .css 样式请一起选上。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }
            itemsIndexed(settings.dicts, key = { _, d -> d.id }) { i, d ->
                GlassCard(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), insideMargin = PaddingValues(0.dp)) {
                    SwitchPreference(
                        checked = d.enabled,
                        onCheckedChange = { vm.setDictEnabled(d.id, it) },
                        title = d.title,
                        summary = buildString {
                            append("${d.entries} 个词条")
                            if (d.mdds.isNotEmpty()) append(" · 含资源")
                        },
                    )
                    Row(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                        val colors = ButtonDefaults.textButtonColors()
                        TextButton("上移", onClick = { vm.moveDict(d.id, true) }, enabled = i > 0, colors = colors, modifier = Modifier.weight(1f))
                        TextButton("下移", onClick = { vm.moveDict(d.id, false) }, enabled = i < settings.dicts.lastIndex, colors = colors, modifier = Modifier.weight(1f))
                        TextButton("删除", onClick = { removing = d }, colors = colors, modifier = Modifier.weight(1f))
                    }
                }
            }
            item {
                Text(
                    "查词结果按上面的顺序排列。在线查询会打开浏览器，灵阅本身不联网。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                )
            }
        }
        // 弹窗要放在 Scaffold 里面才显示得出来。
        removing?.let { d ->
            GlassDialog(show = true, title = "删除词典", summary = "删除《${d.title}》及其索引？", onDismissRequest = { removing = null }) {
                DialogButtons(confirm = "删除", onDismiss = { removing = null }) {
                    vm.removeDict(d.id)
                    removing = null
                }
            }
        }
    }
}
