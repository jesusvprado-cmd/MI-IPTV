package com.suso.miiptv.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.suso.miiptv.data.Repo

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRoot(activity: Activity) {
    AppTheme {
      CompositionLocalProvider(LocalBringIntoViewSpec provides TvBringIntoView) {
        var configured by remember { mutableStateOf(Repo.source != null) }
        var editing by remember { mutableStateOf(false) }
        // Varios perfiles: se pregunta quién está viendo al abrir la app.
        var picking by remember {
            mutableStateOf(!ProfileSession.chosen && Repo.prefs.askProfile && Repo.prefs.profiles().size > 1)
        }
        // Al abrir la app mira si hay versión nueva (como mucho cada 6 horas).
        val ctx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(5000)
            runCatching { com.suso.miiptv.data.Updater.check(ctx) }
        }
        Box(Modifier.fillMaxSize().background(C.bg).systemBarsPadding()) {
            when {
                !configured -> SetupScreen(canCancel = false, onDone = { configured = true }, onCancel = {})
                editing -> SetupScreen(canCancel = true, onDone = { editing = false }, onCancel = { editing = false })
                picking -> {
                    BackHandler { activity.finish() }
                    ProfilePicker { picking = false }
                }
                else -> {
                    val top = UiState.stack.lastOrNull()
                    BackHandler {
                        when {
                            top != null -> UiState.pop()
                            UiState.tab != Tab.HOME -> {
                                UiState.tab = Tab.HOME
                                runCatching { UiState.tabRequester(Tab.HOME).requestFocus() }
                            }
                            else -> activity.finish()
                        }
                    }
                    when (top) {
                        null -> MainScreen()
                        is Screen.Movie -> key(top) { MovieDetailScreen(top.item) }
                        is Screen.Series -> key(top) { SeriesDetailScreen(top.item) }
                        Screen.Settings -> SettingsScreen(onChangeList = { editing = true })
                        Screen.HiddenCats -> HiddenCatsScreen()
                        Screen.MissingLogos -> MissingLogosScreen()
                    }
                }
            }
            if (configured && !editing && !picking) UpdateDialog()
            SplashOverlay()
        }
      }
    }
}
