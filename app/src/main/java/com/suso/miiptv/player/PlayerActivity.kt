package com.suso.miiptv.player

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.suso.miiptv.data.Repo
import com.suso.miiptv.ui.AppTheme

class PlayerActivity : ComponentActivity() {
    private var ctl: PlayerCtl? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(applicationContext)
        if (PlayQueue.items.isEmpty()) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val c = PlayerCtl(this)
        c.onFinished = { finish() }
        ctl = c
        c.start()
        setContent { AppTheme { PlayerScreen(c) { finish() } } }
    }

    override fun onStart() {
        super.onStart()
        ctl?.onStart()
    }

    override fun onStop() {
        ctl?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        ctl?.release()
        ctl = null
        super.onDestroy()
    }
}
