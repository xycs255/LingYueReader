package com.xycs.lingyuereader.ui

import androidx.compose.runtime.getValue
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Spacer
import com.xycs.lingyuereader.ui.glass.GlassScaffold
import com.xycs.lingyuereader.ui.glass.glassBar
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xycs.lingyuereader.AppViewModel
import com.xycs.lingyuereader.data.DayStats
import com.xycs.lingyuereader.data.ReadingStats
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 阅读统计：今天、本周、本月、今年的时长，连续天数，阅读速度，每日目标，近 20 周的热力图和各本书的时长。
 * 分组列表的样子，和“我的”一致。
 */
@Composable
fun StatsScreen(vm: AppViewModel) {
    val days by vm.readingStats.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.reloadStats() }
    val today = LocalDate.now()

    ProvideGroupedColors {
        val c = LocalGroupedColors.current
        val scrollBehavior = MiuixScrollBehavior()
        GlassScaffold(
            containerColor = c.background,
            topBar = {
                TopAppBar(
                    color = Color.Transparent,
                    modifier = Modifier.glassBar(),
                    title = "统计",
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + LocalTabBarInset.current + 24.dp),
                overscrollEffect = null,
            ) {
                item {
                    val week = ReadingStats.total(days, today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today)
                    val month = ReadingStats.total(days, today.withDayOfMonth(1), today)
                    val year = ReadingStats.total(days, today.withDayOfYear(1), today)
                    GroupedSection(footer = "连续阅读 ${ReadingStats.streak(days, today)} 天 · 阅读速度约每分钟 ${ReadingStats.charsPerMinute(days, today)} 字") {
                        row {
                            // 今天最要紧，写大；本周、本月、今年并排小一号。
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("今天", fontSize = 13.sp, color = c.secondary)
                                val todayShown = animateFromZero((days[today]?.millis ?: 0).toFloat())
                                Text(minutes(todayShown.toLong()), color = c.label, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                                Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                                Row(Modifier.fillMaxWidth()) {
                                    Figure("本周", week, Modifier.weight(1f))
                                    Figure("本月", month, Modifier.weight(1f))
                                    Figure("今年", year, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    GoalSection(vm, todayMinutes = ((days[today]?.millis ?: 0) / 60_000).toInt())
                    GroupedSection(header = "近 20 周") {
                        row {
                            Column(Modifier.padding(16.dp)) {
                                Heatmap(days, today, weeks = 20)
                                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("少", fontSize = 13.sp, color = c.secondary)
                                    HEAT_LEVELS.indices.forEach { i ->
                                        Box(Modifier.padding(horizontal = 2.dp).size(10.dp).clip(RoundedCornerShape(2.dp)).background(heatColor(i)))
                                    }
                                    Text("多", fontSize = 13.sp, color = c.secondary)
                                }
                            }
                        }
                    }
                    val books = remember(shelf) { shelf.filter { it.readMillis >= 60_000 }.sortedByDescending { it.readMillis } }
                    val longest = books.firstOrNull()?.readMillis ?: 1
                    // 书多时先列读得最久的几本（每本都带动画，一次全排出来会卡一下），点“查看全部”再展开。
                    var showAll by rememberSaveable { mutableStateOf(false) }
                    // 点“查看全部”“收起”时多出来、去掉的几行由卡片的高度动画平滑长高、缩短（GroupedSection 自带）。
                    GroupedSection(header = "各本书") {
                        if (books.isEmpty()) {
                            row { EmptyRow("还没有阅读记录。一本书读满 1 分钟，就会列在这里。") }
                        }
                        (if (showAll) books else books.take(TOP_BOOKS)).forEach { b ->
                            row {
                                // 书名、时长和进度条一起呈现，行高按内容自然展开。
                                Column(Modifier.fillMaxWidth().testTag("stats-book-${b.id}").heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(b.title, color = c.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(durationLabel(b.readMillis), color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
                                    }
                                    val grown = animateFromZero(b.readMillis.toFloat() / longest)
                                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.fill)) {
                                        Box(Modifier.fillMaxHeight().fillMaxWidth(grown.coerceIn(0f, 1f)).clip(RoundedCornerShape(3.dp)).background(c.tint))
                                    }
                                }
                            }
                        }
                        if (books.size > TOP_BOOKS) {
                            row { ActionRow(if (showAll) "收起" else "查看全部 ${books.size} 本") { showAll = !showAll } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Figure(label: String, millis: Long, modifier: Modifier = Modifier) {
    // 打开统计页时数字从 0 往上数。
    val shown = animateFromZero(millis.toFloat())
    val c = LocalGroupedColors.current
    Column(modifier) {
        Text(minutes(shown.toLong()), color = c.label, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(label, fontSize = 13.sp, color = c.secondary, modifier = Modifier.padding(top = 2.dp))
    }
}

/** “各本书”先列几本。 */
private const val TOP_BOOKS = 10

private fun minutes(millis: Long): String {
    val m = millis / 60_000
    return if (m < 60) "$m 分" else "%.1f 时".format(m / 60f)
}

/** 热力图的分档：分钟数下限。 */
private val HEAT_LEVELS = listOf(0L, 1L, 10L, 30L, 60L)

@Composable
private fun heatColor(level: Int): Color {
    val c = LocalGroupedColors.current
    return if (level == 0) c.fill else c.tint.copy(alpha = 0.2f + 0.2f * level)
}

/** 每列一周（周一到周日），最右一列是本周。 */
@Composable
private fun Heatmap(days: Map<LocalDate, DayStats>, today: LocalDate, weeks: Int) {
    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
    val colors = HEAT_LEVELS.indices.map { heatColor(it) }
    val readDays = days.count { (d, s) -> !d.isBefore(start) && s.millis >= 60_000 }
    // 从左往右一列列亮起来。
    val reveal = remember { Animatable(if (Effects.noMotion) 1f else 0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, Motion.standard(Motion.COUNT_UP)) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(weeks / 7f)
            .semantics { contentDescription = "近 $weeks 周有 $readDays 天在读书" },
    ) {
        val cell = size.width / weeks
        val gap = cell * 0.18f
        val front = reveal.value * (weeks + 4)
        for (w in 0 until weeks) {
            val t = (front - w).coerceIn(0f, 4f) / 4f
            if (t <= 0f) continue
            for (d in 0 until 7) {
                val day = start.plusDays((w * 7 + d).toLong())
                if (day.isAfter(today)) continue
                val minutes = (days[day]?.millis ?: 0) / 60_000
                val level = HEAT_LEVELS.indexOfLast { minutes >= it && (it > 0 || minutes == 0L) }.coerceAtLeast(0)
                val side = (cell - gap) * (0.6f + 0.4f * t)
                val inset = (cell - side) / 2
                drawRoundRect(
                    colors[level],
                    topLeft = Offset(w * cell + inset, d * cell + inset),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(side * 0.2f),
                    alpha = t,
                )
            }
        }
    }
}

/** 每日目标：选几分钟，看今天读了多少；可以让它晚上 9 点还没读够时提醒一下。 */
@Composable
private fun GoalSection(vm: AppViewModel, todayMinutes: Int) {
    val c = LocalGroupedColors.current
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
    GroupedSection(
        header = "每日目标",
        footer = if (goal > 0) {
            "还没读够目标时，晚上 ${com.xycs.lingyuereader.reminder.GoalReminder.HOUR} 点发一条通知；连续读了好几天、今天还没读的，提醒别断了。"
        } else {
            "设一个每天的阅读目标，这里会显示今天读到哪了。"
        },
    ) {
        if (goal > 0) {
            row {
                val done = todayMinutes >= goal
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${animateFromZero(todayMinutes.toFloat()).toInt()}", color = c.label, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text(" / $goal 分钟", color = c.secondary, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp))
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (done) "今天的目标完成了" else "还差 ${goal - todayMinutes} 分钟",
                            fontSize = 13.sp,
                            color = if (done) c.tint else c.secondary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.fill)) {
                        val filled = animateFromZero((todayMinutes.toFloat() / goal).coerceIn(0f, 1f))
                        Box(Modifier.fillMaxHeight().fillMaxWidth(filled.coerceIn(0f, 1f)).clip(RoundedCornerShape(4.dp)).background(c.tint))
                    }
                }
            }
        }
        row {
            PickerRow("每天读", listOf(0, 15, 30, 45, 60, 90).map { it to if (it == 0) "不设目标" else "$it 分钟" }, goal) { m ->
                vm.updateSettings { it.copy(dailyGoal = m) }
                if (m > 0 && settings.goalReminder) ensureNotifications()
            }
        }
        if (goal > 0) {
            row {
                ToggleRow("晚上 ${com.xycs.lingyuereader.reminder.GoalReminder.HOUR} 点提醒", settings.goalReminder) { on ->
                    vm.updateSettings { it.copy(goalReminder = on) }
                    if (on) ensureNotifications()
                }
            }
        }
    }
}
