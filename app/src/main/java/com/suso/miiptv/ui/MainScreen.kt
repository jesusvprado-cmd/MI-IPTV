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
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Tab.values().forEach { t ->
                                TopTab(
                                    text = t.label,
                                    selected = UiState.tab == t,
                                    menuFocused = hovered != null,
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
                }
            }
            Spacer(Modifier.width(12.dp))
            Clock()
            Spacer(Modifier.width(14.dp))
            TopIcon(Icons.Filled.Settings, "Ajustes", "tab_settings") { UiState.push(Screen.Settings) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.divider))
    }
}

/** Hora en la esquina, como en las apps de TV. */
@Composable
fun Clock(fontSize: Int = 17) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000 + 50)
        }
    }
    androidx.compose.material3.Text(
        // Mismo estilo que las secciones del menú (Inicio, TV en directo...).
        hm(now), color = androidx.compose.ui.graphics.Color(0xFFB4B4B4), fontSize = fontSize.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontFamily = Manrope, maxLines = 1,
    )
}

/**
 * Sección del menú superior: la activa en dorado y negrita; la que tiene el cursor del mando
 * se agranda un poco con un brillo suave detrás (sin recuadro blanco).
 */
@Composable
private fun TopTab(
    text: String,
    selected: Boolean,
    menuFocused: Boolean,
    focusKey: String,
    focusRequester: FocusRequester,
    requestInitial: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (requestInitial) {
            delay(80)
            runCatching { focusRequester.requestFocus() }
        }
    }
    // Sin brillo ni recuadro: la sección con el cursor se pone en dorado y en negrita, al instante.
    // Con el cursor en el menú solo esa va en dorado; si estás abajo, la sección activa.
    val color = when {
        focused -> C.gold
        selected && !menuFocused -> C.gold
        else -> Color(0xFFB4B4B4)
    }
    Box(
        Modifier
            .focusRequester(focusRequester)
            .memFocus(focusKey) {
                focused = it
                onFocusChange(it)
            }
            .tvClick(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Texto invisible en negrita: reserva el ancho para que el menú no se mueva al cambiar el grosor.
        Text(text, color = Color.Transparent, fontSize = 17.sp, maxLines = 1, fontWeight = FontWeight.ExtraBold)
        Text(
            text, color = color, fontSize = 17.sp, maxLines = 1,
            fontWeight = when {
                focused -> FontWeight.ExtraBold
                selected && !menuFocused -> FontWeight.SemiBold
                else -> FontWeight.Medium
            },
        )
    }
}

/** Icono del menú superior (Ajustes) con el mismo estilo que las secciones. */
@Composable
private fun TopIcon(icon: ImageVector, label: String, focusKey: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color by animateColorAsState(if (focused) C.gold else Color(0xFFB4B4B4), Motion.focusSpec(), label = "tic")
    Box(
        Modifier
            .memFocus(focusKey) { focused = it }
            .tvClick(onClick = onClick)
            .padding(9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(26.dp))
    }
}
