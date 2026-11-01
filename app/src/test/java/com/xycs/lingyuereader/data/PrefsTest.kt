package com.xycs.lingyuereader.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PrefsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(context)

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun wrongTypeInBackupDoesNotChangeAnySettings() {
        prefs.saveSettings(ReaderSettings(fontSize = 23f, lineHeight = 1.9f))
        val malformed = JSONObject()
            .put("lineHeight", JSONObject().put("f", 2.5))
            .put("fontSize", JSONObject().put("s", "bad"))

        val error = runCatching { prefs.importJson(malformed) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("fontSize"))
        assertEquals(23f, prefs.settings().fontSize, 0f)
        assertEquals(1.9f, prefs.settings().lineHeight, 0f)
    }

    @Test
    fun validBackupImportsKnownKeysAndSkipsUnknownFutureKeys() {
        prefs.importJson(
            JSONObject()
                .put("fontSize", JSONObject().put("f", 21.5))
                .put("shelf.list", JSONObject().put("b", true))
                .put("ui.mode", JSONObject().put("s", "DARK"))
                .put("future.setting", JSONObject().put("s", "skip me")),
        )

        assertEquals(21.5f, prefs.settings().fontSize, 0f)
        assertTrue(prefs.shelf().list)
        assertEquals(ThemeMode.DARK, prefs.appearance().mode)
        assertFalse(context.getSharedPreferences("reader", Context.MODE_PRIVATE).contains("future.setting"))
    }

    @Test
    fun exportedCurrentSettingsRoundTripThroughValidatedImport() {
        val settings = ReaderSettings(
            fontSize = 22f, lineHeight = 1.4f, paragraphSpacing = 0.2f, serif = false,
            theme = ReaderTheme.NIGHT, keepScreenOn = false, tapToTurn = false,
            pageMode = PageMode.SLIDE, volumeKeys = false, brightness = 0.5f,
            fullscreen = true, autoNight = false, ttsRate = 1.4f, ambientLight = false,
            eveningWarmth = false, breakReminder = false, indent = false, justify = false,
            chineseConvert = ChineseConvert.TO_TRADITIONAL, font = "my.ttf", autoScrollLines = 23f,
            ttsVoice = "zh", eInk = true, dailyGoal = 25, goalReminder = false,
            pinyin = true, twoPage = false, margin = 32f, letterSpacing = 0.1f,
            customBackground = 0xFF123456.toInt(), customText = 0xFF654321.toInt(),
            tapZones = TapZones.ONE_HAND, orientation = ReadingOrientation.LANDSCAPE, pageEdges = false, boldText = true,
        )
        val appearance = Appearance(
            mode = ThemeMode.DARK, monet = true, accent = 0xFF123456.toInt(),
            paletteStyle = PaletteStyle.VIBRANT, blur = false, liquidGlass = false,
            floatingBar = false, transition = PageTransition.FADE, scale = 0.9f,
        )
        val shelf = ShelfPrefs(sort = ShelfSort.TITLE, list = true, groups = listOf("在读"), group = "在读")
        prefs.saveSettings(settings)
        prefs.saveAppearance(appearance)
        prefs.saveShelf(shelf)
        val backup = prefs.exportJson()
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()

        prefs.importJson(backup)

        assertEquals(settings, prefs.settings())
        assertEquals(appearance, prefs.appearance())
        assertEquals(shelf, prefs.shelf())
    }

    /** 还什么都没存过的新安装：“深色模式时用夜间背景”直接认应用的深色。 */
    @Test
    fun freshInstallFollowsTheAppDarkMode() {
        val fresh = Prefs(context).settings()
        assertTrue(fresh.autoNight)
        assertFalse(fresh.autoNightSystemOnly)
        // 迁移只做一次：之后再建 Prefs 也不会把新安装当成老用户。
        Prefs(context).saveAppearance(Appearance(mode = ThemeMode.DARK))
        assertFalse(Prefs(context).settings().autoNightSystemOnly)
    }

    /** 升级上来的老用户（已经存过设置、还没有设置版本）：照旧只认系统深色，开关的值不动。 */
    @Test
    fun upgradeKeepsTheOldSystemOnlyBehaviour() {
        val sp = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        sp.edit().putString("ui.mode", ThemeMode.DARK.name).putFloat("fontSize", 21f).commit()
        val upgraded = Prefs(context).settings()
        assertTrue(upgraded.autoNight)
        assertTrue(upgraded.autoNightSystemOnly)
        assertEquals(21f, upgraded.fontSize, 0f)

        // 自己关掉过的照旧是关的。
        sp.edit().clear().putBoolean("autoNight", false).commit()
        val off = Prefs(context).settings()
        assertFalse(off.autoNight)
        assertTrue(off.autoNightSystemOnly)
    }

    /** 老用户自己动过一次开关就改成新的认法，以后再打开也不会被迁移改回去。 */
    @Test
    fun touchingTheSwitchOnceMovesToTheNewBehaviour() {
        val sp = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        sp.edit().putString("theme", ReaderTheme.PAPER.name).commit()
        val p = Prefs(context)
        assertTrue(p.settings().autoNightSystemOnly)
        p.saveSettings(p.settings().copy(autoNight = true, autoNightSystemOnly = false))
        assertFalse(Prefs(context).settings().autoNightSystemOnly)
        assertFalse(Prefs(context).settings().autoNightSystemOnly)
    }

    /** 备份里带着这一项，恢复到新手机上行为一样；老备份没有这一项也能导入。 */
    @Test
    fun backupCarriesTheNightSetting() {
        val sp = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        sp.edit().putString("theme", ReaderTheme.PAPER.name).commit()
        val p = Prefs(context)
        val exported = p.exportJson()
        assertTrue(exported.getJSONObject("autoNightSystemOnly").getBoolean("b"))
        sp.edit().clear().commit()
        val restored = Prefs(context)
        restored.importJson(exported)
        assertTrue(restored.settings().autoNightSystemOnly)
        restored.importJson(JSONObject().put("autoNightSystemOnly", JSONObject().put("b", false)))
        assertFalse(restored.settings().autoNightSystemOnly)
        restored.importJson(JSONObject().put("fontSize", JSONObject().put("f", 20.0)))
        assertFalse(restored.settings().autoNightSystemOnly)
    }

    /** 新安装以后恢复旧版本的备份（没有设置版本，带着“深色模式时用夜间背景”）：和升级上来的老用户一样，照旧只认系统深色。 */
    @Test
    fun restoringAnOldBackupKeepsTheOldSystemOnlyBehaviour() {
        val fresh = Prefs(context)
        assertFalse(fresh.settings().autoNightSystemOnly)
        val old = JSONObject()
            .put("autoNight", JSONObject().put("b", true))
            .put("fontSize", JSONObject().put("f", 21.0))
            .put("ui.mode", JSONObject().put("s", ThemeMode.DARK.name))
        fresh.importJson(old)
        assertTrue(fresh.settings().autoNight)
        assertTrue(fresh.settings().autoNightSystemOnly)
        // 新版本导出的备份（记着设置版本）：照备份里的，不补。
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        val again = Prefs(context)
        val exported = again.exportJson()
        assertTrue(exported.has("prefs.version"))
        again.importJson(exported.put("autoNight", JSONObject().put("b", true)))
        assertFalse(again.settings().autoNightSystemOnly)
    }
}
