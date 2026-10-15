package com.suso.miiptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suso.miiptv.data.Kind

@Composable
fun MainScreen() {
    // Si volvemos de una ficha, el foco lo recupera la tarjeta; si no, va a la pestaña.
    val restoring = remember { FocusMemory.restore != null }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(restoring)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (UiState.tab) {
                Tab.HOME -> HomeScreen()
                Tab.LIVE -> BrowseScreen(Kind.LIVE)
                Tab.MOVIES -> BrowseScreen(Kind.MOVIE)
                Tab.SERIES -> BrowseScreen(Kind.SERIES)
                Tab.SEARCH -> SearchScreen()
            }
        }
    }
}

@Composable
private fun TopBar(restoring: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(34.dp)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tab.values().forEach { t ->
                        TabChip(
                            text = t.label,
                            selected = UiState.tab == t,
                            focusKey = "tab_${t.name}",
                            focusRequester = UiState.tabRequester(t),
                            requestInitial = !restoring && UiState.tab == t,
                        ) {
                            if (UiState.tab != t) UiState.tab = t
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            TabChip(text = "Ajustes", selected = false, focusKey = "tab_settings", icon = Icons.Filled.Settings) {
                UiState.push(Screen.Settings)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.divider))
    }
}
