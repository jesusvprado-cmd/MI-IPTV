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
        c.onVideoFormat = { f -> if (Repo.prefs.afr) matchFrameRate(f.frameRate) else resetFrameRate() }
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
    /** Cada cambio pedido lleva un número: si llega otro cambio a mitad, los pasos pendientes del anterior se ignoran. */
    private var switchGen = 0

    private fun setPref(modeId: Int) {
        val lp = window.attributes
        lp.preferredDisplayModeId = modeId
        window.attributes = lp
    }

    private fun later(gen: Int, ms: Long, step: () -> Unit) {
        window.decorView.postDelayed({ if (gen == switchGen && !isFinishing) step() }, ms)
    }

    /** Espera a que la pantalla deje el modo [fromId] (o [timeoutMs] como mucho) y sigue con [then]. */
    private fun waitForChange(gen: Int, fromId: Int?, timeoutMs: Long, then: () -> Unit) {
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        switchListener?.let { runCatching { dm?.unregisterDisplayListener(it) } }
        var done = false
        val go = {
            if (!done) {
                done = true
                switchListener?.let { runCatching { dm?.unregisterDisplayListener(it) } }
                switchListener = null
                if (gen == switchGen && !isFinishing) then()
            }
        }
        val l = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (window.decorView.display?.mode?.modeId != fromId) go()
            }

            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        switchListener = l
        runCatching { dm?.registerDisplayListener(l, android.os.Handler(android.os.Looper.getMainLooper())) }
        window.decorView.postDelayed({ go() }, timeoutMs)
    }

    /**
     * Pide a la pantalla el modo [modeId] (0 = el normal del aparato) con el vídeo parado, y lo vuelve a abrir al acabar.
     *
     * En la Mi Box el primer paso a la frecuencia del vídeo deja la imagen a microsaltos, y se arregla al volver a
     * pedirla una segunda vez (lo que pasaba al apagar y encender el ajuste a mano, comprobado en el registro).
     * Por eso, al llegar a la frecuencia nueva se hace ese «rebote» solo (normal → la del vídeo) mientras la pantalla
     * aún está en negro por el primer cambio, y el vídeo no se vuelve a abrir hasta que todo ha terminado.
     */
    /** Pide a la pantalla el modo [modeId] (0 = el normal del aparato), sin parar el vídeo ni nada más. */
    private fun applyMode(modeId: Int, why: String) {
        val lp = window.attributes
        if (lp.preferredDisplayModeId == modeId) return
        setPref(modeId)
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
