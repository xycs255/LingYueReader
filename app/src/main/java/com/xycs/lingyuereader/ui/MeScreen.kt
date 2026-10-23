package com.xycs.lingyuereader.ui

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
import com.xycs.lingyuereader.data.ReadingStats
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.time.LocalDate

/** “我的”：阅读统计、生词本、词典、备份恢复、关于。书架右上角的入口。 */
@Composable
fun MeScreen(vm: AppViewModel) {
    val stats by vm.readingStats.collectAsStateWithLifecycle()
    val vocab by vm.vocab.collectAsStateWithLifecycle()
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

    LaunchedEffect(Unit) {
        vm.reloadStats()
        vm.reloadVocabulary()
        vm.reloadDicts()
    }
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "我的",
                navigationIcon = {
                    IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 12.dp)) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
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
                if (busy) LinearProgressIndicator(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                SmallTitle("阅读")
                Card(Modifier.padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
                    val today = LocalDate.now()
                    val minutes = (stats[today]?.millis ?: 0) / 60_000
                    ArrowPreference(
                        title = "阅读统计",
                        summary = "今天 $minutes 分钟 · 连续 ${ReadingStats.streak(stats, today)} 天",
                        onClick = { vm.open(Screen.Stats) },
                    )
                    val learning = vocab.count { !it.mastered }
                    ArrowPreference(
                        title = "生词本",
                        summary = if (vocab.isEmpty()) "查词时收进来的词" else "学习中 $learning 个，共 ${vocab.size} 个",
                        onClick = { vm.open(Screen.Vocabulary) },
                    )
                    ArrowPreference(
                        title = "词典",
                        summary = buildString {
                            append(if (dicts.builtinEnabled) "内置字典词典" else "内置词典已关闭")
                            if (dicts.dicts.isNotEmpty()) append(" · 导入 ${dicts.dicts.size} 本")
                        },
                        onClick = { vm.open(Screen.Dictionaries) },
                    )
                }
                SmallTitle("数据")
                Card(Modifier.padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
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
                Card(Modifier.padding(horizontal = 12.dp), insideMargin = PaddingValues(0.dp)) {
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
            OverlayDialog(
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
