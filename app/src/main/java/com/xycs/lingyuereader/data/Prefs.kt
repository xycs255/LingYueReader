package com.xycs.lingyuereader.data

import android.content.Context
import androidx.core.content.edit

/** 阅读时的屏幕方向。 */
enum class ReadingOrientation(val label: String) {
    SYSTEM("跟随系统"),
    PORTRAIT("竖屏"),
    LANDSCAPE("横屏"),
}

enum class ReaderTheme(val label: String) {
    PAPER("纸白"),
    PARCHMENT("宣黄"),
    GREEN("护眼"),
    NIGHT("夜间"),
    CUSTOM("自定义"),
}

enum class PageMode(val label: String) {
    SCROLL("滚动"),
    SLIDE("平移"),
    COVER("覆盖"),
    CURL("仿真"),
    VERTICAL("竖排"),
}

/** 点正文某处做什么。 */
enum class TapAction(val label: String) {
    PREV("上一页"),
    NEXT("下一页"),
    MENU("菜单"),
    NONE("不响应"),
}

/** 点击区域：把正文分成 3×3 九格，按行从上到下、每行从左到右。 */
object TapZones {
    /** 左边上一页、右边下一页、中间菜单。 */
    val DEFAULT: List<TapAction> = List(3) { listOf(TapAction.PREV, TapAction.MENU, TapAction.NEXT) }.flatten()
    /** 单手：中间菜单，其余都是下一页。 */
    val ONE_HAND: List<TapAction> = List(9) { if (it == 4) TapAction.MENU else TapAction.NEXT }

    /** [x]、[y] 是点在正文里的相对位置（0..1）。 */
    fun indexOf(x: Float, y: Float): Int {
        val col = (x.coerceIn(0f, 0.999f) * 3).toInt()
        val row = (y.coerceIn(0f, 0.999f) * 3).toInt()
        return row * 3 + col
    }

    fun encode(zones: List<TapAction>): String = zones.joinToString(",") { it.name }

    fun decode(s: String?): List<TapAction> =
        s?.split(",")?.mapNotNull { n -> TapAction.entries.firstOrNull { it.name == n } }?.takeIf { it.size == 9 } ?: DEFAULT
}

data class ReaderSettings(
    val fontSize: Float = 19f,
    val lineHeight: Float = 1.8f,
    val paragraphSpacing: Float = 0.6f,
    val serif: Boolean = true,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val keepScreenOn: Boolean = true,
    val tapToTurn: Boolean = true,
    val pageMode: PageMode = PageMode.SCROLL,
    val volumeKeys: Boolean = true,
    /** 0..1；为负表示跟随系统亮度。 */
    val brightness: Float = -1f,
    val fullscreen: Boolean = false,
    /** 系统处于深色模式时自动改用夜间背景。 */
    val autoNight: Boolean = true,
    /** 听书语速，1 为正常。 */
    val ttsRate: Float = 1f,
    /** 按光线传感器测到的环境亮度调整背景：昏暗时压暗背景，很暗时换夜间。 */
    val ambientLight: Boolean = true,
    /** 日落后背景和文字逐渐偏暖。 */
    val eveningWarmth: Boolean = true,
    /** 每连续阅读 20 分钟，提醒看远处 20 秒（20-20-20）。 */
    val breakReminder: Boolean = true,
    /** 正文段首空两格。 */
    val indent: Boolean = true,
    /** 正文两端对齐。 */
    val justify: Boolean = true,
    /** 阅读时繁简转换。 */
    val chineseConvert: ChineseConvert = ChineseConvert.NONE,
    /** 导入的字体文件名；为空时按 [serif] 用内置宋体或黑体。 */
    val font: String = "",
    /** 自动滚动的速度（每分钟几行）；为 0 时按个人阅读速度估算。 */
    val autoScrollLines: Float = 0f,
    /** 听书选用的语音名；为空用引擎默认。 */
    val ttsVoice: String = "",
    /** 墨水屏 / 省电模式：关掉玻璃、动画和环境光变色，书页纯黑白。 */
    val eInk: Boolean = false,
    /** 每天的阅读目标（分钟）；0 表示不设。 */
    val dailyGoal: Int = 0,
    /** 晚上还没读够目标时提醒。 */
    val goalReminder: Boolean = true,
    /** 注音：在汉字上方标拼音。 */
    val pinyin: Boolean = false,
    /** 横屏且屏幕够宽时一次显示两页。 */
    val twoPage: Boolean = true,
    /** 正文左右留白（dp）。 */
    val margin: Float = 24f,
    /** 字间距（em）。 */
    val letterSpacing: Float = 0.03f,
    /** 自定义背景的底色和字色（ARGB）。 */
    val customBackground: Int = 0xFFF4F2EC.toInt(),
    val customText: Int = 0xFF2A2724.toInt(),
    /** 九格点击区域各做什么（见 [TapZones]）。 */
    val tapZones: List<TapAction> = TapZones.DEFAULT,
    /** 阅读时锁定横屏或竖屏（仍可上下或左右颠倒）。 */
    val orientation: ReadingOrientation = ReadingOrientation.SYSTEM,
    /** 翻页时在屏幕左右边缘画出读过 / 没读的“书页厚度”，像拿着纸书一样知道读到哪了。 */
    val pageEdges: Boolean = true,
)

/** 界面的深浅色。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/** 换页面时的动画。 */
enum class PageTransition(val label: String) {
    SLIDE("层级滑动"),
    FADE("淡入淡出"),
    NONE("无"),
}

/** 由强调色生成配色时的风格（对应 miuix 的 ThemePaletteStyle）。 */
enum class PaletteStyle(val label: String) {
    TONAL_SPOT("柔和"),
    VIBRANT("鲜艳"),
    EXPRESSIVE("活泼"),
    NEUTRAL("素雅"),
    MONOCHROME("黑白"),
}

/** 界面外观（阅读页的书页配色另在 [ReaderSettings] 里）。 */
data class Appearance(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** Monet 取色：Android 12 起从壁纸取色。 */
    val monet: Boolean = false,
    /** 强调色（ARGB）；0 为默认（开着 Monet 时取壁纸色，否则用 miuix 的蓝）。 */
    val accent: Int = 0,
    val paletteStyle: PaletteStyle = PaletteStyle.TONAL_SPOT,
    /** 顶栏、面板、导航栏的毛玻璃模糊。 */
    val blur: Boolean = true,
    /** 液态玻璃：边缘折射和高光。 */
    val liquidGlass: Boolean = true,
    /** 底部导航栏悬浮在内容上（关掉则贴底）。 */
    val floatingBar: Boolean = true,
    val transition: PageTransition = PageTransition.SLIDE,
    /** 界面缩放，1 为原大小。 */
    val scale: Float = 1f,
) {
    /** 用 Monet 生成配色：开了 Monet，或选了强调色。 */
    val dynamicColors: Boolean get() = monet || accent != 0

    companion object {
        /** 界面缩放的几档。 */
        val SCALES = listOf(0.85f, 0.9f, 0.95f, 1f, 1.05f, 1.1f, 1.15f)

        /** 可选的强调色：朱砂、琥珀、竹青、黛蓝、藕荷、桃红、松石、墨。 */
        val ACCENTS = listOf(
            0xFFA23B2A, 0xFFD49A57, 0xFF3E6B2E, 0xFF2F5D8A, 0xFF8E6C8A, 0xFFD9667A, 0xFF2A8C82, 0xFF4A4A4A,
        ).map { it.toInt() }
    }
}

enum class ShelfSort(val label: String) {
    RECENT("最近阅读"),
    TITLE("书名"),
    ADDED("添加时间"),
}

data class ShelfPrefs(
    val sort: ShelfSort = ShelfSort.RECENT,
    val list: Boolean = false,
    /** 分组的先后顺序（书上出现、这里没有的分组排在后面）。 */
    val groups: List<String> = emptyList(),
    /** 正在看的分组；为空是全部，[UNGROUPED] 是未分组。 */
    val group: String? = null,
) {
    companion object {
        const val UNGROUPED = "\u0000未分组"
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    private companion object {
        // 备份可以来自旧版本或另一台设备；只导入当前版本认识且类型正确的键。
        val FLOAT_KEYS = setOf(
            "fontSize", "lineHeight", "paragraphSpacing", "brightness", "ttsRate", "autoScrollLines",
            "margin", "letterSpacing", "ui.scale",
        )
        val BOOLEAN_KEYS = setOf(
            "serif", "keepScreenOn", "tapToTurn", "volumeKeys", "fullscreen", "autoNight",
            "ambientLight", "eveningWarmth", "breakReminder", "indent", "justify", "eInk",
            "goalReminder", "pinyin", "twoPage", "pageEdges", "ui.monet", "ui.blur",
            "ui.liquidGlass", "ui.floatingBar", "shelf.list",
        )
        val INT_KEYS = setOf("dailyGoal", "customBackground", "customText", "ui.accent")
        val STRING_KEYS = setOf(
            "theme", "pageMode", "font", "ttsVoice", "chineseConvert", "tapZones", "orientation",
            "ui.mode", "ui.paletteStyle", "ui.transition", "shelf.sort", "shelf.groups", "shelf.group",
        )
    }

    fun settings(): ReaderSettings {
        val d = ReaderSettings()
        return ReaderSettings(
            fontSize = sp.getFloat("fontSize", d.fontSize),
            lineHeight = sp.getFloat("lineHeight", d.lineHeight),
            paragraphSpacing = sp.getFloat("paragraphSpacing", d.paragraphSpacing),
            serif = sp.getBoolean("serif", d.serif),
            theme = runCatching { ReaderTheme.valueOf(sp.getString("theme", null)!!) }.getOrDefault(d.theme),
            keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
            tapToTurn = sp.getBoolean("tapToTurn", d.tapToTurn),
            pageMode = runCatching { PageMode.valueOf(sp.getString("pageMode", null)!!) }.getOrDefault(d.pageMode),
            volumeKeys = sp.getBoolean("volumeKeys", d.volumeKeys),
            brightness = sp.getFloat("brightness", d.brightness),
            fullscreen = sp.getBoolean("fullscreen", d.fullscreen),
            autoNight = sp.getBoolean("autoNight", d.autoNight),
            ttsRate = sp.getFloat("ttsRate", d.ttsRate),
            ambientLight = sp.getBoolean("ambientLight", d.ambientLight),
            eveningWarmth = sp.getBoolean("eveningWarmth", d.eveningWarmth),
            breakReminder = sp.getBoolean("breakReminder", d.breakReminder),
            indent = sp.getBoolean("indent", d.indent),
            justify = sp.getBoolean("justify", d.justify),
            font = sp.getString("font", d.font) ?: d.font,
            autoScrollLines = sp.getFloat("autoScrollLines", d.autoScrollLines),
            ttsVoice = sp.getString("ttsVoice", d.ttsVoice) ?: d.ttsVoice,
            eInk = sp.getBoolean("eInk", d.eInk),
            dailyGoal = sp.getInt("dailyGoal", d.dailyGoal),
            goalReminder = sp.getBoolean("goalReminder", d.goalReminder),
            pinyin = sp.getBoolean("pinyin", d.pinyin),
            twoPage = sp.getBoolean("twoPage", d.twoPage),
            margin = sp.getFloat("margin", d.margin),
            letterSpacing = sp.getFloat("letterSpacing", d.letterSpacing),
            customBackground = sp.getInt("customBackground", d.customBackground),
            customText = sp.getInt("customText", d.customText),
            tapZones = TapZones.decode(sp.getString("tapZones", null)),
            pageEdges = sp.getBoolean("pageEdges", d.pageEdges),
            orientation = runCatching { ReadingOrientation.valueOf(sp.getString("orientation", null)!!) }.getOrDefault(d.orientation),
            chineseConvert = runCatching { ChineseConvert.valueOf(sp.getString("chineseConvert", null)!!) }.getOrDefault(d.chineseConvert),
        )
    }

    fun saveSettings(s: ReaderSettings) {
        sp.edit {
            putFloat("fontSize", s.fontSize)
            putFloat("lineHeight", s.lineHeight)
            putFloat("paragraphSpacing", s.paragraphSpacing)
            putBoolean("serif", s.serif)
            putString("theme", s.theme.name)
            putBoolean("keepScreenOn", s.keepScreenOn)
            putBoolean("tapToTurn", s.tapToTurn)
            putString("pageMode", s.pageMode.name)
            putBoolean("volumeKeys", s.volumeKeys)
            putFloat("brightness", s.brightness)
            putBoolean("fullscreen", s.fullscreen)
            putBoolean("autoNight", s.autoNight)
            putBoolean("ambientLight", s.ambientLight)
            putBoolean("eveningWarmth", s.eveningWarmth)
            putBoolean("breakReminder", s.breakReminder)
            putBoolean("indent", s.indent)
            putBoolean("justify", s.justify)
            putString("chineseConvert", s.chineseConvert.name)
            putString("font", s.font)
            putFloat("autoScrollLines", s.autoScrollLines)
            putString("ttsVoice", s.ttsVoice)
            putBoolean("eInk", s.eInk)
            putInt("dailyGoal", s.dailyGoal)
            putBoolean("goalReminder", s.goalReminder)
            putBoolean("pinyin", s.pinyin)
            putBoolean("twoPage", s.twoPage)
            putFloat("margin", s.margin)
            putFloat("letterSpacing", s.letterSpacing)
            putInt("customBackground", s.customBackground)
            putInt("customText", s.customText)
            putString("tapZones", TapZones.encode(s.tapZones))
            putString("orientation", s.orientation.name)
            putBoolean("pageEdges", s.pageEdges)
            putFloat("ttsRate", s.ttsRate)
        }
    }

    fun appearance(): Appearance {
        val d = Appearance()
        return Appearance(
            mode = runCatching { ThemeMode.valueOf(sp.getString("ui.mode", null)!!) }.getOrDefault(d.mode),
            monet = sp.getBoolean("ui.monet", d.monet),
            accent = sp.getInt("ui.accent", d.accent),
            paletteStyle = runCatching { PaletteStyle.valueOf(sp.getString("ui.paletteStyle", null)!!) }.getOrDefault(d.paletteStyle),
            blur = sp.getBoolean("ui.blur", d.blur),
            liquidGlass = sp.getBoolean("ui.liquidGlass", d.liquidGlass),
            floatingBar = sp.getBoolean("ui.floatingBar", d.floatingBar),
            transition = runCatching { PageTransition.valueOf(sp.getString("ui.transition", null)!!) }.getOrDefault(d.transition),
            scale = sp.getFloat("ui.scale", d.scale).coerceIn(Appearance.SCALES.first(), Appearance.SCALES.last()),
        )
    }

    fun saveAppearance(a: Appearance) {
        sp.edit {
            putString("ui.mode", a.mode.name)
            putBoolean("ui.monet", a.monet)
            putInt("ui.accent", a.accent)
            putString("ui.paletteStyle", a.paletteStyle.name)
            putBoolean("ui.blur", a.blur)
            putBoolean("ui.liquidGlass", a.liquidGlass)
            putBoolean("ui.floatingBar", a.floatingBar)
            putString("ui.transition", a.transition.name)
            putFloat("ui.scale", a.scale)
        }
    }

    fun shelf(): ShelfPrefs = ShelfPrefs(
        sort = runCatching { ShelfSort.valueOf(sp.getString("shelf.sort", null)!!) }.getOrDefault(ShelfSort.RECENT),
        list = sp.getBoolean("shelf.list", false),
        groups = runCatching {
            org.json.JSONArray(sp.getString("shelf.groups", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
        }.getOrDefault(emptyList()),
        group = sp.getString("shelf.group", null),
    )

    fun saveShelf(p: ShelfPrefs) {
        sp.edit {
            putString("shelf.sort", p.sort.name)
            putBoolean("shelf.list", p.list)
            putString("shelf.groups", org.json.JSONArray(p.groups).toString())
            if (p.group == null) remove("shelf.group") else putString("shelf.group", p.group)
        }
    }

    /** 全部设置（阅读排版、书架显示）导出成 JSON，供备份使用。 */
    fun exportJson(): org.json.JSONObject {
        val o = org.json.JSONObject()
        sp.all.forEach { (k, v) ->
            when (v) {
                is Boolean -> o.put(k, org.json.JSONObject().put("b", v))
                is Float -> o.put(k, org.json.JSONObject().put("f", v.toDouble()))
                is Int -> o.put(k, org.json.JSONObject().put("i", v))
                is Long -> o.put(k, org.json.JSONObject().put("l", v))
                is String -> o.put(k, org.json.JSONObject().put("s", v))
            }
        }
        return o
    }

    fun importJson(o: org.json.JSONObject) {
        // 全部校验完再写盘。若某个已知键类型错误，旧偏好保持原样，避免下次启动时 getFloat 等抛异常。
        val values = mutableMapOf<String, Any>()
        o.keys().forEach { key ->
            val tag = when (key) {
                in FLOAT_KEYS -> "f"
                in BOOLEAN_KEYS -> "b"
                in INT_KEYS -> "i"
                in STRING_KEYS -> "s"
                else -> return@forEach
            }
            val wrapped = o.optJSONObject(key) ?: throw IllegalArgumentException("备份设置类型错误：$key")
            require(wrapped.length() == 1 && wrapped.has(tag)) { "备份设置类型错误：$key" }
            values[key] = when (tag) {
                "f" -> wrapped.getDouble(tag).toFloat().also { require(it.isFinite()) { "备份设置数值错误：$key" } }
                "b" -> wrapped.getBoolean(tag)
                "i" -> wrapped.getInt(tag)
                else -> wrapped.getString(tag)
            }
        }
        sp.edit {
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                }
            }
        }
    }
}
