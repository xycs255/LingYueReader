package com.xycs.lingyuereader.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.xycs.lingyuereader.MainActivity
import com.xycs.lingyuereader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 听书时的前台服务：保住进程，并在通知栏放暂停/继续、停止按钮。 */
class ReadAloudService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "听书", NotificationManager.IMPORTANCE_LOW).apply {
                description = "朗读时显示当前章节和控制按钮"
                setShowBadge(false)
            },
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            build(ReadAloud.state.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        scope.launch {
            ReadAloud.state
                .map { s -> s?.let { Triple(it.chapterTitle, it.playing, it.bookTitle) } }
                .distinctUntilChanged()
                .collect { key ->
                    if (key == null) {
                        stopSelf()
                    } else {
                        manager.notify(NOTIFICATION_ID, build(ReadAloud.state.value))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> ReadAloud.toggle()
            ACTION_STOP -> ReadAloud.stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(state: ReadAloudState?): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(state?.bookTitle ?: "听书")
            .setContentText(state?.chapterTitle.orEmpty())
            .setContentIntent(open)
            .setOngoing(state?.playing == true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, if (state?.playing == true) "暂停" else "继续", action(this, ACTION_TOGGLE))
            .addAction(0, "停止", action(this, ACTION_STOP))
            .build()
    }

    companion object {
        private const val CHANNEL = "read_aloud"
        private const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "com.xycs.lingyuereader.READ_ALOUD_TOGGLE"
        const val ACTION_STOP = "com.xycs.lingyuereader.READ_ALOUD_STOP"

        private fun action(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, ReadAloudService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
