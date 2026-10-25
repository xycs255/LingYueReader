package com.xycs.lingyuereader.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xycs.lingyuereader.data.DayStats
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ReadingStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class GoalReminderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val today = LocalDate.of(2026, 10, 23)
    private fun minutes(m: Int) = DayStats(millis = m * 60_000L)

    @Before
    fun setUp() {
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "stats.json").delete()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun messagesDependOnTheGoalAndTheStreak() {
        assertNull(GoalReminder.message(0, emptyMap(), today))
        assertEquals("今天还没读书，目标是 30 分钟。", GoalReminder.message(30, emptyMap(), today))
        assertEquals("今天读了 12 分钟，离目标还差 18 分钟。", GoalReminder.message(30, mapOf(today to minutes(12)), today))
        val streak = mapOf(today.minusDays(1) to minutes(40), today.minusDays(2) to minutes(5))
        assertEquals("已经连续读了 2 天，今天还没读。读几分钟，别断了。", GoalReminder.message(30, streak, today))
        assertNull(GoalReminder.message(30, mapOf(today to minutes(31)), today))
    }

    @Test
    fun reminderIsScheduledForNinePmOnlyWithAGoal() {
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        GoalReminder.schedule(context)
        assertNull(alarms.nextScheduledAlarm)

        val prefs = Prefs(context)
        prefs.saveSettings(prefs.settings().copy(dailyGoal = 30))
        GoalReminder.schedule(context)
        val alarm = alarms.nextScheduledAlarm
        assertNotNull(alarm)
        val at = Calendar.getInstance().apply { timeInMillis = alarm!!.triggerAtTime }
        assertEquals(21, at.get(Calendar.HOUR_OF_DAY))
        assertEquals(AlarmManager.INTERVAL_DAY, alarm!!.interval)

        prefs.saveSettings(prefs.settings().copy(goalReminder = false))
        GoalReminder.schedule(context)
        assertNull(alarms.nextScheduledAlarm)
    }

    @Test
    fun theReceiverPostsANotificationOnlyWhenTheGoalIsNotMet() {
        val prefs = Prefs(context)
        prefs.saveSettings(prefs.settings().copy(dailyGoal = 30))
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))

        ReadingStats(File(context.filesDir, "stats.json")).addTime("b1", 10 * 60_000L)
        GoalReminderReceiver().onReceive(context, android.content.Intent())
        assertEquals(1, notifications.allNotifications.size)
        assertTrue(notifications.allNotifications[0].extras.getString("android.text")!!.contains("还差 20 分钟"))

        context.getSystemService(NotificationManager::class.java).cancelAll()
        ReadingStats(File(context.filesDir, "stats.json")).addTime("b1", 25 * 60_000L)
        GoalReminderReceiver().onReceive(context, android.content.Intent())
        assertEquals(0, notifications.allNotifications.size)
    }

    /** 统计文件坏了：到点的提醒跳过这一次，不能让应用在后台崩溃；坏文件原样留着。 */
    @Test
    fun aDamagedStatsFileSkipsTheReminderInsteadOfCrashing() {
        val prefs = Prefs(context)
        prefs.saveSettings(prefs.settings().copy(dailyGoal = 30))
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        val file = File(context.filesDir, "stats.json").apply { writeText("{\"days\": [") }

        GoalReminderReceiver().onReceive(context, android.content.Intent())

        assertEquals(0, notifications.allNotifications.size)
        assertEquals("{\"days\": [", file.readText())
    }
}
