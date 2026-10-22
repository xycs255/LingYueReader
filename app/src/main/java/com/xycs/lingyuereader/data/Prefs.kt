package com.xycs.lingyuereader.data

import android.content.Context
import androidx.core.content.edit

enum class ReaderTheme(val label: String) {
    PAPER("纸白"),
    PARCHMENT("宣黄"),
    GREEN("护眼"),
    NIGHT("夜间"),
}

data class ReaderSettings(
    val fontSize: Float = 19f,
    val lineHeight: Float = 1.8f,
    val paragraphSpacing: Float = 0.6f,
    val serif: Boolean = true,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val keepScreenOn: Boolean = true,
    val tapToTurn: Boolean = true,
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
        }
    }
}
