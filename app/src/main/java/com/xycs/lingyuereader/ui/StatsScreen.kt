package com.xycs.lingyuereader.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import com.xycs.lingyuereader.ui.glass.GlassCard
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.data.DayStats
import com.xycs.lingyuereader.data.ReadingStats
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** 阅读统计：今天、本周、本月、今年的时长，连续天数，阅读速度，近 20 周的热力图和各本书的时长。 */
@Composable
fun StatsScreen(vm: AppViewModel) {
    val days by vm.readingStats.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.reloadStats() }
    val today = LocalDate.now()

    GlassScaffold(
        topBar = {
            SmallTopAppBar(
                color = Color.Transparent,
                modifier = Modifier.glassBar(),
                title = "阅读统计",
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + LocalTabBarInset.current + 24.dp),
            overscrollEffect = null,
        ) {
            item {
                val week = ReadingStats.total(days, today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today)
                val month = ReadingStats.total(days, today.withDayOfMonth(1), today)
                val year = ReadingStats.total(days, today.withDayOfYear(1), today)
                GlassCard(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), insideMargin = PaddingValues(16.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Figure("今天", minutes(days[today]?.millis ?: 0))
                        Figure("本周", minutes(week))
                        Figure("本月", minutes(month))
                        Figure("今年", minutes(year))
                    }
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "连续阅读 ${ReadingStats.streak(days, today)} 天 · 阅读速度约每分钟 ${ReadingStats.charsPerMinute(days, today)} 字",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                SmallTitle("每日目标")
                GoalCard(vm, todayMinutes = ((days[today]?.millis ?: 0) / 60_000).toInt())
                SmallTitle("近 20 周")
                GlassCard(Modifier.padding(horizontal = 12.dp), insideMargin = PaddingValues(16.dp)) {
                    Heatmap(days, today, weeks = 20)
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("少", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        HEAT_LEVELS.indices.forEach { i ->
                            Box(Modifier.padding(horizontal = 2.dp).size(10.dp).clip(RoundedCornerShape(2.dp)).background(heatColor(i)))
                        }
                        Text("多", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                SmallTitle("各本书")
            }
            val books = shelf.filter { it.readMillis >= 60_000 }.sortedByDescending { it.readMillis }
            if (books.isEmpty()) {
                item {
                    Text(
                        "还没有阅读记录。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                }
            }
            val longest = books.firstOrNull()?.readMillis ?: 1
            items(books, key = { it.id }) { b ->
                Column(Modifier.padding(horizontal = 28.dp, vertical = 6.dp)) {
                    Row {
                        Text(b.title, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(durationLabel(b.readMillis), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
                    }
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MiuixTheme.colorScheme.secondaryContainer)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(b.readMillis.toFloat() / longest).background(MiuixTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

private fun minutes(millis: Long): String {
    val m = millis / 60_000
    return if (m < 60) "$m 分" else "%.1f 时".format(m / 60f)
}

/** 热力图的分档：分钟数下限。 */
private val HEAT_LEVELS = listOf(0L, 1L, 10L, 30L, 60L)

@Composable
private fun heatColor(level: Int): Color {
    val base = MiuixTheme.colorScheme.primary
    return if (level == 0) MiuixTheme.colorScheme.secondaryContainer else base.copy(alpha = 0.2f + 0.2f * level)
}

/** 每列一周（周一到周日），最右一列是本周。 */
@Composable
private fun Heatmap(days: Map<LocalDate, DayStats>, today: LocalDate, weeks: Int) {
    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
    val colors = HEAT_LEVELS.indices.map { heatColor(it) }
    val readDays = days.count { (d, s) -> !d.isBefore(start) && s.millis >= 60_000 }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(weeks / 7f)
            .semantics { contentDescription = "近 $weeks 周有 $readDays 天在读书" },
    ) {
        val cell = size.width / weeks
        val gap = cell * 0.18f
        for (w in 0 until weeks) {
            for (d in 0 until 7) {
                val day = start.plusDays((w * 7 + d).toLong())
                if (day.isAfter(today)) continue
                val minutes = (days[day]?.millis ?: 0) / 60_000
                val level = HEAT_LEVELS.indexOfLast { minutes >= it && (it > 0 || minutes == 0L) }.coerceAtLeast(0)
                drawRoundRect(
                    colors[level],
                    topLeft = Offset(w * cell + gap / 2, d * cell + gap / 2),
                    size = Size(cell - gap, cell - gap),
                    cornerRadius = CornerRadius(cell * 0.2f),
                )
            }
        }
    }
}

/** 每日目标：选几分钟，看今天读了多少；可以让它晚上 9 点还没读够时提醒一下。 */
@Composable
private fun GoalCard(vm: AppViewModel, todayMinutes: Int) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val goal = settings.dailyGoal
    val context = androidx.compose.ui.platform.LocalContext.current
    val askPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    fun ensureNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) askPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    GlassCard(Modifier.padding(horizontal = 12.dp), insideMargin = PaddingValues(16.dp)) {
        if (goal > 0) {
            val done = todayMinutes >= goal
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$todayMinutes", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(" / $goal 分钟", style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(bottom = 4.dp))
                Spacer(Modifier.weight(1f))
                Text(
                    if (done) "今天的目标完成了" else "还差 ${goal - todayMinutes} 分钟",
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (done) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MiuixTheme.colorScheme.secondaryContainer)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((todayMinutes.toFloat() / goal).coerceIn(0f, 1f)).background(MiuixTheme.colorScheme.primary))
            }
        } else {
            Text("设一个每天的阅读目标，统计页会显示今天读到哪了。", style = MiuixTheme.textStyles.body2)
        }
        Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 15, 30, 45, 60, 90).forEach { m ->
                val on = goal == m
                Text(
                    if (m == 0) "不设" else "$m 分钟",
                    fontSize = 14.sp,
                    color = if (on) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryContainer)
                        .clickable {
                            vm.updateSettings { it.copy(dailyGoal = m) }
                            if (m > 0 && settings.goalReminder) ensureNotifications()
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        if (goal > 0) {
            Row(Modifier.padding(top = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("晚上 ${com.xycs.lingyuereader.reminder.GoalReminder.HOUR} 点提醒", style = MiuixTheme.textStyles.body1)
                    Text(
                        "还没读够目标时发一条通知；连续读了好几天、今天还没读的，提醒别断了",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                top.yukonga.miuix.kmp.basic.Switch(
                    checked = settings.goalReminder,
                    onCheckedChange = { on ->
                        vm.updateSettings { it.copy(goalReminder = on) }
                        if (on) ensureNotifications()
                    },
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}
