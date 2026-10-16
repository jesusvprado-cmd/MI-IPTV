package com.suso.miiptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.suso.miiptv.data.Repo
import com.suso.miiptv.player.PlayerHolder
import com.suso.miiptv.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(applicationContext)
        // La app gestiona ella misma el hueco del teclado (imePadding) para que los campos no queden tapados.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { AppRoot(this) }
    }

    private var startedOnce = false

    override fun onStart() {
        super.onStart()
        // Al volver de la pantalla completa, la previsualización se vuelve a enganchar.
        if (startedOnce) PlayerHolder.onMainVisible()
        startedOnce = true
    }

    override fun onStop() {
        // Si la app pasa a segundo plano, se corta la previsualización (libera la conexión).
        PlayerHolder.stopPreview()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Al volver del reproductor, refresca "Continuar viendo".
        if (Repo.isReady()) Repo.prefs.bump()
    }
}
