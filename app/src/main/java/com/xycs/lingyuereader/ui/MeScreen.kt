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
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * “我的”，照 HyperOS 设置的样子：分组列表——阅读（词典、主题设置）、备份、关于，每行一个彩色图标。
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
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(vm::backup)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> confirmRestore = uri }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::setBackupFolder) }
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
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + LocalTabBarInset.current + 24.dp),
                overscrollEffect = null,
            ) {
                item {
                    if (busy) LinearProgressIndicator(Modifier.padding(horizontal = 32.dp, vertical = 4.dp))
                    // 每一行都带图标，按用途分三组：阅读、备份、关于。
                    GroupedSection(header = "阅读") {
                        iconRow {
                            NavRow(
                                "词典",
                                value = if (dicts.dicts.isNotEmpty()) "导入 ${dicts.dicts.size} 本" else if (dicts.builtinEnabled) "内置" else "已关闭",
                                icon = LingYueIcons.Translate,
                                iconBackground = Color(0xFFFF9500),
                            ) { vm.open(Screen.Dictionaries) }
                        }
                        iconRow {
                            NavRow(
                                "主题设置",
                                value = if (settings.eInk) "墨水屏模式" else appearance.mode.label,
                                icon = LingYueIcons.Tune,
                                iconBackground = Color(0xFF007AFF),
                            ) { vm.open(Screen.Theme) }
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
                                iconBackground = Color(0xFFAF52DE),
                            ) { vm.open(Screen.Ai) }
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
                            NavRow("备份书架", "${shelf.size} 本", icon = LingYueIcons.Upload, iconBackground = Color(0xFF34C759), enabled = !busy) {
                                backupLauncher.launch("灵阅备份-${stamp.format(java.util.Date())}.zip")
                            }
                        }
                        row {
                            NavRow("从备份恢复", icon = LingYueIcons.Download, iconBackground = Color(0xFF30B0C7), enabled = !busy) {
                                restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                            }
                        }
                        row {
                            ToggleRow("自动备份到文件夹", auto.enabled, enabled = !busy, icon = LingYueIcons.Timer, iconBackground = Color(0xFF5856D6)) { on ->
                                if (on) folderLauncher.launch(null) else vm.disableAutoBackup()
                            }
                        }
                        if (auto.enabled) {
                            row { NavRow("备份位置", auto.label, icon = LingYueIcons.Folder, iconBackground = Color(0xFF8E8E93)) { folderLauncher.launch(null) } }
                            row { NavRow("现在备份一次", icon = LingYueIcons.Upload, iconBackground = Color(0xFF5856D6), enabled = !busy, onClick = vm::backupNow) }
                        }
                    }
                    GroupedSection(header = "关于", footer = "灵阅：开源、无广告，默认不联网（只有开启 AI 助手并使用时才联网）。代码以 Apache-2.0 发布。") {
                        row { ValueRow("版本", version, icon = LingYueIcons.Info, iconBackground = Color(0xFF8E8E93)) }
                        row { NavRow("项目主页", "GitHub", icon = LingYueIcons.Share, iconBackground = Color(0xFF636366)) { openHome() } }
                    }
                }
            }
            confirmRestore?.let { uri ->
                GlassDialog(
                    show = true,
                    title = "从备份恢复",
                    summary = "备份里的书会加回书架；同一本书会用备份里的版本（含进度、书签批注）覆盖。阅读设置、生词本和阅读统计也会恢复。",
                    onDismissRequest = { confirmRestore = null },
                ) {
                    DialogButtons(confirm = "恢复", onDismiss = { confirmRestore = null }) {
                        vm.restore(uri)
                        confirmRestore = null
                    }
                }
            }
        }
    }
}
