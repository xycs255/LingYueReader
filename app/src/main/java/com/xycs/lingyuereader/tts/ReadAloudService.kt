package com.xycs.lingyuereader.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
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

/**
 * 听书时的前台服务：保住进程，通知栏放上一句、暂停/继续、下一句、停止按钮；
 * 并登记一个媒体会话，耳机线控和蓝牙按键（播放/暂停、上一曲/下一曲）都能控制朗读。
 */
class ReadAloudService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null

    /** 测试用：拿到会话，模拟耳机、蓝牙按键。 */
    internal val sessionToken: MediaSession.Token? get() = session?.sessionToken

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
        // 耳机单击 = 播放/暂停，双击 = 下一句，三击 = 上一句（系统按播放/下一曲/上一曲分发）。
        session = MediaSession(this, "LingYueReadAloud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = ReadAloud.resume()
                override fun onPause() = ReadAloud.pause()
                override fun onStop() = ReadAloud.stop()
                override fun onSkipToNext() = ReadAloud.nextSentence()
                override fun onSkipToPrevious() = ReadAloud.previousSentence()
            })
            isActive = true
        }
        scope.launch {
            ReadAloud.state
                .map { s -> s?.let { Triple(it.chapterTitle, it.playing, it.bookTitle) } }
                .distinctUntilChanged()
                .collect { key ->
                    if (key == null) {
                        stopSelf()
                    } else {
                        updateSession(ReadAloud.state.value)
                        manager.notify(NOTIFICATION_ID, build(ReadAloud.state.value))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> ReadAloud.toggle()
            ACTION_STOP -> ReadAloud.stop()
            ACTION_PREVIOUS -> ReadAloud.previousSentence()
            ACTION_NEXT -> ReadAloud.nextSentence()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun updateSession(state: ReadAloudState?) {
        val s = session ?: return
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(
                    if (state?.playing == true) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state?.chapterTitle.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, state?.bookTitle.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "灵阅听书")
                .build(),
        )
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
            .addAction(0, "上一句", action(this, ACTION_PREVIOUS))
            .addAction(0, if (state?.playing == true) "暂停" else "继续", action(this, ACTION_TOGGLE))
            .addAction(0, "下一句", action(this, ACTION_NEXT))
            .addAction(0, "停止", action(this, ACTION_STOP))
            .build()
    }

    companion object {
        private const val CHANNEL = "read_aloud"
        private const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "com.xycs.lingyuereader.READ_ALOUD_TOGGLE"
        const val ACTION_STOP = "com.xycs.lingyuereader.READ_ALOUD_STOP"
        const val ACTION_PREVIOUS = "com.xycs.lingyuereader.READ_ALOUD_PREVIOUS"
        const val ACTION_NEXT = "com.xycs.lingyuereader.READ_ALOUD_NEXT"

        private fun action(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, ReadAloudService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
