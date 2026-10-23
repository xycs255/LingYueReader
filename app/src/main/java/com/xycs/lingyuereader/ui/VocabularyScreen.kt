package com.xycs.lingyuereader.ui

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
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 生词本：查词时收进来的词，分“学习中”和“已掌握”，可以翻卡片复习、导出。 */
@Composable
fun VocabularyScreen(vm: AppViewModel) {
    val words by vm.vocab.collectAsStateWithLifecycle()
    var mastered by rememberSaveable { mutableStateOf(false) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.reloadVocabulary() }
    val learning = words.filterNot { it.mastered }
    // 到期的词：按遗忘曲线排到今天或更早的。
    val now = remember(words) { System.currentTimeMillis() }
    val due = learning.filter { it.isDue(now) }.sortedWith(compareBy({ it.due }, { it.addedAt }))
    val shown = words.filter { it.mastered == mastered }

    GlassScaffold(
        topBar = {
            SmallTopAppBar(
                color = Color.Transparent,
                modifier = Modifier.glassBar(),
                title = if (reviewing) "复习生词" else "生词本",
                navigationIcon = {
                    if (reviewing) {
                        IconButton(onClick = { reviewing = false }, modifier = Modifier.padding(start = 12.dp)) {
                            Icon(MiuixIcons.Back, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (!reviewing && words.isNotEmpty()) {
                        IconButton(
                            onClick = { shareText(context, "生词本", vm.vocabularyMarkdown(words)) },
                            modifier = Modifier.padding(end = 12.dp),
                        ) {
                            Icon(MiuixIcons.Share, contentDescription = "导出生词本")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (reviewing) {
            Review(due, Modifier.padding(padding).padding(bottom = LocalTabBarInset.current), onAnswer = { w, known -> vm.reviewWord(w.word, known) }, onDone = { reviewing = false })
            return@GlassScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + LocalTabBarInset.current + 24.dp),
            overscrollEffect = null,
        ) {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tab("学习中 ${learning.size}", !mastered) { mastered = false }
                    Tab("已掌握 ${words.size - learning.size}", mastered) { mastered = true }
                    Spacer(Modifier.weight(1f))
                    if (due.isNotEmpty()) {
                        TextButton("复习 ${due.size}", onClick = { reviewing = true }, colors = ButtonDefaults.textButtonColorsPrimary())
                    } else if (learning.isNotEmpty()) {
                        val next = learning.minOf { it.due }
                        Text(
                            "下次复习：${dueLabel(next, now)}",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (words.isEmpty()) "还没有生词。读书时长按选词，在查词面板里点“☆ 收入生词本”。" else "这里还没有词。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                    )
                }
            }
            items(shown, key = { it.word }) { w ->
                WordCard(
                    w,
                    // 标为掌握、删除、切换标签时，其余的词滑到新位置。
                    modifier = Modifier.animateItem(Motion.listItem(), Motion.listItem(), Motion.listItem()),
                    onToggleMastered = { vm.setMastered(w.word, !w.mastered) },
                    onRemove = { vm.removeFromVocabulary(w.word) },
                )
            }
        }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MiuixTheme.colorScheme.secondaryContainer else MiuixTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

@Composable
private fun WordCard(w: VocabWord, modifier: Modifier = Modifier, onToggleMastered: () -> Unit, onRemove: () -> Unit) {
    GlassCard(modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(w.word, fontSize = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
            if (w.reading.isNotBlank()) {
                Text(w.reading, color = MiuixTheme.colorScheme.primary, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp, bottom = 3.dp))
            }
            Spacer(Modifier.weight(1f))
            if (w.lookups > 1) Text("查过 ${w.lookups} 次", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (w.gloss.isNotBlank()) Text(w.gloss, style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(top = 6.dp))
        if (w.context.isNotBlank()) {
            Text(
                highlighted(w.context, w.word),
                style = MiuixTheme.textStyles.body2,
                fontFamily = FontFamily.Serif,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(
                    w.bookTitle.takeIf { it.isNotBlank() }?.let { "《$it》" },
                    dateFormat.format(Date(w.addedAt)),
                    if (w.mastered) null else "下次复习 " + dueLabel(w.due, System.currentTimeMillis()),
                ).joinToString(" · "),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            TextButton(if (w.mastered) "重新学习" else "已掌握", onClick = onToggleMastered)
            TextButton("删除", onClick = onRemove, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

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
        Text("${index + 1} / ${deck.size}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(40.dp))
        Text(w.word, fontSize = 40.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
        if (w.context.isNotBlank()) {
            Text(
                highlighted(w.context, w.word),
                fontFamily = FontFamily.Serif,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 28.dp), contentAlignment = Alignment.TopCenter) {
            if (revealed) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (w.reading.isNotBlank()) Text(w.reading, color = MiuixTheme.colorScheme.primary, fontSize = 18.sp)
                    Text(w.gloss.ifBlank { "（没有记下释义）" }, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                }
            } else {
                TextButton("看释义", onClick = { revealed = true })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                "还不熟",
                onClick = {
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

private fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "导出$subject").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
