package com.xycs.lingyuereader.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * 护眼的几件事，依据见 README“护眼”一节：
 * - 屏幕与环境的亮度落差越大越累（Benedetto 等 2014；晚间 21–42 cd/m² 配 25–50 lx 最舒适，2021），
 *   所以按环境光压暗背景、很暗时换夜间；
 * - 日落后偏暖只为柔和、少影响睡眠，滤蓝光对眼疲劳并无证据（Cochrane 2023），不宣称“防蓝光”；
 * - 20-20-20 休息提醒能减轻数字眼疲劳和干眼症状（Talens-Estarelles 等 2023）。
 * 背景本身不做持续的动画：余光里的运动只会分散注意力。
 */

/** 环境亮度档位，从暗到亮排列。 */
enum class LightLevel(val label: String) { DARK("很暗"), DIM("昏暗"), NORMAL("正常") }

/**
 * 由照度（lx）定档，带宽回差：进出同一档的门槛相差好几倍，读数在门槛附近抖动时不会来回切换。
 * 很暗：< 3 lx（升到 15 lx 以上才离开）；昏暗：< 25 lx（升到 80 lx 以上才离开）。
 */
fun nextLightLevel(current: LightLevel?, lux: Float): LightLevel {
    fun fresh() = when {
        lux < 3f -> LightLevel.DARK
        lux < 25f -> LightLevel.DIM
        else -> LightLevel.NORMAL
    }
    return when (current) {
        null -> fresh()
        LightLevel.DARK -> if (lux > 15f) (if (lux > 80f) LightLevel.NORMAL else LightLevel.DIM) else LightLevel.DARK
        LightLevel.DIM -> when {
            lux < 3f -> LightLevel.DARK
            lux > 80f -> LightLevel.NORMAL
            else -> LightLevel.DIM
        }
        LightLevel.NORMAL -> when {
            lux < 3f -> LightLevel.DARK
            lux < 25f -> LightLevel.DIM
            else -> LightLevel.NORMAL
        }
    }
}

/**
 * 把光线传感器的原始读数变成稳定的档位。
 *
 * 传感器只在读数变化时上报，而且很跳：手指挡一下、手机一歪、灯管闪烁都会让读数骤变；
 * 屏下传感器还会读到屏幕自己的光，背景一变读数跟着变。所以：
 * - 按时间做指数平滑（时间常数 [tauMs]），不按事件个数，需要定时用最近一次读数 [update]；
 * - 新档位要持续一段时间才生效：变暗要 [darkerDwellMs]，变亮快一些（[brighterDwellMs]），开灯后尽快恢复；
 * - 每次换档后至少保持 [minHoldMs]，杜绝来回切换。
 */
class AmbientLightFilter(
    private val tauMs: Long = 5_000,
    private val darkerDwellMs: Long = 10_000,
    private val brighterDwellMs: Long = 3_000,
    private val minHoldMs: Long = 30_000,
) {
    /** 平滑后的照度；还没有读数时为负。 */
    var lux = -1f
        private set
    var level: LightLevel? = null
        private set
    private var lastTime = 0L
    private var candidate: LightLevel? = null
    private var candidateSince = 0L
    private var changedAt = 0L

    fun update(rawLux: Float, now: Long): LightLevel {
        val raw = rawLux.coerceAtLeast(0f)
        if (lux < 0) {
            lux = raw
        } else {
            val dt = (now - lastTime).coerceAtLeast(0)
            lux += (raw - lux) * (1f - exp(-dt.toDouble() / tauMs).toFloat())
        }
        lastTime = now
        val current = level
        val target = nextLightLevel(current, lux)
        if (current == null) {
            level = target
            changedAt = now
            return target
        }
        if (target == current) {
            candidate = null
            return current
        }
        if (target != candidate) {
            candidate = target
            candidateSince = now
        }
        val dwell = if (target.ordinal < current.ordinal) darkerDwellMs else brighterDwellMs
        if (now - candidateSince >= dwell && now - changedAt >= minHoldMs) {
            level = target
            changedAt = now
            candidate = null
        }
        return level!!
    }
}

/** 环境光的当前状态：给阅读页定配色，也在设置里显示读数，方便核对。 */
class AmbientLight {
    /** 有没有光线传感器（且功能开着）。 */
    var available by mutableStateOf(false)
        internal set
    var level by mutableStateOf<LightLevel?>(null)
        internal set
    /** 平滑后的照度，取整，只在变化时更新。 */
    var lux by mutableIntStateOf(-1)
        internal set
}

val LocalAmbientLight = staticCompositionLocalOf<AmbientLight?> { null }

/** 读光线传感器，经 [AmbientLightFilter] 得到稳定的档位。关闭或没有传感器时 level 为 null。 */
@Composable
fun rememberAmbientLight(enabled: Boolean): AmbientLight {
    val context = LocalContext.current
    val state = remember { AmbientLight() }
    val manager = remember { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val sensor = remember(manager) { manager?.getDefaultSensor(Sensor.TYPE_LIGHT) }
    var raw by remember { mutableFloatStateOf(-1f) }
    DisposableEffect(enabled, sensor) {
        if (!enabled || manager == null || sensor == null) {
            state.available = false
            state.level = null
            state.lux = -1
            return@DisposableEffect onDispose { }
        }
        state.available = true
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                event.values.firstOrNull()?.let { raw = it }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose {
            manager.unregisterListener(listener)
            raw = -1f
        }
    }
    // 传感器只在变化时上报，平滑按时间算，所以每半秒用最近一次读数推进一次。
    LaunchedEffect(enabled, sensor) {
        if (!enabled || sensor == null) return@LaunchedEffect
        val filter = AmbientLightFilter()
        while (true) {
            if (raw >= 0) {
                state.level = filter.update(raw, SystemClock.elapsedRealtime())
                val rounded = filter.lux.roundToInt()
                if (rounded != state.lux) state.lux = rounded
            }
            delay(500)
        }
    }
    return state
}

/** 一天中 [minuteOfDay] 时的偏暖程度：18:30 起渐暖，21:00 到顶，清晨 5:00–6:30 退回。 */
fun eveningWarmth(minuteOfDay: Int): Float {
    val m = minuteOfDay.coerceIn(0, 24 * 60 - 1)
    return when {
        m < 5 * 60 -> 1f
        m < 6 * 60 + 30 -> 1f - (m - 5 * 60) / 90f
        m < 18 * 60 + 30 -> 0f
        m < 21 * 60 -> (m - (18 * 60 + 30)) / 150f
        else -> 1f
    }
}

/** 当前时刻的偏暖程度，每分钟更新一次；关闭时为 0。 */
@Composable
fun rememberEveningWarmth(enabled: Boolean): Float {
    var warmth by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled) {
        while (enabled) {
            val c = Calendar.getInstance()
            warmth = eveningWarmth(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
            delay(60_000)
        }
        warmth = 0f
    }
    return if (enabled) warmth else 0f
}

/**
 * 配色变化时渐变过去，不突然一闪：环境光、日落变暖这类自己发生的变化慢慢来（[Motion.PALETTE]，约 3 秒）；
 * 读者在外观里亲手换配色（[choice] 变了）就快些（[Motion.MEDIUM]），点了马上看到。
 */
@Composable
fun animatePalette(target: ReaderPalette, choice: Any? = null): ReaderPalette {
    if (Effects.noMotion) return target
    var lastChoice by remember { mutableStateOf(choice) }
    val chosen = choice != lastChoice
    SideEffect { lastChoice = choice }
    val spec = tween<Color>(if (chosen) Motion.MEDIUM else Motion.PALETTE, easing = Motion.Standard)
    val background by animateColorAsState(target.background, spec, label = "background")
    val text by animateColorAsState(target.text, spec, label = "text")
    val secondary by animateColorAsState(target.secondary, spec, label = "secondary")
    val accent by animateColorAsState(target.accent, spec, label = "accent")
    val bar by animateColorAsState(target.bar, spec, label = "bar")
    return ReaderPalette(background, text, secondary, accent, bar, target.isDark)
}

/**
 * 远眺提醒：连续阅读满 [intervalMs]，画面淡出成一幅远景，请读者看向远处 [restMs]，
 * 倒计时结束自动淡回。听书时不打断；离开阅读页超过 5 分钟回来重新计时。
 */
@Composable
fun BreakReminder(
    enabled: Boolean,
    paused: Boolean,
    palette: ReaderPalette,
    intervalMs: Long = 20 * 60_000L,
    restMs: Long = 20_000L,
) {
    if (!enabled) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val isPaused by rememberUpdatedState(paused)
    var showing by remember { mutableStateOf(false) }
    // 只在协程里读写，不触发重组。
    val clock = remember { object { var elapsed = 0L; var stoppedAt = 0L } }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> clock.stoppedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    if (clock.stoppedAt > 0 && SystemClock.elapsedRealtime() - clock.stoppedAt >= 5 * 60_000L) clock.elapsed = 0
                    clock.stoppedAt = 0
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(1_000)
            if (showing || isPaused || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
            clock.elapsed += 1_000
            if (clock.elapsed >= intervalMs) showing = true
        }
    }
    AnimatedVisibility(
        visible = showing,
        enter = Effects.enter(fadeIn(tween(Motion.REST_FADE_IN))),
        exit = Effects.exit(fadeOut(tween(Motion.REST_FADE_OUT))),
    ) {
        RestScene(
            night = palette.isDark,
            restMs = restMs,
            onDone = {
                clock.elapsed = 0
                showing = false
            },
            onSnooze = {
                clock.elapsed = intervalMs - 5 * 60_000L
                showing = false
            },
        )
    }
}

@Composable
private fun RestScene(night: Boolean, restMs: Long, onDone: () -> Unit, onSnooze: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val noMotion = Effects.noMotion
    var remainingSeconds by remember(restMs) { mutableIntStateOf(ceil(restMs / 1000f).toInt()) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(restMs, noMotion) {
        if (noMotion) {
            // 倒计时仍每秒更新，但圆环不做连续动画。
            var elapsed = 0L
            while (elapsed < restMs) {
                val step = minOf(1_000L, restMs - elapsed)
                delay(step)
                elapsed += step
                remainingSeconds = ceil((restMs - elapsed) / 1000f).toInt()
            }
        } else {
            progress.animateTo(1f, tween(restMs.toInt(), easing = LinearEasing))
        }
        done()
    }
    val ink = if (night) Color(0xFFE6DCC4) else Color(0xFF2A2F33)
    Box(
        Modifier
            .fillMaxSize()
            // 吃掉所有触摸，休息时不误翻页。
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
    ) {
        Landscape(night, Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxWidth().padding(top = 96.dp, start = 32.dp, end = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("看看远处", color = ink, fontSize = TypeScale.Display, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
            Text(
                "把目光投向 6 米外的窗外或远处，眨眨眼，放松 20 秒。",
                color = ink.copy(alpha = 0.8f),
                fontSize = TypeScale.Body,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            val left = if (noMotion) remainingSeconds else ceil((1f - progress.value) * restMs / 1000f).toInt()
            Box(Modifier.padding(top = 28.dp).size(84.dp).semantics { contentDescription = "还剩 $left 秒" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(ink.copy(alpha = 0.15f), 0f, 360f, false, style = stroke)
                    if (!noMotion) drawArc(ink.copy(alpha = 0.7f), -90f, 360f * (1f - progress.value), false, style = stroke)
                }
                Text("$left", color = ink, fontSize = TypeScale.Title)
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SceneButton("5 分钟后", onSnooze)
            SceneButton("跳过", onDone)
        }
    }
}

@Composable
private fun SceneButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = TypeScale.Body,
        modifier = Modifier
            .clip(Radius.Full)
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    )
}

/** 一幅静止的远景：天空渐变、太阳或月亮、由远及近三层山。远处的山更淡（空气透视），引导目光看远。 */
@Composable
private fun Landscape(night: Boolean, modifier: Modifier) {
    val sky = if (night) listOf(Color(0xFF141B26), Color(0xFF2B2C36)) else listOf(Color(0xFFBCD4E0), Color(0xFFF0EADB))
    val orb = if (night) Color(0xFFE8E2CC) else Color(0xFFF7E6B8)
    val hills = if (night) {
        listOf(Color(0xFF2E3542), Color(0xFF242A34), Color(0xFF1B2028))
    } else {
        listOf(Color(0xFFA7BCC2), Color(0xFF86A096), Color(0xFF6A8573))
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(sky))
        drawCircle(orb.copy(alpha = if (night) 0.75f else 0.9f), radius = w * 0.07f, center = Offset(w * 0.72f, h * 0.46f))
        val layers = listOf(
            Triple(0.58f, 0.07f, 1.2f),
            Triple(0.69f, 0.05f, 1.7f),
            Triple(0.81f, 0.035f, 2.2f),
        )
        layers.forEachIndexed { i, (base, amp, freq) ->
            val path = Path().apply {
                moveTo(0f, h)
                val steps = 160
                for (s in 0..steps) {
                    val x = w * s / steps
                    val t = x / w
                    val y = h * (base - amp * (0.6f * sin(2 * PI * (t * freq + i * 0.37)).toFloat() +
                        0.4f * sin(2 * PI * (t * freq * 2.3 + i * 0.71)).toFloat()))
                    lineTo(x, y)
                }
                lineTo(w, h)
                close()
            }
            drawPath(path, hills[i])
        }
    }
}
