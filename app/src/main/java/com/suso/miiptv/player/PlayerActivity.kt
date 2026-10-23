package com.suso.miiptv.player

import android.os.Build
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
        PlayerHolder.fullscreen = true
        val c = PlayerCtl(this)
        c.onFinished = { finish() }
        // La frecuencia de la pantalla se ajusta (o se deja como estaba) según el ajuste, que también se cambia desde las opciones.
        c.onVideoFormat = { f -> if (Repo.prefs.afr) matchFrameRate(f.frameRate) else resetFrameRate() }
        ctl = c
        c.start()
        setContent { AppTheme { PlayerScreen(c) { finish() } } }
    }

    private var afrApplied = -1f

    /** Vuelve a la frecuencia normal de la pantalla (al desactivar el ajuste desde las opciones). */
    private fun resetFrameRate() {
        if (afrApplied <= 0f) return
        afrApplied = -1f
        try {
            val lp = window.attributes
            lp.preferredDisplayModeId = 0
            window.attributes = lp
        } catch (e: Exception) {
            // Sin importancia.
        }
    }

    /**
     * Cambia la frecuencia de la pantalla para que coincida con la del vídeo (24, 25, 50, 60 Hz...),
     * en las teles y reproductores que lo permiten. Se deshace sola al salir del reproductor.
     */
    private fun matchFrameRate(fps: Float) {
        if (Build.VERSION.SDK_INT < 23 || fps <= 0f || fps == afrApplied) return
        try {
            val display = window.decorView.display ?: return
            val cur = display.mode
            var best: android.view.Display.Mode? = null
            var bestMult = Int.MAX_VALUE
            for (m in display.supportedModes) {
                if (m.physicalWidth != cur.physicalWidth || m.physicalHeight != cur.physicalHeight) continue
                val ratio = m.refreshRate / fps
                val n = Math.round(ratio)
                if (n < 1 || kotlin.math.abs(ratio - n) > 0.01f * n) continue
                if (n < bestMult) {
                    bestMult = n
                    best = m
                }
            }
            val b = best ?: return
            afrApplied = fps
            if (b.modeId != cur.modeId) {
                val lp = window.attributes
                lp.preferredDisplayModeId = b.modeId
                window.attributes = lp
            }
        } catch (e: Exception) {
            // Este dispositivo no permite cambiar la frecuencia.
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onStart() {
        super.onStart()
        ctl?.onStart()
    }

    override fun onStop() {
        ctl?.onStop(isFinishing)
        super.onStop()
    }

    override fun onDestroy() {
        ctl?.release()
        ctl = null
        PlayerHolder.fullscreen = false
        super.onDestroy()
    }
}
