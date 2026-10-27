package com.xycs.lingyuereader.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.ReferenceAnalysis
import com.xycs.lingyuereader.data.ReferenceExcerpt
import com.xycs.lingyuereader.data.ReferencePart
import com.xycs.lingyuereader.data.WritingReference
import com.xycs.lingyuereader.data.WritingReferenceImport
import com.xycs.lingyuereader.ui.glass.GlassDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.io.File

/** External reference books are imported here, never into the current story's setting package. */
@Composable
fun WritingReferenceScreen(vm: AppViewModel, workId: String, initialChapterId: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val works by vm.writing.works.collectAsStateWithLifecycle()
    val work = works.firstOrNull { it.id == workId }
    val config by vm.aiConfig.collectAsStateWithLifecycle()
    val allState by vm.referenceAi.state.collectAsStateWithLifecycle()
    var references by remember(workId) { mutableStateOf<List<WritingReference>?>(null) }
    var referenceId by rememberSaveable(workId) { mutableStateOf<String?>(null) }
    var referenceChapterId by rememberSaveable(workId) { mutableStateOf<String?>(null) }
    var manualChapterChoice by rememberSaveable(workId) { mutableStateOf(false) }
    var targetChapterId by rememberSaveable(workId) { mutableStateOf(initialChapterId) }
    var targetNumber by remember(workId) { mutableStateOf<Int?>(null) }
    var partName by rememberSaveable(workId) { mutableStateOf(ReferencePart.OPENING.name) }
    val part = ReferencePart.valueOf(partName)
    var fragmentLimit by rememberSaveable(workId) { mutableStateOf(2400) }
    var rangeStart by rememberSaveable(workId) { mutableStateOf("1") }
    var rangeEnd by rememberSaveable(workId) { mutableStateOf("") }
    var excerpt by remember(workId) { mutableStateOf<ReferenceExcerpt?>(null) }
    var reading by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var showExcerpt by remember { mutableStateOf(false) }
    var showSent by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<WritingReference?>(null) }
    var history by remember(workId) { mutableStateOf<List<ReferenceAnalysis>>(emptyList()) }
    var historySelection by remember { mutableStateOf<ReferenceAnalysis?>(null) }
    var historyCurrent by remember { mutableStateOf<Boolean?>(null) }
    val reference = references?.firstOrNull { it.id == referenceId }
    val sourceChapter = reference?.chapters?.firstOrNull { it.id == referenceChapterId }
    val target = work?.chapters?.firstOrNull { it.id == targetChapterId }
    val state = allState?.takeIf { it.workId == workId && it.chapterId == targetChapterId }
    val running = starting || (allState?.workId == workId && allState?.loading == true)
    val maxChars = if (part == ReferencePart.CHAPTER) 12_000 else fragmentLimit
    val start = (rangeStart.toIntOrNull()?.minus(1))?.coerceAtLeast(0) ?: 0
    val end = rangeEnd.toIntOrNull() ?: start + fragmentLimit
    val snackbar = rememberWritingMessages(vm)

    LaunchedEffect(workId) {
        try {
            val list = vm.writing.references(workId)
            references = list
            if (referenceId == null) referenceId = list.firstOrNull()?.id
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "范本列表读取失败" }
    }
    LaunchedEffect(workId, work?.chapters) {
        if (targetChapterId == null) targetChapterId = work?.chapters?.firstOrNull()?.id
    }
    LaunchedEffect(workId, initialChapterId) {
        // The same screen type can be reused when entering from another editor in this work.
        // An explicit entry chapter wins over the previous saved choice, but later manual picks stay put.
        if (initialChapterId != null && targetChapterId != initialChapterId) {
            targetChapterId = initialChapterId
            manualChapterChoice = false
            referenceChapterId = null
            excerpt = null
            historySelection = null
        }
    }
    LaunchedEffect(workId, targetChapterId) {
        val id = targetChapterId ?: return@LaunchedEffect
        targetNumber = null
        historySelection = null
        vm.referenceAi.load(workId, id)
        try {
            targetNumber = vm.writingAi.writingTask(workId, id)?.chapter
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "当前章资料读取失败" }
    }
    LaunchedEffect(reference?.id, targetChapterId, targetNumber) {
        // Only a unique explicit chapter number is a match. Prefaces and missing numbers are not chapter one.
        if (!manualChapterChoice) {
            val matches = targetNumber?.let { no -> reference?.chapters?.filter { it.number == no } }.orEmpty()
            referenceChapterId = matches.singleOrNull()?.id
        }
    }
    LaunchedEffect(workId, referenceId, referenceChapterId, part, fragmentLimit, rangeStart, rangeEnd) {
        excerpt = null
        previewError = null
        val ref = referenceId ?: return@LaunchedEffect
        val chapter = referenceChapterId ?: return@LaunchedEffect
        reading = true
        try {
            if (part == ReferencePart.RANGE) {
                require(rangeStart.toIntOrNull()?.let { it > 0 } == true && end > start) { "请填写有效的起止位置，起始位置从 1 算起。" }
            }
            val selectedEnd = if (part == ReferencePart.RANGE && rangeEnd.isBlank()) {
                minOf(end, vm.writing.referenceText(workId, ref, chapter)?.length ?: 0)
            } else end
            excerpt = vm.writing.selectedChapterExcerpt(workId, ref, chapter, part, maxChars, start, selectedEnd)
            if (excerpt == null) previewError = "所选章节已经不存在，请重新选择章节。"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { previewError = e.message ?: "范本片段读取失败" }
        finally { reading = false }
    }
    LaunchedEffect(workId, targetChapterId, state?.analysis?.id, state?.loading) {
        try { history = vm.writing.referenceAnalyses(workId, targetChapterId) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "历史分析读取失败" }
    }
    LaunchedEffect(historySelection?.id, state?.analysis?.id, work) {
        historyCurrent = null
        val result = historySelection ?: state?.analysis ?: return@LaunchedEffect
        try { historyCurrent = vm.writing.referenceAnalysisCurrent(workId, result) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { historyCurrent = false }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            error = null
            var copy: File? = null
            try {
                val (name, file) = withContext(Dispatchers.IO) { referenceFile(context, uri) }
                copy = file
                val imported = vm.writing.importReference(workId, name, file)
                references = vm.writing.references(workId)
                referenceId = imported.id
                manualChapterChoice = false
                referenceChapterId = null
                excerpt = null
                vm.showMessage("已添加范本《${imported.title}》，请选择要分析的章节和范围")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "导入范本失败：${e.message ?: "文件打不开"}" }
            catch (_: OutOfMemoryError) { error = "导入范本失败：${WritingReferenceImport.TOO_LARGE}" }
            finally {
                copy?.let { withContext(NonCancellable + Dispatchers.IO) { it.delete() } }
                importing = false
            }
        }
    }

    SubPageScaffold("写作范本", onBack = { vm.back() }, snackbar = snackbar) { padding ->
        val c = LocalGroupedColors.current
        LazyColumn(
            Modifier.fillMaxSize().imePadding().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            item(key = "source") {
                GroupedSection(footer = "导入作者选择的 TXT 或 EPUB，分析其叙事与对白，再对照你正在写的章节给建议。范本独立保存，情节和人物不会写入本书设定。") {
                    row {
                        if (importing) LoadingRow("正在导入范本……")
                        else ActionRow("添加范本", enabled = !running) { pickFile.launch(arrayOf("*/*")) }
                    }
                    when {
                        references == null -> row { LoadingRow("正在读范本列表……") }
                        references?.isEmpty() == true -> row { EmptyRow("还没有范本，先导入一本你想研究的作品。") }
                        else -> {
                            row { NavRow("参考作品", value = reference?.title ?: "请选择", enabled = !running && !importing) { picker = "reference" } }
                            reference?.let { book ->
                                row { TwoLine("《${book.title}》", listOfNotNull(book.author?.takeIf { it.isNotBlank() }, "${book.chapters.size} 章", book.sourceName).joinToString(" · ")) }
                                row { ActionRow("移除这本范本", destructive = true, enabled = !running && !importing) { deleting = book } }
                            }
                        }
                    }
                }
            }
            if (references?.isNotEmpty() == true) item(key = "selection") {
                GroupedSection(header = "这次分析哪里", footer = when {
                    targetNumber == null -> "当前章没有可确认的章号，请手动选择范本章节。"
                    sourceChapter == null -> "没有找到唯一对应的第 $targetNumber 章，请手动选择范本章节。"
                    part == ReferencePart.CHAPTER -> "将发送所选整章，最多 12000 字符；超过上限请改选开头、结尾或自选片段。"
                    else -> "只分析下面实际选中的片段。需要讨论整章结构时，请将范围切换为“整章”。"
                }) {
                    row { NavRow("正在写的章节", value = target?.title ?: "请选择", enabled = !running) { picker = "target" } }
                    row { NavRow("范本章节", value = sourceChapter?.let { ch -> ch.number?.let { "第 $it 章 · ${ch.title}" } ?: "${ch.title}（未识别章号）" } ?: "请选择", enabled = !running && reference != null) { picker = "chapter" } }
                    row {
                        PickerRow("范本范围", ReferencePart.entries.map { it to it.label }, part, enabled = !running) {
                            if (partName != it.name) { partName = it.name; excerpt = null }
                        }
                    }
                    if (part != ReferencePart.CHAPTER) row {
                        PickerRow(if (part == ReferencePart.RANGE) "留空时选取" else "片段长度上限", listOf(1000, 2400, 4000, 8000).map { it to "$it 字符" }, fragmentLimit, enabled = !running) {
                            if (fragmentLimit != it) { fragmentLimit = it; excerpt = null }
                        }
                    }
                    if (part == ReferencePart.RANGE) row {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextField(value = rangeStart, onValueChange = { rangeStart = it.filter(Char::isDigit) }, label = "从第几个字符起（从 1 算起）", enabled = !running, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            TextField(value = rangeEnd, onValueChange = { rangeEnd = it.filter(Char::isDigit) }, label = "到第几个字符止（可空）", enabled = !running, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }
                    when {
                        reading -> row { LoadingRow("正在准备所选原文……") }
                        previewError != null -> row { ErrorText(previewError!!, Modifier.padding(16.dp)) }
                        excerpt != null -> {
                            val chosen = excerpt!!
                            row { ReferenceRangeRuler(chosen.start, chosen.end, chosen.chapterLength, chosen.part,
                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
                            row { NavRow("预览将发送的原文", value = "${chosen.text.length} 字符", subtitle = "${sourceChapter?.title.orEmpty()} · 第 ${chosen.start + 1}—${chosen.end} 字符" + if (chosen.truncated) " · 本次仅选定片段" else "") { showExcerpt = true } }
                        }
                    }
                }
                if (running) {
                    GroupedSection {
                        row { LoadingRow("正在分析范本与当前章……") }
                        row { ActionRow("停止分析", destructive = true) { vm.referenceAi.stop() } }
                    }
                } else {
                    GroupedSection(footer = if (config.ready) "点下方按钮才会将所选范本原文和本章相关资料发给 AI，按所设服务商计费。浏览、选章与查看历史结果不会发请求。" else "先在“我的 → AI 助手”设置服务商与模型，再发起分析。") {
                        row {
                            ActionRow("分析并给本章建议", enabled = !importing && !reading && config.ready && target != null && excerpt != null && previewError == null) {
                                val ref = referenceId
                                val chapter = referenceChapterId
                                val own = targetChapterId
                                if (ref != null && chapter != null && own != null) {
                                    historySelection = null
                                    val expected = excerpt?.fingerprint
                                    val chosenPart = part
                                    val chosenStart = excerpt?.start ?: start
                                    val chosenEnd = excerpt?.end ?: end
                                    val chosenLimit = maxChars
                                    starting = true
                                    scope.launch {
                                        try {
                                            vm.writing.flush()
                                            val fresh = vm.writing.selectedChapterExcerpt(workId, ref, chapter, chosenPart, chosenLimit, chosenStart, chosenEnd)
                                            check(fresh != null && fresh.fingerprint == expected) { "所选原文已经变化，请确认预览后再分析。" }
                                            vm.referenceAi.analyze(workId, own, ref, chapter, chosenPart, chosenStart, chosenEnd, chosenLimit)
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { error = e.message ?: "分析准备失败" }
                                        finally { starting = false }
                                    }
                                }
                            }
                        }
                        if (!config.ready) row { NavRow("设置 AI 助手") { vm.open(Screen.Ai) } }
                    }
                }
            }
            error?.let { message -> item(key = "error") { GroupedSection { row { ErrorText(message, Modifier.padding(16.dp)) } } } }
            state?.error?.let { message -> item(key = "ai-error") {
                GroupedSection {
                    row { ErrorText(message, Modifier.padding(16.dp)) }
                    row { ActionRow("重试分析", enabled = !running && config.ready) { historySelection = null; vm.referenceAi.retry() } }
                }
            } }
            val selectedAnalysis = historySelection ?: state?.analysis
            val body = historySelection?.body ?: state?.text.orEmpty()
            if (body.isNotBlank()) item(key = "result") {
                GroupedSection(header = "范本分析与本章写法", footer = "借鉴结构、信息安排与对话作用，具体人物、事件和措辞由你按本书重新组织。") {
                    selectedAnalysis?.let { result ->
                        row { TwoLine("《${result.referenceTitle}》 · ${result.chapterTitle}", "${result.part.label} · 第 ${result.start + 1}—${result.end} 字符 · ${whenLabel(result.createdAt)}") }
                    }
                    state?.note?.takeIf { historySelection == null }?.let { note -> row { Text(note, color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) } }
                    if (historyCurrent == false) row { Text("来源或当前章已变动，建议重新分析。", color = c.warning, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
                    row { SelectionContainer { Column(Modifier.fillMaxWidth().padding(16.dp)) { MarkdownText(body) } } }
                    if (historySelection == null && state?.loading != true) row { UsageNote(state?.usage, state?.cost, Modifier.padding(16.dp)) }
                    if (target != null) row { NavRow("回到正文继续写", value = target.title) {
                        val below = vm.stack.value.dropLast(1).lastOrNull()
                        if (below is Screen.Editor && below.workId == workId && below.chapterId == target.id) vm.back()
                        else vm.open(Screen.Editor(workId, target.id))
                    } }
                }
            }
            state?.request?.let { item(key = "sent") {
                GroupedSection { row { NavRow("看这次发给 AI 的内容", value = "${it.size} 字符") { showSent = true } } }
            } }
            if (history.isNotEmpty()) item(key = "history") {
                GroupedSection(header = "本章已有分析") {
                    history.forEach { result ->
                        row { NavRow("《${result.referenceTitle}》 · ${result.chapterTitle}", subtitle = "${result.part.label} · ${whenLabel(result.createdAt)}", enabled = !running) { historySelection = result } }
                    }
                }
            }
        }
        when (picker) {
            "reference" -> ReferenceChoiceDialog("参考作品", references.orEmpty().map { it.id to "《${it.title}》" }, referenceId, { picker = null }) {
                if (referenceId != it) {
                    referenceId = it
                    referenceChapterId = null
                    manualChapterChoice = false
                    excerpt = null
                }
                picker = null
            }
            "chapter" -> ReferenceChoiceDialog("范本章节", reference?.chapters.orEmpty().map { ch -> ch.id to (ch.number?.let { "第 $it 章 · ${ch.title}" } ?: "${ch.title}（未识别章号）") }, referenceChapterId, { picker = null }) {
                manualChapterChoice = true
                if (referenceChapterId != it) { referenceChapterId = it; excerpt = null }
                picker = null
            }
            "target" -> ReferenceChoiceDialog("正在写的章节", work?.chapters.orEmpty().map { it.id to it.title }, targetChapterId, { picker = null }) {
                if (targetChapterId != it) {
                    targetChapterId = it
                    referenceChapterId = null
                    manualChapterChoice = false
                    excerpt = null
                }
                picker = null
            }
        }
        if (showExcerpt) excerpt?.let { chosen ->
            GlassDialog(show = true, title = "《${reference?.title.orEmpty()}》 · ${sourceChapter?.title.orEmpty()}", summary = "${part.label} · 实际发送第 ${chosen.start + 1}—${chosen.end} 字符，共 ${chosen.text.length} 字符", onDismissRequest = { showExcerpt = false }) {
                SelectionContainer { Text(chosen.text, modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp).verticalScroll(rememberScrollState()), fontSize = 15.sp, lineHeight = 25.sp) }
                DialogOkButton("好") { showExcerpt = false }
            }
        }
        if (showSent) state?.request?.let { SentDialog(it) { showSent = false } }
        deleting?.let { book ->
            GlassDialog(show = true, title = "移除《${book.title}》", summary = "从这部作品的范本列表移除；本书的正文与设定不受影响。", onDismissRequest = { deleting = null }) {
                DialogButtons(onDismiss = { deleting = null }, onConfirm = {
                    deleting = null
                    scope.launch {
                        try {
                            check(vm.writing.deleteReference(workId, book.id)) { "范本已经不存在" }
                            references = vm.writing.references(workId)
                            if (referenceId == book.id) { referenceId = null; referenceChapterId = null; manualChapterChoice = false; excerpt = null }
                            if (state?.referenceId == book.id) vm.referenceAi.clear()
                            history = vm.writing.referenceAnalyses(workId, targetChapterId)
                            historySelection = null
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "移除范本失败" }
                    }
                }, confirmText = "移除", destructive = true)
            }
        }
    }
}

@Composable
private fun ReferenceChoiceDialog(title: String, options: List<Pair<String, String>>, selected: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    GlassDialog(show = true, title = title, onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(options, key = { it.first }) { (id, label) -> CheckRow(label, selected = id == selected) { onPick(id) } }
        }
        DialogOkButton("取消", primary = false, onClick = onDismiss)
    }
}

/**
 * 先看文档提供方报的大小，超过上限的在读取之前就拒绝；其余边读边写进缓存目录的临时文件，
 * 不在内存里攒整份文件。上次中断留下的临时文件顺手清掉。
 */
private fun referenceFile(context: Context, uri: Uri): Pair<String, File> {
    val resolver = context.contentResolver
    var size: Long? = null
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        if (!cursor.isNull(1)) size = cursor.getLong(1)
        cursor.getString(0)
    } ?: uri.lastPathSegment ?: "范本.txt"
    val limit = WritingReferenceImport.byteLimit(name)
    size?.let { require(it <= limit) { "范本文件过大，请先拆分后导入（上限 ${limit / 1024 / 1024} MB）" } }
    context.cacheDir.listFiles { file -> file.name.startsWith(TEMP_PREFIX) }.orEmpty().forEach { it.delete() }
    val target = File.createTempFile(TEMP_PREFIX, ".tmp", context.cacheDir)
    try {
        val input = resolver.openInputStream(uri) ?: error("无法读取所选范本文件")
        input.use { WritingReferenceImport.copyBounded(it, target, limit) }
    } catch (e: Throwable) {
        target.delete()
        throw e
    }
    return name to target
}

private const val TEMP_PREFIX = "reference-import-"
