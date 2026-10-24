package com.xycs.lingyuereader.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import com.xycs.lingyuereader.ui.glass.LocalGlassBackdrop
import com.xycs.lingyuereader.ui.glass.readerGlass
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.tts.ReadAloud
import com.xycs.lingyuereader.tts.ReadAloudEngine
import com.xycs.lingyuereader.tts.ReadAloudState
import com.xycs.lingyuereader.tts.SleepTimer
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text

/** 听书时浮在底部的控制条：播放/暂停、设置、停止。 */
@Composable
internal fun ReadAloudBar(state: ReadAloudState, palette: ReaderPalette, onSettings: () -> Unit) {
    Row(
        Modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 36.dp)
            .fillMaxWidth()
            .readerGlass(RoundedCornerShape(20.dp), palette, LocalGlassBackdrop.current)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = ReadAloud::toggle) {
            Icon(
                if (state.playing) LingYueIcons.Pause else LingYueIcons.Play,
                contentDescription = if (state.playing) "暂停听书" else "继续听书",
                tint = palette.accent,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onSettings)
                .semantics { contentDescription = "打开听书设置" }
                .padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                state.error ?: if (state.playing) "正在听书" else "已暂停",
                color = if (state.error != null) palette.accent else palette.text,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(state.chapterTitle, progressLabel(state), timerLabel(state.timer)).joinToString(" · "),
                color = palette.secondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onSettings) {
            Icon(LingYueIcons.Timer, contentDescription = "语速与定时", tint = palette.text)
        }
        IconButton(onClick = ReadAloud::stop) {
            Icon(LingYueIcons.Close, contentDescription = "停止听书", tint = palette.text)
        }
    }
}

/** “本章 35%”：按本章第几句估算。 */
internal fun progressLabel(state: ReadAloudState): String? =
    if (state.sentences <= 0) null else "本章 ${(state.sentence + 1) * 100 / state.sentences}%"

private fun timerLabel(timer: SleepTimer): String? = when (timer) {
    SleepTimer.Off -> null
    SleepTimer.EndOfChapter -> "本章读完停止"
    is SleepTimer.At -> {
        val left = ((timer.endsAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1
        "约 $left 分钟后停止"
    }
}

/**
 * 听书设置，苹果分组面板的样子：顶上是进度和大号的上一句 / 播放 / 下一句，
 * 下面是语速滑块、语音和定时关闭两个单选列表。
 */
@Composable
internal fun ReadAloudSheet(
    state: ReadAloudState,
    palette: ReaderPalette,
    onRate: (Float) -> Unit,
    onVoice: (String?) -> Unit = {},
    onTimer: (Int?) -> Unit = ReadAloud::setTimer,
    tint: Color = palette.accent,
    /** 能用的朗读引擎和说明（系统语音总在；离线语音装了模型才有；AI 语音设好了才有）。 */
    engines: List<Pair<ReadAloudEngine, String>> = emptyList(),
    onEngine: (ReadAloudEngine) -> Unit = {},
    /** 还没下载离线语音模型时，去下载的入口；为空不显示。 */
    onOfflineSetup: (() -> Unit)? = null,
) {
    ProvideReaderColors(palette, tint) {
        val c = LocalGroupedColors.current
        var rate by remember(state.bookId) { mutableStateOf(state.rate) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SheetNavBar("听书", back = null) {}
            Text(
                if (state.sentences > 0) "${state.chapterTitle} · 第 ${state.sentence + 1} / ${state.sentences} 句" else state.chapterTitle,
                color = c.secondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                textAlign = TextAlign.Center,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ReadAloud::previousSentence) {
                    Icon(LingYueIcons.ChevronBackward, contentDescription = "上一句", tint = c.label, modifier = Modifier.size(30.dp))
                }
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(c.tint)
                        .clickable(role = Role.Button, onClick = ReadAloud::toggle)
                        .semantics { contentDescription = if (state.playing) "暂停听书" else "继续听书" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (state.playing) LingYueIcons.Pause else LingYueIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = ReadAloud::nextSentence) {
                    Icon(LingYueIcons.ChevronForward, contentDescription = "下一句", tint = c.label, modifier = Modifier.size(30.dp))
                }
            }
            GroupedSection {
                row {
                    LabeledSliderRow(
                        "语速",
                        rate,
                        "%.1f×".format(rate),
                        0.5f..2f,
                        steps = 14,
                        onValueChangeFinished = { onRate(rate) },
                    ) { rate = it }
                }
            }
            state.error?.let {
                Text(it, color = c.destructive, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
            }
            if (engines.size > 1 || onOfflineSetup != null) {
                val engine = when {
                    state.offline -> ReadAloudEngine.OFFLINE
                    state.aiLabel != null -> ReadAloudEngine.ONLINE
                    else -> ReadAloudEngine.SYSTEM
                }
                GroupedSection(
                    header = "朗读引擎",
                    footer = when (engine) {
                        ReadAloudEngine.OFFLINE -> "在手机上合成，不联网、不花钱。每句先合成再读，较慢的手机句与句之间可能停顿。"
                        ReadAloudEngine.ONLINE -> "用「${state.aiLabel}」合成语音，按服务商的价格计费；读过的句子缓存在本机，重听、往回跳不再花钱。"
                        ReadAloudEngine.SYSTEM -> if (onOfflineSetup != null) "离线语音在手机上用 AI 模型合成，比系统语音自然，不联网；先下载一个模型（约 150 MB）。" else null
                    },
                ) {
                    engines.forEach { (e, label) -> row { CheckRow(label, e == engine) { if (e != engine) onEngine(e) } } }
                    onOfflineSetup?.let { row { NavRow("离线语音", value = "下载模型", onClick = it) } }
                }
            }
            if (state.voices.size > 1) {
                GroupedSection(header = if (state.aiLabel != null) "声音" else "语音") {
                    val options = if (state.aiLabel != null) state.voices.map { it.name to it.label } else listOf(null to "默认") + state.voices.map { it.name to it.label }
                    options.forEach { (name, label) ->
                        val on = name == state.voice || (name == null && state.voices.none { it.name == state.voice })
                        row { CheckRow(label, on) { onVoice(name) } }
                    }
                }
            }
            val selected = when (val t = state.timer) {
                SleepTimer.Off -> null
                SleepTimer.EndOfChapter -> 0
                is SleepTimer.At -> t.minutes
            }
            GroupedSection(
                header = "定时关闭",
                footer = listOfNotNull(
                    timerLabel(state.timer)?.let { "$it。" },
                    if (state.aiLabel != null) "锁屏后继续朗读，可在通知栏、耳机或蓝牙按键上暂停、切换上下句。"
                    else "使用系统的“文字转语音”引擎。锁屏后继续朗读，可在通知栏、耳机或蓝牙按键上暂停、切换上下句。",
                ).joinToString(""),
            ) {
                listOf<Pair<Int?, String>>(null to "不定时", 15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟", 0 to "本章结束").forEach { (minutes, label) ->
                    row { CheckRow(label, minutes == selected) { onTimer(minutes) } }
                }
            }
        }
    }
}
