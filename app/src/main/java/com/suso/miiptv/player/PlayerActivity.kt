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
        // Solo en los canales SAT: en el resto el cambio de frecuencia solo provoca un corte de imagen al entrar.
        c.onVideoFormat = { f -> if (Repo.prefs.afr && isSatChannel(c.current)) matchFrameRate(f.frameRate) else resetFrameRate() }
        ctl = c
        c.start()
        setContent { AppTheme { PlayerScreen(c) { finish() } } }
    }

    private var afrApplied = -1f

    /** Lee un archivo del sistema (en los aparatos Amlogic, /sys/class/display/mode dice la salida HDMI real). */
    private fun sys(path: String): String = try {
        java.io.File(path).readText().trim()
    } catch (e: Exception) {
        "?"
    }

    private fun modeText(m: android.view.Display.Mode?): String =
        if (m == null) "?" else "modo ${m.modeId} (${"%.2f".format(m.refreshRate)} Hz)"

    private var switchListener: android.hardware.display.DisplayManager.DisplayListener? = null

    /**
     * Pide a la pantalla el modo [modeId] (0 = el normal del aparato). Si de verdad va a cambiar, el vídeo se para
     * antes, se espera a que la pantalla termine el cambio (y a que la tele se asiente) y entonces se vuelve a abrir.
     * Así el vídeo arranca ya con la frecuencia nueva, como cuando se apagaba y encendía el ajuste a mano.
     */
    private fun applyMode(modeId: Int, why: String) {
        val lp = window.attributes
        if (lp.preferredDisplayModeId == modeId) return
        val display = window.decorView.display
        val before = display?.mode
        if (modeId != 0 && before?.modeId == modeId) {
            lp.preferredDisplayModeId = modeId
            window.attributes = lp
            return
        }
        com.suso.miiptv.data.ErrorLog.add(
            "Frecuencia ($why): pantalla en ${modeText(before)} · HDMI ${sys("/sys/class/display/mode")} · se pide " +
                (if (modeId == 0) "la normal" else modeText(display?.supportedModes?.firstOrNull { it.modeId == modeId })),
        )
        val c = ctl
        c?.holdForDisplaySwitch()
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        switchListener?.let { runCatching { dm?.unregisterDisplayListener(it) } }
        var done = false
        val finishSwitch = object : Runnable {
            override fun run() {
                if (done) return
                done = true
                switchListener?.let { runCatching { dm?.unregisterDisplayListener(it) } }
                switchListener = null
                // La tele tarda un poco en mostrar imagen tras el cambio: se espera antes de volver a abrir el vídeo.
                window.decorView.postDelayed({
                    com.suso.miiptv.data.ErrorLog.add(
                        "Frecuencia: ya en ${modeText(window.decorView.display?.mode)} · HDMI ${sys("/sys/class/display/mode")}",
                    )
                    if (!isFinishing) ctl?.resumeAfterDisplaySwitch()
                }, 1200)
            }
        }
        val l = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                val now = window.decorView.display?.mode?.modeId
                if (now != before?.modeId) finishSwitch.run()
            }

            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        switchListener = l
        runCatching { dm?.registerDisplayListener(l, android.os.Handler(android.os.Looper.getMainLooper())) }
        lp.preferredDisplayModeId = modeId
        window.attributes = lp
        // Por si el aparato no avisa del cambio.
        window.decorView.postDelayed(finishSwitch, if (modeId == 0) 2500 else 4000)
    }

    /** Vuelve a la frecuencia normal de la pantalla (canal que no es SAT, o ajuste desactivado). */
    private fun resetFrameRate() {
        if (afrApplied <= 0f) return
        afrApplied = -1f
        try {
            applyMode(0, "normal")
        } catch (e: Exception) {
            // Sin importancia.
        }
    }

    /**
     * Cambia la frecuencia de la pantalla para que coincida con la del vídeo (24, 25, 50, 60 Hz...),
     * en las teles y reproductores que lo permiten. Se deshace sola al salir del reproductor.
     */
    private fun matchFrameRate(fps: Float) {
        if (Build.VERSION.SDK_INT < 23 || fps <= 0f) return
        try {
            val display = window.decorView.display ?: return
            val cur = display.mode
            val same = display.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            fun fits(m: android.view.Display.Mode): Boolean {
                val ratio = m.refreshRate / fps
                val n = Math.round(ratio)
                return n >= 1 && kotlin.math.abs(ratio - n) <= 0.01f * n
            }
            // La frecuencia más baja que sea múltiplo de los fps del vídeo.
            val b = same.filter { fits(it) }.minByOrNull { it.refreshRate }
                ?: return
            afrApplied = fps
            applyMode(b.modeId, "vídeo a $fps fps")
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
        switchListener?.let { l -> runCatching { getSystemService(android.hardware.display.DisplayManager::class.java)?.unregisterDisplayListener(l) } }
        ctl?.release()
        ctl = null
        PlayerHolder.fullscreen = false
        super.onDestroy()
    }
}
