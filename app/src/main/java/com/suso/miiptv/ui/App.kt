package com.suso.miiptv.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
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

@Composable
fun AppRoot(activity: Activity) {
    AppTheme {
        var configured by remember { mutableStateOf(Repo.source != null) }
        var editing by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(C.bg).systemBarsPadding()) {
            when {
                !configured -> SetupScreen(canCancel = false, onDone = { configured = true }, onCancel = {})
                editing -> SetupScreen(canCancel = true, onDone = { editing = false }, onCancel = { editing = false })
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
                    }
                }
            }
        }
    }
}
