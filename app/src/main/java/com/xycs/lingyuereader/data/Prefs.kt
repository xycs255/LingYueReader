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
)

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
        }
    }
}
