package com.xycs.lingyuereader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.tts.OfflineModel
import com.xycs.lingyuereader.tts.OfflineModels
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 离线语音：在手机上用 sherpa-onnx 合成，不联网、不花钱。模型不打包在安装包里，读者选一个下载（或从文件导入），
 * 之后听书、讲书都能用。
 */
@Composable
fun OfflineVoiceScreen(vm: AppViewModel) {
    val state by vm.offlineVoiceState.collectAsStateWithLifecycle()
    val config = state.config
    val current = config.current
    val ready = current.id in state.installed
    val task = state.task
    var confirmDownload by remember { mutableStateOf<OfflineModel?>(null) }
    var removing by remember { mutableStateOf<OfflineModel?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importOfflineModel) }

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                SmallTopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "离线语音",
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) {
                            Icon(LingYueIcons.Back, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                overscrollEffect = null,
            ) {
                item {
                    GroupedSection(
                        footer = if (ready) "听书、AI 讲书用「${current.label}」在手机上合成：不联网、不花钱，锁屏也能听。"
                        else "在手机上合成语音：不联网、不花钱，锁屏也能听。先下载一个模型（只需一次，建议连 Wi-Fi）。",
                    ) {
                        row {
                            ToggleRow("听书用离线语音", config.enabled && ready, enabled = ready, icon = LingYueIcons.VolumeUp, iconBackground = RowIconColors.Voice) {
                                vm.setOfflineEnabled(it)
                            }
                        }
                    }
                    GroupedSection(
                        header = "模型",
                        footer = "模型来自 sherpa-onnx 在 GitHub 上的发布页，下载后核对校验值。点已下载的模型选用它，长按删除。",
                    ) {
                        OfflineModels.ALL.forEach { m ->
                            val installed = m.id in state.installed
                            val running = task != null && task.model == m.id
                            row {
                                when {
                                    running -> TaskRow(m.label, "${task!!.phase.label} ${(task.fraction * 100).toInt()}%", task.fraction, onCancel = vm::cancelOfflineTask)
                                    installed -> CheckRow(
                                        m.label,
                                        m.id == current.id,
                                        subtitle = "${m.summary} · 已下载，占 ${(state.sizes[m.id] ?: 0) / 1_000_000} MB",
                                        onLongClick = { removing = m },
                                    ) { vm.setOfflineEnabled(true, m.id) }
                                    else -> NavRow(m.label, value = "下载 ${m.sizeLabel}", subtitle = m.summary, enabled = task == null) { confirmDownload = m }
                                }
                            }
                        }
                        if (task != null && task.model == null) {
                            row { TaskRow("从文件导入", "${task.phase.label} ${(task.fraction * 100).toInt()}%", task.fraction, onCancel = vm::cancelOfflineTask) }
                        }
                        // 出错写在卡片里，不顶掉下面的说明。
                        state.error?.let { e -> row { ErrorText(e, Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) } }
                    }
                    if (ready) {
                        GroupedSection(header = "声音") {
                            current.voices.forEach { v ->
                                row { CheckRow(v.label, v.name == config.voice(current)) { vm.saveOfflineConfig(config.withVoice(v.name)) } }
                            }
                            row {
                                ActionRow("试听") {
                                    previewError = null
                                    vm.previewOffline { previewError = "试听失败：$it" }
                                }
                            }
                            previewError?.let { e -> row { ErrorText(e, Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) } }
                        }
                    }
                    GroupedSection(
                        footer = "下载不了时，可以在电脑上打开 github.com/k2-fsa/sherpa-onnx/releases 的 tts-models，" +
                            "下载 ${OfflineModels.ALL.joinToString(" 或 ") { it.id + ".tar.bz2" }}，拷到手机上再导入。",
                    ) {
                        row { ActionRow("从文件导入…", enabled = task == null) { picker.launch(arrayOf("*/*")) } }
                    }
                    GroupedSection(
                        header = "关于",
                        footer = "引擎：sherpa-onnx（Apache-2.0）。模型：${OfflineModels.ALL.joinToString("、") { it.license }}。" +
                            "模型占几百 MB 内存，闲置几分钟后自动释放。",
                    ) {}
                }
            }
            confirmDownload?.let { m ->
                GlassDialog(
                    show = true,
                    title = "下载「${m.label}」",
                    summary = "要下载 ${m.sizeLabel}，装好后约占 ${m.installedMb} MB。建议连 Wi-Fi。下到一半中断的，下次接着下。",
                    onDismissRequest = { confirmDownload = null },
                ) {
                    DialogButtons(
                        onDismiss = { confirmDownload = null },
                        onConfirm = {
                            vm.downloadOfflineModel(m)
                            confirmDownload = null
                        },
                        confirmText = "下载",
                    )
                }
            }
            removing?.let { m ->
                GlassDialog(show = true, title = "删除模型", summary = "删除「${m.label}」？要用时可以再下载。", onDismissRequest = { removing = null }) {
                    DialogButtons(
                        onDismiss = { removing = null },
                        onConfirm = {
                            vm.deleteOfflineModel(m)
                            removing = null
                        },
                        confirmText = "删除",
                        destructive = true,
                    )
                }
            }
        }
    }
}

/** 正在下载、解压的一行：名字、进度文字、细进度条，右边“取消”。 */
@Composable
private fun TaskRow(title: String, status: String, fraction: Float, onCancel: () -> Unit) {
    val c = LocalGroupedColors.current
    GroupedRow {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(title, color = c.label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(status, color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.separator)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(2.dp)).background(c.tint))
            }
        }
        Text(
            "取消",
            color = c.tint,
            fontSize = 15.sp,
            modifier = Modifier
                .padding(start = 12.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "取消$title", onClick = onCancel)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
