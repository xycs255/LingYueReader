package com.xycs.lingyuereader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xycs.lingyuereader.ui.App
import com.xycs.lingyuereader.ui.LingYueTheme
import com.xycs.lingyuereader.ui.ThemeRevealHost
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    // 显式用本 Activity 的 Application 构造，不经过 AndroidX 默认工厂里缓存的静态实例。
    private val vm: AppViewModel by viewModels { viewModelFactory { initializer { AppViewModel(application) } } }

    /** 阅读页在前台且开启了音量键翻页时设置；返回前一页为 false，后一页为 true。 */
    var volumeKeyHandler: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearance by vm.appearance.collectAsStateWithLifecycle()
            LingYueTheme(appearance) { ThemeRevealHost { App(vm) } }
        }
        // 只在首次启动时处理外部文件，旋转屏幕等重建时不再重复导入。
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onStop() {
        super.onStop()
        // 退到后台时，离上次自动备份满一天就备份一次。
        vm.maybeAutoBackup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** “用灵阅打开”或从别的应用分享过来的文件。 */
    private fun handleIncoming(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamExtras()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.openExternal(uris)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handler = volumeKeyHandler
        if (handler != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            handler(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // 按下时已翻页，抬起也要吞掉，否则系统会弹出音量条。
        if (volumeKeyHandler != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }
}

private fun Intent.streamExtra(): Uri? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)

private fun Intent.streamExtras(): List<Uri> =
    (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
    else @Suppress("DEPRECATION") getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
