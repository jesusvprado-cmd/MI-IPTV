package com.suso.miiptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suso.miiptv.data.Kind
import kotlinx.coroutines.delay

@Composable
fun MainScreen() {
    // Si volvemos de una ficha, el foco lo recupera la tarjeta; si no, va a la pestaña.
    val restoring = remember { FocusMemory.restore != null }
    Box(Modifier.fillMaxSize().background(C.bg)) {
    // Fondo que toma el color de la carátula con el foco (Inicio, Películas y Series).
    AmbientBackground(enabled = UiState.tab == Tab.HOME || UiState.tab == Tab.MOVIES || UiState.tab == Tab.SERIES)
    Column(Modifier.fillMaxSize()) {
        TopBar(restoring)
        Box(Modifier.fillMaxWidth().weight(1f).fadeInOnChange(UiState.tab)) {
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
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TopBar(restoring: Boolean) {
    // La sección cambia sola al ponerse encima (con una pausa corta para poder pasar de largo).
    var hovered by remember { mutableStateOf<Tab?>(null) }
    LaunchedEffect(hovered) {
        val h = hovered ?: return@LaunchedEffect
        delay(260)
        if (UiState.tab != h) UiState.tab = h
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(34.dp)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(
                    Modifier
                        // Al subir al menú desde el contenido, el foco entra por la sección actual.
                        .focusProperties { enter = { UiState.tabRequester(UiState.tab) } }
                        .focusGroup()
                        .horizontalScroll(rememberScrollState()),
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
                            onFocusChange = { f ->
                                if (f) hovered = t else if (hovered == t) hovered = null
                            },
                        ) {
                            hovered = null
                            if (UiState.tab != t) UiState.tab = t
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Clock()
            Spacer(Modifier.width(14.dp))
            TabChip(text = "Ajustes", selected = false, focusKey = "tab_settings", icon = Icons.Filled.Settings) {
                UiState.push(Screen.Settings)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.divider))
    }
}

/** Hora en la esquina, como en las apps de TV. */
@Composable
fun Clock(fontSize: Int = 18) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000 + 50)
        }
    }
    androidx.compose.material3.Text(
        hm(now), color = androidx.compose.ui.graphics.Color.White, fontSize = fontSize.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
    )
}
