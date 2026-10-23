package com.xycs.lingyuereader.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.R
import com.xycs.lingyuereader.data.DayStats
import com.xycs.lingyuereader.data.Prefs
import com.xycs.lingyuereader.data.ReadingStats
import java.io.File
import java.time.LocalDate
import java.util.Calendar

/**
 * 阅读目标的晚间提醒：每天晚上（默认 21 点）看一眼，今天还没读够目标时发一条通知；
 * 连续读了好几天、今天还一分钟没读的，提醒别断了。用不精确的闹钟，省电，早几分钟晚几分钟都行。
 */
object GoalReminder {
    private const val CHANNEL = "reading_goal"
    private const val NOTIFICATION_ID = 21
    const val HOUR = 21

    /** 按设置排上或取消每天的提醒。打开应用、改设置、开机后调用。 */
    fun schedule(context: Context) {
        val settings = Prefs(context).settings()
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        alarms.cancel(pending)
        if (settings.dailyGoal <= 0 || !settings.goalReminder) return
        alarms.setInexactRepeating(AlarmManager.RTC, nextTrigger(System.currentTimeMillis()), AlarmManager.INTERVAL_DAY, pending)
    }

    /** 下一个晚上 [HOUR] 点。 */
    fun nextTrigger(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, HOUR)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, GoalReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 该说的话；不需要提醒时为空。 */
    fun message(goalMinutes: Int, days: Map<LocalDate, DayStats>, today: LocalDate = LocalDate.now()): String? {
        if (goalMinutes <= 0) return null
        val read = ((days[today]?.millis ?: 0) / 60_000).toInt()
        if (read >= goalMinutes) return null
        val streak = ReadingStats.streak(days, today)
        return when {
            read == 0 && streak > 0 -> "已经连续读了 $streak 天，今天还没读。读几分钟，别断了。"
            read == 0 -> "今天还没读书，目标是 $goalMinutes 分钟。"
            else -> "今天读了 $read 分钟，离目标还差 ${goalMinutes - read} 分钟。"
        }
    }

    fun notifyIfNeeded(context: Context) {
        val settings = Prefs(context).settings()
        if (!settings.goalReminder) return
        val days = ReadingStats(File(context.filesDir, "stats.json")).days()
        val text = message(settings.dailyGoal, days) ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "阅读目标", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("今天的阅读")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

/** 闹钟到点：看看今天读够没有。 */
class GoalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = GoalReminder.notifyIfNeeded(context)
}

/** 开机后闹钟会被清掉，重新排上。 */
class GoalBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) GoalReminder.schedule(context)
    }
}
