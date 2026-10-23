package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.ui.glass.GlassCard
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** “我的”：词典、备份恢复、关于。首页底部导航的最后一页。 */
@Composable
fun MeScreen(vm: AppViewModel) {
    val dicts by vm.dictSettings.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(vm::backup)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> confirmRestore = uri }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::setBackupFolder) }

    LaunchedEffect(Unit) {
        vm.reloadDicts()
    }
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
                title = "我的",
            )
        },
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = LocalTabBarInset.current)) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + LocalTabBarInset.current + 24.dp),
            overscrollEffect = null,
        ) {
            item {
                if (busy) LinearProgressIndicator(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                SmallTitle("阅读")
                GlassCard(Modifier.padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                    ArrowPreference(
                        title = "词典",
                        summary = buildString {
                            append(if (dicts.builtinEnabled) "内置字典词典" else "内置词典已关闭")
                            if (dicts.dicts.isNotEmpty()) append(" · 导入 ${dicts.dicts.size} 本")
                        },
                        onClick = { vm.open(Screen.Dictionaries) },
                    )
                }
                SmallTitle("显示")
                GlassCard(Modifier.padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                    val settings by vm.settings.collectAsStateWithLifecycle()
                    val appearance by vm.appearance.collectAsStateWithLifecycle()
                    ArrowPreference(
                        title = "主题设置",
                        summary = buildString {
                            append(appearance.mode.label)
                            if (appearance.dynamicColors) append(" · Monet 配色")
                            if (settings.eInk) append(" · 墨水屏模式") else if (!appearance.blur && !appearance.liquidGlass) append(" · 不用玻璃")
                            if (appearance.scale != 1f) append(" · 缩放 ${(appearance.scale * 100).toInt()}%")
                        },
                        onClick = { vm.open(Screen.Theme) },
                    )
                }
                SmallTitle("数据")
                GlassCard(Modifier.padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                    val auto by vm.autoBackup.collectAsStateWithLifecycle()
                    SwitchPreference(
                        title = "自动备份到文件夹",
                        summary = if (auto.enabled) {
                            buildString {
                                append("每天一份，存到「${auto.label}」，留最近 ${com.xycs.lingyuereader.data.AutoBackup.KEEP} 份")
                                if (auto.lastAt > 0) {
                                    append(" · 上次 ")
                                    append(java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(auto.lastAt)))
                                }
                                if (auto.lastError.isNotEmpty()) append(" · 上次失败：${auto.lastError}")
                            }
                        } else {
                            "选一个文件夹（可以是云盘、Syncthing 等同步目录），每天自动备份一份；在另一台设备上从里面的备份恢复"
                        },
                        checked = auto.enabled,
                        enabled = !busy,
                        onCheckedChange = { on -> if (on) folderLauncher.launch(null) else vm.disableAutoBackup() },
                    )
                    if (auto.enabled) {
                        ArrowPreference(title = "现在备份一次", enabled = !busy, onClick = vm::backupNow)
                        ArrowPreference(title = "换个文件夹", summary = auto.label, onClick = { folderLauncher.launch(null) })
                    }
                    val stamp = remember { java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT) }
                    ArrowPreference(
                        title = "备份书架",
                        summary = "${shelf.size} 本书，连同进度、书签批注、生词本、阅读统计和设置，存成一个 zip",
                        enabled = !busy,
                        onClick = { backupLauncher.launch("灵阅备份-${stamp.format(java.util.Date())}.zip") },
                    )
                    ArrowPreference(
                        title = "从备份恢复",
                        summary = "换手机时用",
                        enabled = !busy,
                        onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    )
                }
                SmallTitle("关于")
                GlassCard(Modifier.padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                    val version = remember {
                        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
                    }
                    ArrowPreference(
                        title = "灵阅 $version",
                        summary = "开源、离线、无广告的阅读器 · Apache-2.0 · 在浏览器中打开项目主页",
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xycs255/LingYueReader")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
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
