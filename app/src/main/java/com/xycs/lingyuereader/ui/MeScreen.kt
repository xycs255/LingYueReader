package com.xycs.lingyuereader.ui

import androidx.core.net.toUri
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Icon
import com.xycs.lingyuereader.R
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import com.xycs.lingyuereader.ui.glass.GlassDialog
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import androidx.compose.ui.graphics.Color
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 行首图标的语义色。由 [RowIcon] 淡化成浅底图标，同一个入口在各页使用同一色。
 */
internal object RowIconColors {
    /** 词典（Translate）。 */
    val Dictionary = Color(0xFF956B50)
    /** 主题设置（Tune）。 */
    val Theme = Color(0xFF8B6759)
    /** 文件夹（Folder）：书源、WebDAV、备份位置。 */
    val Folder = Color(0xFF66806B)
    /** AI（Sparkle）。 */
    val Ai = Color(0xFF8B6B79)
    /** 声音（VolumeUp）：离线语音、AI 朗读。 */
    val Voice = Color(0xFF5C7B78)
    /** 备份、上传（Upload）。 */
    val Upload = Color(0xFF687E63)
    /** 恢复、下载（Download）。 */
    val Download = Color(0xFF5B7C84)
    /** 定时（Timer）：自动备份、写作时给建议。 */
    val Timer = Color(0xFF88705D)
    /** 添加（Add）。 */
    val Add = Color(0xFFA94B36)
    /** 关于、版本这类说明（Info）。 */
    val Info = Color(0xFF7D746C)
    /** 外部链接（Share）。 */
    val Link = Color(0xFF696F6C)
}

/**
 * “我的”：分组列表——阅读、备份、关于；淡色图标辅助扫描，不压过文字。
 * 每行一个短名字，状态写在右边灰字或组下面的小字里。
 */
@Composable
fun MeScreen(vm: AppViewModel) {
    val dicts by vm.dictSettings.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val appearance by vm.appearance.collectAsStateWithLifecycle()
    val auto by vm.autoBackup.collectAsStateWithLifecycle()
    val ai by vm.aiConfig.collectAsStateWithLifecycle()
    val offline by vm.offlineVoiceState.collectAsStateWithLifecycle()
    val catalogs by vm.catalogs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(vm::backup)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> confirmRestore = uri }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::setBackupFolder) }
    // 闪退记录有几条（回到这页时重读）。
    var crashCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { crashCount = withContext(Dispatchers.IO) { com.xycs.lingyuereader.CrashLog.entries().size } }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val openHome = {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, "https://github.com/xycs255/LingYueReader".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        Unit
    }

    LaunchedEffect(Unit) {
        vm.reloadDicts()
    }
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        val scrollBehavior = MiuixScrollBehavior()
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                TopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "我的",
                    scrollBehavior = scrollBehavior,
                )
            },
            snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = LocalTabBarInset.current)) },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + LocalTabBarInset.current + 24.dp),
                    overscrollEffect = null,
                ) {
                    item {
                        // 每一行都带图标，按用途分三组：阅读、备份、关于。
                        GroupedSection(header = "阅读") {
                            iconRow {
                                NavRow(
                                    "词典",
                                    value = if (dicts.dicts.isNotEmpty()) "导入 ${dicts.dicts.size} 本" else if (dicts.builtinEnabled) "内置" else "已关闭",
                                    icon = LingYueIcons.Translate,
                                    iconBackground = RowIconColors.Dictionary,
                                ) { vm.open(Screen.Dictionaries) }
                            }
                            iconRow {
                                NavRow(
                                    "主题设置",
                                    value = if (settings.eInk) "墨水屏模式" else appearance.mode.label,
                                    icon = LingYueIcons.Tune,
                                    iconBackground = RowIconColors.Theme,
                                ) { vm.open(Screen.Theme) }
                            }
                            iconRow {
                                NavRow(
                                    "书源",
                                    value = if (catalogs.isEmpty()) "OPDS、WebDAV" else "${catalogs.size} 个",
                                    icon = LingYueIcons.Folder,
                                    iconBackground = RowIconColors.Folder,
                                ) { vm.open(Screen.Catalogs) }
                            }
                            iconRow {
                                NavRow(
                                    "AI 助手",
                                    value = when {
                                        !ai.enabled -> "未开启"
                                        !ai.complete -> "未填密钥"
                                        else -> ai.providerLabel
                                    },
                                    icon = LingYueIcons.Sparkle,
                                    iconBackground = RowIconColors.Ai,
                                ) { vm.open(Screen.Ai) }
                            }
                            iconRow {
                                NavRow(
                                    "离线语音",
                                    value = offline.config.current.takeIf { it.id in offline.installed }?.let { m -> if (offline.config.enabled) m.label else "未开启" } ?: "未下载",
                                    icon = LingYueIcons.VolumeUp,
                                    iconBackground = RowIconColors.Voice,
                                ) { vm.open(Screen.OfflineVoice) }
                            }
                        }
                        val stamp = remember { java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT) }
                        GroupedSection(
                            header = "备份",
                            footer = if (auto.enabled) {
                                buildString {
                                    append("每天一份，存到「${auto.label}」，留最近 ${com.xycs.lingyuereader.data.AutoBackup.KEEP} 份")
                                    if (auto.lastAt > 0) {
                                        append("；上次 ")
                                        append(java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(auto.lastAt)))
                                    }
                                    if (auto.lastError.isNotEmpty()) append("；上次失败：${auto.lastError}")
                                    append("。")
                                }
                            } else {
                                "备份包含书、阅读进度、书签批注、生词本、阅读统计和设置。自动备份可以选云盘或 Syncthing 等同步文件夹，换手机时从里面恢复。"
                            },
                        ) {
                            row {
                                NavRow("备份书架", "${shelf.size} 本", icon = LingYueIcons.Upload, iconBackground = RowIconColors.Upload, enabled = !busy) {
                                    backupLauncher.launch("灵阅备份-${stamp.format(java.util.Date())}.zip")
                                }
                            }
                            row {
                                NavRow("从备份恢复", icon = LingYueIcons.Download, iconBackground = RowIconColors.Download, enabled = !busy) {
                                    restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                                }
                            }
                            row {
                                ToggleRow("自动备份到文件夹", auto.enabled, enabled = !busy, icon = LingYueIcons.Timer, iconBackground = RowIconColors.Timer) { on ->
                                    if (on) folderLauncher.launch(null) else vm.disableAutoBackup()
                                }
                            }
                            if (auto.enabled) {
                                row { NavRow("备份位置", auto.label, icon = LingYueIcons.Folder, iconBackground = RowIconColors.Folder) { folderLauncher.launch(null) } }
                                row { NavRow("现在备份一次", icon = LingYueIcons.Upload, iconBackground = RowIconColors.Upload, enabled = !busy, onClick = vm::backupNow) }
                            }
                        }
                        GroupedSection(header = "关于", footer = "灵阅：开源、无广告，默认不联网（只在你使用 AI 助手、书源或下载离线语音模型时联网）。代码以 Apache-2.0 发布。") {
                            row { ValueRow("版本", version, icon = LingYueIcons.Info, iconBackground = RowIconColors.Info) }
                            row { NavRow("项目主页", "GitHub", icon = LingYueIcons.Share, iconBackground = RowIconColors.Link) { openHome() } }
                            row {
                                NavRow("闪退记录", crashCount?.let { if (it == 0) "没有" else "$it 条" }, icon = LingYueIcons.Info, iconBackground = RowIconColors.Info) {
                                    vm.open(Screen.CrashLog)
                                }
                            }
                        }
                    }
                }
                // 备份、恢复时的进度条浮在标题栏下面的空隙里，不占列表的高度：出现、消失时下面的卡片不被顶下去又弹回来。
                AnimatedVisibility(
                    visible = busy,
                    enter = Effects.enter(fadeIn(Motion.standard(Motion.SHORT))),
                    exit = Effects.exit(fadeOut(Motion.exit())),
                    modifier = Modifier.padding(top = padding.calculateTopPadding() + 2.dp),
                ) {
                    LinearProgressIndicator(Modifier.padding(horizontal = 32.dp))
                }
            }
            confirmRestore?.let { uri ->
                GlassDialog(
                    show = true,
                    title = "从备份恢复",
                    summary = "备份里的书会加回书架；同一本书会用备份里的版本（含进度、书签批注）覆盖。阅读设置、生词本和阅读统计也会恢复。",
                    onDismissRequest = { confirmRestore = null },
                ) {
                    DialogButtons(
                        onDismiss = { confirmRestore = null },
                        onConfirm = {
                            vm.restore(uri)
                            confirmRestore = null
                        },
                        confirmText = "恢复",
                    )
                }
            }
        }
    }
}
