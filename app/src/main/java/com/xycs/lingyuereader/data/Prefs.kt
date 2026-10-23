package com.xycs.lingyuereader.data

import android.content.Context
import androidx.core.content.edit

enum class ReaderTheme(val label: String) {
    PAPER("纸白"),
    PARCHMENT("宣黄"),
    GREEN("护眼"),
    NIGHT("夜间"),
}

enum class PageMode(val label: String) {
    SCROLL("上下滚动"),
    SLIDE("左右平移"),
    COVER("左右覆盖"),
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
)

enum class ShelfSort(val label: String) {
    RECENT("最近阅读"),
    TITLE("书名"),
    ADDED("添加时间"),
}

data class ShelfPrefs(val sort: ShelfSort = ShelfSort.RECENT, val list: Boolean = false)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

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
            putFloat("ttsRate", s.ttsRate)
        }
    }

    fun shelf(): ShelfPrefs = ShelfPrefs(
        sort = runCatching { ShelfSort.valueOf(sp.getString("shelf.sort", null)!!) }.getOrDefault(ShelfSort.RECENT),
        list = sp.getBoolean("shelf.list", false),
    )

    fun saveShelf(p: ShelfPrefs) {
        sp.edit {
            putString("shelf.sort", p.sort.name)
            putBoolean("shelf.list", p.list)
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
        sp.edit {
            o.keys().forEach { k ->
                val v = o.optJSONObject(k) ?: return@forEach
                when {
                    v.has("b") -> putBoolean(k, v.getBoolean("b"))
                    v.has("f") -> putFloat(k, v.getDouble("f").toFloat())
                    v.has("i") -> putInt(k, v.getInt("i"))
                    v.has("l") -> putLong(k, v.getLong("l"))
                    v.has("s") -> putString(k, v.getString("s"))
                }
            }
        }
    }
}
