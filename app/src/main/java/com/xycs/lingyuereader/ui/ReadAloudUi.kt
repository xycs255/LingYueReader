package com.xycs.lingyuereader.ui

import com.xycs.lingyuereader.ui.glass.LocalGlassBackdrop
import com.xycs.lingyuereader.ui.glass.readerGlass
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xycs.lingyuereader.tts.ReadAloud
import com.xycs.lingyuereader.tts.ReadAloudState
import com.xycs.lingyuereader.tts.SleepTimer
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
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
        Column(Modifier.weight(1f).clickable(onClick = onSettings).padding(horizontal = 6.dp)) {
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

/** 听书设置：进度与上下句、语速、语音、定时关闭。 */
@Composable
internal fun ReadAloudSheet(state: ReadAloudState, palette: ReaderPalette, onRate: (Float) -> Unit, onVoice: (String?) -> Unit = {}) {
    var rate by remember(state.bookId) { mutableStateOf(state.rate) }
    Column(Modifier.padding(bottom = 24.dp)) {
        Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ReadAloud::previousSentence) {
                Icon(LingYueIcons.ChevronBackward, contentDescription = "上一句", tint = palette.text)
            }
            IconButton(onClick = ReadAloud::toggle) {
                Icon(if (state.playing) LingYueIcons.Pause else LingYueIcons.Play, contentDescription = if (state.playing) "暂停听书" else "继续听书", tint = palette.accent)
            }
            IconButton(onClick = ReadAloud::nextSentence) {
                Icon(LingYueIcons.ChevronForward, contentDescription = "下一句", tint = palette.text)
            }
            Text(
                if (state.sentences > 0) "第 ${state.sentence + 1} / ${state.sentences} 句 · ${progressLabel(state)}" else state.chapterTitle,
                color = palette.secondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("语速", color = palette.text, fontSize = 14.sp, modifier = Modifier.width(48.dp))
            Box(Modifier.weight(1f)) {
                Slider(
                    value = rate,
                    onValueChange = { rate = it },
                    onValueChangeFinished = { onRate(rate) },
                    valueRange = 0.5f..2f,
                    steps = 14,
                    colors = SliderDefaults.sliderColors(foregroundColor = palette.accent, backgroundColor = palette.text.copy(alpha = 0.12f)),
                )
            }
            Text("%.1f×".format(rate), color = palette.text, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
        if (state.voices.size > 1) {
            Text("语音", color = palette.text, fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(null to "默认") + state.voices.map { it.name to it.label }).forEach { (name, label) ->
                    val on = name == state.voice || (name == null && state.voices.none { it.name == state.voice })
                    Text(
                        label,
                        color = if (on) palette.accent else palette.text,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) palette.accent.copy(alpha = 0.15f) else palette.text.copy(alpha = 0.06f))
                            .clickable { onVoice(name) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Text("定时关闭", color = palette.text, fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        val options = listOf<Pair<Int?, String>>(null to "不定时", 15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟", 0 to "本章结束")
        val selected = when (val t = state.timer) {
            SleepTimer.Off -> null
            SleepTimer.EndOfChapter -> 0
            is SleepTimer.At -> t.minutes
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (minutes, label) ->
                val on = minutes == selected
                Text(
                    label,
                    color = if (on) palette.accent else palette.text,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) palette.accent.copy(alpha = 0.15f) else palette.text.copy(alpha = 0.06f))
                        .clickable { ReadAloud.setTimer(minutes) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        timerLabel(state.timer)?.let { Text(it, color = palette.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
        Text(
            "使用系统的“文字转语音”引擎。锁屏后会继续朗读，可在通知栏、耳机或蓝牙按键上暂停、切换上下句。",
            color = palette.secondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}
