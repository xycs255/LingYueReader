package com.xycs.lingyuereader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.Screen
import com.xycs.lingyuereader.data.SampleKind
import com.xycs.lingyuereader.data.SamplePick
import com.xycs.lingyuereader.data.StyleSamples
import com.xycs.lingyuereader.data.WorkAssist
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 挑范例：设置变了（换一段、不用这段、字数上限）就重挑；读不出时当成没有。 */
@Composable
private fun rememberStylePick(vm: AppViewModel, workId: String, assist: WorkAssist, loaded: Boolean): SamplePick? {
    val pick by produceState<SamplePick?>(null, workId, loaded, assist.sampleLimit, assist.sampleFallback, assist.sampleSkip, assist.sampleShift) {
        if (!loaded) return@produceState
        value = try {
            vm.writingAi.stylePick(workId, null, local = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SamplePick(emptyList())
        }
    }
    return pick
}

/** “高级设置”里“文风范例”一节的开关；带多少字、没有定稿章时退回、看看挑了哪几段紧跟在下面（[StyleSamplesMoreSection]）。 */
@Composable
internal fun StyleSamplesSection(vm: AppViewModel, workId: String, assist: WorkAssist) {
    GroupedSection(
        header = "文风范例",
        footer = "从你标了“定稿”的章里挑几段——一段叙述描写、一段对白多的、一段动作场面，每段 200–500 字——放进发给 AI 的内容里当范例。" +
            "AI 写、AI 写整章草稿、润色、更生动、扩写、缩写、按要求改时照着学句式、节奏、用词和对白方式，不照抄内容；挑错、写作建议、审稿不带。" +
            "写第 N 章时只从它前面的章里挑，不会剧透。本机模型只带三分之一。在本机挑，同样的定稿章挑出同样的几段（开头不变，服务商能缓存）。" +
            "最多带多少字、挑了哪几段在下面。",
    ) {
        row {
            ToggleRow("用定稿章当文风范例", assist.sampleLimit > 0) { on ->
                vm.writingAi.updateAssist(workId) { it.copy(sampleLimit = if (on) StyleSamples.DEFAULT_LIMIT else 0) }
            }
        }
    }
}

/** “高级设置”里的文风范例：最多带、没有定稿章时退回、看看挑了哪几段；开关关着时只说一句。紧跟在开关下面时不再写组名（[header] 传空）。 */
@Composable
internal fun StyleSamplesMoreSection(vm: AppViewModel, workId: String, assist: WorkAssist, header: String? = "文风范例") {
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    val pick = rememberStylePick(vm, workId, assist, assists[workId] != null && assist.sampleLimit > 0)
    GroupedSection(
        header = header,
        footer = if (assist.sampleLimit > 0) "从定稿章里挑几段当范例，最多带这么多字。" else "打开上面的“用定稿章当文风范例”才用得上。",
    ) {
        if (assist.sampleLimit <= 0) {
            row { EmptyRow("文风范例关着") }
            return@GroupedSection
        }
        row {
            PickerRow("最多带", StyleSamples.LIMITS.filter { it > 0 }.map { it to "$it 字" }, assist.sampleLimit) { v ->
                vm.writingAi.updateAssist(workId) { it.copy(sampleLimit = v) }
            }
        }
        row {
            ToggleRow("没有定稿章时用字数最多的章", assist.sampleFallback) { on ->
                vm.writingAi.updateAssist(workId) { it.copy(sampleFallback = on) }
            }
        }
        row {
            NavRow(
                "看看挑了哪几段",
                value = when {
                    pick == null -> "……"
                    pick.samples.isEmpty() -> "没有可用的"
                    else -> "${pick.samples.size} 段 · ${pick.chars} 字"
                },
            ) { vm.open(Screen.WorkStyleSamples(workId)) }
        }
    }
}

/**
 * 挑了哪几段：每段写出自哪章、哪一类、开头几十字（点开看全文），可以“换一段”或“不用这段”（记住，以后不再挑）。
 * 按写下一章时的样子挑（写前面的章时只从那章之前挑，可能少几段）。
 */
@Composable
fun WorkStyleSamplesScreen(vm: AppViewModel, workId: String) {
    val assists by vm.writingAi.assists.collectAsStateWithLifecycle()
    LaunchedEffect(workId) { vm.writingAi.loadAssist(workId) }
    val assist = assists[workId] ?: WorkAssist()
    val pick = rememberStylePick(vm, workId, assist, assists[workId] != null)
    SubPageScaffold("文风范例", onBack = { vm.back() }) { padding ->
        val c = LocalGroupedColors.current
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            overscrollEffect = null,
        ) {
            item(key = "body") {
                when {
                    pick == null -> LoadingState("正在挑……")
                    assist.sampleLimit <= 0 -> EmptyState(LingYueIcons.Notes, "文风范例关着", "在“写作 AI → 高级设置”里打开“用定稿章当文风范例”，AI 写的时候会照着你定稿的几段学写法。")
                    pick.samples.isEmpty() -> EmptyState(
                        LingYueIcons.Notes,
                        "还没有可用的段落",
                        if (assist.sampleFallback) "把写好的章标为“定稿”，或者先多写几章：每段要有 200 字以上的叙述、对白或动作场面才挑得出来。"
                        else "把写好的章标为“定稿”就会从里面挑；也可以打开“没有定稿章时用字数最多的章”。",
                    )
                    else -> Column {
                        val source = if (pick.fallback) "还没有定稿章，先从字数最多的 ${pick.sources} 章里挑。" else "从 ${pick.sources} 章定稿里挑。"
                        GroupedSection(
                            footer = source + "这里按写下一章时的样子列出；写前面的章时只从那章之前挑，可能少几段。" +
                                "共 ${pick.chars} 字，上限 ${assist.sampleLimit} 字。“不用这段”会记住，以后不再挑它。",
                        ) {
                            pick.samples.forEach { s ->
                                row {
                                    var open by remember(s.key) { mutableStateOf(false) }
                                    Column(
                                        Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Tag(s.kind.label, sampleColor(s.kind, c))
                                            Text(
                                                "出自「${s.chapterTitle}」 · ${s.text.length} 字",
                                                color = c.secondary,
                                                fontSize = TypeScale.Caption,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                                            )
                                        }
                                        Text(
                                            if (open) s.text else s.text.replace('\n', ' ').take(PREVIEW_CHARS) + if (s.text.length > PREVIEW_CHARS) "……" else "",
                                            color = c.label,
                                            fontSize = TypeScale.Body,
                                            lineHeight = TypeScale.BodyLine,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            PillButton("换一段", { vm.writingAi.shiftSample(workId, s.kind.name) })
                                            PillButton("不用这段", { vm.writingAi.skipSample(workId, s.key) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (assist.sampleSkip.isNotEmpty() || assist.sampleShift.isNotEmpty()) {
                    GroupedSection(footer = if (assist.sampleSkip.isEmpty()) null else "记住不用的有 ${assist.sampleSkip.size} 段。") {
                        row { ActionRow("恢复成自动挑的") { vm.writingAi.resetSamples(workId) } }
                    }
                }
                Text(
                    "范例只用来学写法，AI 被要求不照抄、不接着这些段落的情节写。“最近一次发给 AI 的内容”里能看到实际带上的范例。",
                    color = c.secondary,
                    fontSize = TypeScale.Caption,
                    lineHeight = TypeScale.CaptionLine,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
            }
        }
    }
}

/** 各类范例的标签色：叙述用强调色，对白用成功色，动作用提醒色（都是语义色，深色、Monet 下也读得清）。 */
private fun sampleColor(kind: SampleKind, c: GroupedColors) = when (kind) {
    SampleKind.NARRATION -> c.tint
    SampleKind.DIALOGUE -> c.success
    SampleKind.ACTION -> c.warning
}.readableOn(listOf(c.cell, c.background))

/** 列表里每段先显示多少字。 */
private const val PREVIEW_CHARS = 48
