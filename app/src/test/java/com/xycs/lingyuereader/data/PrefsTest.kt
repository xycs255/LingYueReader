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
            tapZones = TapZones.ONE_HAND, orientation = ReadingOrientation.LANDSCAPE, pageEdges = false,
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
}
