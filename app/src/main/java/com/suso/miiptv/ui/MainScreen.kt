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
    val tabPos = remember { mutableStateMapOf<Tab, Pair<Float, Float>>() }
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
                                    focusKey = "tab_${t.name}",
                                    focusRequester = UiState.tabRequester(t),
                                    requestInitial = !restoring && UiState.tab == t,
                                    onPlaced = { x, w -> tabPos[t] = x to w },
                                    onFocusChange = { f ->
                                        if (f) hovered = t else if (hovered == t) hovered = null
                                    },
                                ) {
                                    hovered = null
                                    if (UiState.tab != t) UiState.tab = t
                                }
                            }
                        }
                        // Rayita dorada bajo la sección activa, que se desliza al cambiar.
                        val pos = tabPos[UiState.tab]
                        if (pos != null) {
                            val density = LocalDensity.current
                            val lineW = with(density) { (pos.second * 0.5f).toDp() }
                            val x by animateFloatAsState(pos.first + pos.second * 0.25f, tween(320, easing = FastOutSlowInEasing), label = "tabx")
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .offset { IntOffset(x.roundToInt(), 0) }
                                    .width(lineW)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(GoldBrush)
                            )
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

/**
 * Sección del menú superior: la activa en dorado y negrita; la que tiene el cursor del mando
 * se agranda un poco con un brillo suave detrás (sin recuadro blanco).
 */
@Composable
private fun TopTab(
    text: String,
    selected: Boolean,
    focusKey: String,
    focusRequester: FocusRequester,
    requestInitial: Boolean,
    onPlaced: (Float, Float) -> Unit,
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
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, Motion.focusSpec(), label = "tts")
    val glow by animateFloatAsState(if (focused) 1f else 0f, Motion.focusSpec(), label = "ttg")
    val color by animateColorAsState(
        when {
            selected -> C.gold
            focused -> Color.White
            else -> Color(0xFFB4B4B4)
        },
        Motion.focusSpec(), label = "ttc",
    )
    Box(
        Modifier
            .onGloballyPositioned { c -> onPlaced(c.positionInParent().x, c.size.width.toFloat()) }
            .focusRequester(focusRequester)
            .memFocus(focusKey) {
                focused = it
                onFocusChange(it)
            }
            .tvClick(onClick = onClick)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                if (glow > 0f) {
                    drawRoundRect(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.16f * glow), Color.Transparent),
                            center = center, radius = size.width * 0.7f,
                        ),
                        cornerRadius = CornerRadius(size.height / 2),
                    )
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = color, fontSize = 17.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/** Icono del menú superior (Ajustes) con el mismo estilo que las secciones. */
@Composable
private fun TopIcon(icon: ImageVector, label: String, focusKey: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.15f else 1f, Motion.focusSpec(), label = "tis")
    val color by animateColorAsState(if (focused) C.gold else Color(0xFFB4B4B4), Motion.focusSpec(), label = "tic")
    Box(
        Modifier
            .memFocus(focusKey) { focused = it }
            .tvClick(onClick = onClick)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (focused) 0.14f else 0f))
            .padding(9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(26.dp))
    }
}
