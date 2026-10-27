package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import com.xycs.lingyuereader.data.DictGloss
import com.xycs.lingyuereader.ui.glass.GlassCard
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import androidx.compose.ui.graphics.Color
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.data.VocabWord
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.SnackbarResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 生词本：查词时收进来的词，分“学习中”和“已掌握”，可以翻卡片复习、导出。 */
@Composable
fun VocabularyScreen(vm: AppViewModel) {
    val words by vm.vocab.collectAsStateWithLifecycle()
    val aiConfig by vm.aiConfig.collectAsStateWithLifecycle()
    // AI 例句边生成边变：只在卡片里读，不让每个流式片段都把整页（和下面按全部生词算的东西）重算一遍。
    val vocabAi = vm.vocabAi.collectAsStateWithLifecycle()
    val filling by vm.vocabFilling.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val snackbar = remember { top.yukonga.miuix.kmp.basic.SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }
    var mastered by rememberSaveable { mutableStateOf(false) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 上一个“已删除，撤销”提示：再删一个词时先收起它，免得提示排成长队。
    var undoing by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) { vm.reloadVocabulary() }
    val learning = remember(words) { words.filterNot { it.mastered } }
    // 到期的词：按遗忘曲线排到今天或更早的。
    val now = remember(words) { System.currentTimeMillis() }
    val due = remember(learning, now) { learning.filter { it.isDue(now) }.sortedWith(compareBy({ it.due }, { it.addedAt })) }
    val shown = remember(words, mastered) { words.filter { it.mastered == mastered } }
    val anyBlank = remember(words) { words.any { DictGloss.clean(it.gloss).isBlank() } }

    // 和书架、统计、我的一样用大标题，往上滚时收成小标题。
    val scrollBehavior = MiuixScrollBehavior()
    GlassScaffold(
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                modifier = Modifier.glassBar(),
                title = if (reviewing) "复习生词" else "生词本",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (reviewing) {
                        IconButton(onClick = { reviewing = false }, modifier = Modifier.padding(start = 12.dp)) {
                            Icon(LingYueIcons.Back, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    // 开了 AI 助手、有词还没释义时：一键补上中文释义。
                    if (!reviewing && aiConfig.ready && anyBlank) {
                        if (filling) {
                            // 顶栏在设置页配色外面，转圈的颜色直接取主题强调色。
                            Spinner(Modifier.padding(horizontal = 14.dp), size = 20.dp, color = MiuixTheme.colorScheme.primary)
                        } else {
                            IconButton(onClick = vm::fillVocabGlosses) {
                                Icon(LingYueIcons.Sparkle, contentDescription = "AI 补释义")
                            }
                        }
                    }
                    if (!reviewing && words.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val list = words
                                scope.launch { shareVocabulary(context) { vm.vocabularyMarkdown(list) }?.let { snackbar.showSnackbar(it) } }
                            },
                            modifier = Modifier.padding(end = 12.dp),
                        ) {
                            Icon(LingYueIcons.Share, contentDescription = "导出生词本")
                        }
                    }
                },
            )
        },
        snackbarHost = { top.yukonga.miuix.kmp.basic.SnackbarHost(snackbar, modifier = Modifier.padding(bottom = LocalTabBarInset.current)) },
    ) { padding ->
        if (reviewing) {
            Review(due, Modifier.padding(padding).padding(bottom = LocalTabBarInset.current), onAnswer = { w, known -> vm.reviewWord(w.word, known) }, onDone = { reviewing = false })
            return@GlassScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + LocalTabBarInset.current + 24.dp),
            overscrollEffect = null,
        ) {
            item {
                ProvideGroupedColors {
                    val c = LocalGroupedColors.current
                    Column(Modifier.padding(top = 2.dp)) {
                        // 分段控件铺满一行，左右和下面的词卡一样缩进 12dp。
                        SegmentedControl(
                            listOf(false to "学习中 ${learning.size}", true to "已掌握 ${words.size - learning.size}"),
                            mastered,
                            Modifier.padding(horizontal = 12.dp),
                            height = 36.dp,
                        ) { mastered = it }
                        if (due.isNotEmpty()) {
                            // 复习是分段控件下面单独的一行操作（和词卡同宽的卡片），不挤在分段控件旁边。
                            GroupedSection(modifier = Modifier.padding(top = 4.dp)) {
                                row {
                                    NavRow(
                                        "复习 ${due.size}",
                                        subtitle = "${due.size} 个词今天到期，翻卡片过一遍",
                                        icon = LingYueIcons.Months,
                                        iconBackground = c.tint,
                                    ) { reviewing = true }
                                }
                            }
                        } else if (learning.isNotEmpty()) {
                            val next = learning.minOf { it.due }
                            Text(
                                "下次复习：${dueLabel(next, now)}",
                                color = c.secondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 2.dp, bottom = 10.dp),
                            )
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    when {
                        words.isEmpty() -> EmptyState(LingYueIcons.Star, "还没有生词", "读书时长按一个词，在查词面板里点星号，就收进来了。")
                        mastered -> EmptyState(LingYueIcons.Check, "还没有掌握的词", "复习时点“记得”，记牢的词会挪到这里。")
                        else -> EmptyState(LingYueIcons.Check, "都掌握了", "学习中的词都记住了。")
                    }
                }
            }
            items(shown, key = { it.word }) { w ->
                // 只有正在生成例句的这张卡跟着流式片段重组。
                val ai by remember(w.word) { derivedStateOf { vocabAi.value[w.word] } }
                WordCard(
                    w,
                    // 标为掌握、删除、切换标签时，其余的词滑到新位置。
                    modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()),
                    onToggleMastered = { vm.setMastered(w.word, !w.mastered) },
                    onRemove = {
                        // 删除不再确认，但给几秒撤销：原样放回原来的位置。
                        val index = words.indexOfFirst { it.word == w.word }
                        vm.removeFromVocabulary(w.word)
                        undoing?.cancel()
                        undoing = scope.launch {
                            if (snackbar.showSnackbar("已删除“${w.word}”", actionLabel = "撤销") == SnackbarResult.ActionPerformed) {
                                vm.restoreToVocabulary(w, index)
                            }
                        }
                    },
                    ai = ai,
                    onAi = { vm.vocabExample(w.word) }.takeIf { aiConfig.ready },
                )
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

/**
 * 一个生词：卡片里左右留 16dp。第一行词、读音，右边两个小图标（标为掌握 / 删除）；
 * 下面释义、书里的原句（左边一道竖线，像引文），最后一行灰色小字写出处和下次复习。
 */
@Composable
private fun WordCard(
    w: VocabWord,
    modifier: Modifier = Modifier,
    onToggleMastered: () -> Unit,
    onRemove: () -> Unit,
    /** 正在生成的 AI 例句。 */
    ai: com.xycs.lingyuereader.ai.AiTurn? = null,
    /** 开了 AI 助手时：生成例句和用法。 */
    onAi: (() -> Unit)? = null,
) {
    val scheme = MiuixTheme.colorScheme
    GlassCard(modifier.padding(horizontal = 12.dp, vertical = 6.dp), insideMargin = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.word, fontSize = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
            if (w.reading.isNotBlank()) {
                Text(w.reading, color = scheme.primary, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
            }
            Spacer(Modifier.weight(1f))
            if (onAi != null && ai == null) CardAction(LingYueIcons.Sparkle, if (w.example.isBlank()) "AI 例句" else "重新生成例句", onAi)
            CardAction(if (w.mastered) LingYueIcons.Timer else LingYueIcons.Check, if (w.mastered) "重新学习" else "已掌握", onToggleMastered)
            CardAction(LingYueIcons.Delete, "删除", onRemove)
        }
        Column(Modifier.padding(end = 8.dp)) {
            val gloss = DictGloss.clean(w.gloss)
            if (gloss.isNotBlank()) Text(gloss, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 4.dp))
            if (w.context.isNotBlank()) {
                Row(Modifier.padding(top = 10.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(scheme.primary.copy(alpha = 0.4f)))
                    Text(
                        highlighted(w.context, w.word),
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        fontFamily = FontFamily.Serif,
                        color = scheme.onSurfaceVariantSummary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            // AI 例句：生成中边出边显示，存下的直接显示。
            val example = ai?.answer?.takeIf { it.isNotBlank() } ?: w.example.takeIf { it.isNotBlank() }
            if (example != null || ai != null) {
                Column(
                    Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(scheme.primary.copy(alpha = 0.07f)).padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text("AI 例句", color = scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    when {
                        ai?.error != null -> Text(ai.error, color = scheme.error, fontSize = 13.sp)
                        example != null -> Text(example.trim(), fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 2.dp))
                        else -> Text("正在生成……", color = scheme.onSurfaceVariantSummary, fontSize = 13.sp)
                    }
                }
            }
            Text(
                listOfNotNull(
                    shortTitle(w.bookTitle).takeIf { it.isNotBlank() }?.let { "《$it》" },
                    dateFormat.format(Date(w.addedAt)),
                    if (w.mastered) null else "下次复习 " + dueLabel(w.due, System.currentTimeMillis()),
                ).joinToString(" · "),
                fontSize = 12.sp,
                color = scheme.onSurfaceVariantActions,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** 卡片右上角的小图标按钮：图标 20dp，能点的范围 40dp。 */
@Composable
private fun CardAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(20.dp))
    }
}

/** 书名太长时（“《覆汉》（校对版全本）作者：…”）只留书名号里的那段。 */
private fun shortTitle(title: String): String =
    Regex("《([^》]+)》").find(title)?.groupValues?.get(1)?.trim() ?: title.trim()

private fun highlighted(text: String, word: String) = buildAnnotatedString {
    append(text)
    var i = text.indexOf(word)
    while (i >= 0 && word.isNotEmpty()) {
        addStyle(SpanStyle(fontWeight = FontWeight.Bold), i, i + word.length)
        i = text.indexOf(word, i + word.length)
    }
}

/** 下次复习的日子：今天、明天、后天，或几月几日。 */
internal fun dueLabel(due: Long, now: Long): String {
    val days = ((VocabularyDays.startOfDay(due) - VocabularyDays.startOfDay(now)) / VocabularyDays.DAY).toInt()
    return when {
        days <= 0 -> "今天"
        days == 1 -> "明天"
        days == 2 -> "后天"
        else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(due))
    }
}

private typealias VocabularyDays = com.xycs.lingyuereader.data.Vocabulary.Companion

/**
 * 翻卡片复习到期的词：先看词和原句，想一想，点开看释义，再答“记得”或“还不熟”。
 * 记得的按遗忘曲线排到以后；还不熟的这一轮最后再出现一次。
 */
@Composable
private fun Review(words: List<VocabWord>, modifier: Modifier, onAnswer: (VocabWord, Boolean) -> Unit, onDone: () -> Unit) {
    // 复习开始时定下这一轮的顺序（最早到期的在前）。
    val deck = remember { words.toMutableStateList() }
    // 复习中每答一个，外面到期的词就会变；这一轮有几个词以开始时为准。
    val total = remember { words.size }
    val requeued = remember { mutableSetOf<String>() }
    // 最后一次还答“不熟”的词。
    val forgotten = remember { mutableSetOf<String>() }
    var index by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (index >= deck.size) {
            Spacer(Modifier.height(80.dp))
            Text("复习完了", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("记得 ${total - forgotten.size} 个，还不熟 ${forgotten.size} 个。", modifier = Modifier.padding(top = 12.dp))
            Text(
                "记得的会隔几天再出现，越熟隔得越久。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton("返回生词本", onClick = onDone, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.padding(top = 24.dp))
            return@Column
        }
        val w = deck[index]
        val haptic = LocalHapticFeedback.current
        Text("${index + 1} / ${deck.size}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        // 换下一个词：旧卡往左滑走，新卡从右边进来。
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                (slideInHorizontally(Motion.emphasized()) { it / 2 } + fadeIn(Motion.standard())) togetherWith
                    (slideOutHorizontally(Motion.exit(Motion.MEDIUM)) { -it / 2 } + fadeOut(Motion.exit()))
            },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 20.dp),
            label = "card",
        ) { i ->
            val card = deck.getOrNull(i) ?: return@AnimatedContent
            FlipCard(card, revealed && i == index) { revealed = !revealed }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                "还不熟",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                    onAnswer(w, false)
                    forgotten += w.word
                    if (requeued.add(w.word)) deck.add(w)
                    revealed = false
                    index++
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                "记得",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onAnswer(w, true)
                    forgotten -= w.word
                    revealed = false
                    index++
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 复习卡：正面是词和原句，点一下翻到背面看读音和释义。 */
@Composable
private fun FlipCard(w: VocabWord, revealed: Boolean, onFlip: () -> Unit) {
    val angle by animateFloatAsState(if (revealed) 180f else 0f, Motion.emphasized(), label = "flip")
    val c = MiuixTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationY = angle
                cameraDistance = 14f * density
            }
            .clip(RoundedCornerShape(24.dp))
            .background(c.surfaceContainer)
            .clickable(onClick = onFlip)
            .semantics { contentDescription = if (revealed) "复习卡背面" else "复习卡正面" }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 转过一半换成背面，背面再转回来免得字是反的。
        val back = angle > 90f
        Column(
            Modifier.graphicsLayer { if (back) rotationY = 180f },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(w.word, fontSize = 40.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
            if (back) {
                if (w.reading.isNotBlank()) Text(w.reading, color = c.primary, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
                Text(w.gloss.ifBlank { "（没有记下释义）" }, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                if (w.example.isNotBlank()) {
                    Text(w.example.trim(), color = c.onSurfaceVariantSummary, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
                }
            } else if (w.context.isNotBlank()) {
                Text(
                    highlighted(w.context, w.word),
                    fontFamily = FontFamily.Serif,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    color = c.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
            if (!back) Text("看释义", color = c.primary, fontSize = 15.sp, modifier = Modifier.padding(top = 28.dp))
        }
    }
}

/**
 * 生词本写成 cache/exports 下的 Markdown 文件，再用 FileProvider 分享：整份放进 EXTRA_TEXT，
 * 生词一多就超过跨进程传递的大小上限（约 1MB）而闪退。出错时返回提示，不抛出。
 */
private suspend fun shareVocabulary(context: Context, markdown: () -> String): String? = try {
    val file = withContext(Dispatchers.IO) {
        File(File(context.cacheDir, "exports").apply { mkdirs() }, "生词本.md").apply { writeText(markdown()) }
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "生词本")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "导出生词本").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    "导出失败：${error.message ?: error.javaClass.simpleName}"
}
