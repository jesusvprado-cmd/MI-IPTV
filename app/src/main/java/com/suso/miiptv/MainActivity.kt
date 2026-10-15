package com.suso.miiptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.suso.miiptv.data.Repo
import com.suso.miiptv.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(applicationContext)
        setContent { AppRoot(this) }
    }

    override fun onResume() {
        super.onResume()
        // Al volver del reproductor, refresca "Continuar viendo".
        if (Repo.isReady()) Repo.prefs.bump()
    }
}
