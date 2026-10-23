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
import androidx.compose.material.icons.filled.Search
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
    AmbientBackground(enabled = !com.suso.miiptv.data.Perf.light && (UiState.tab == Tab.HOME || UiState.tab == Tab.MOVIES || UiState.tab == Tab.SERIES))
    val mainCtx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopBar(restoring)
        Box(Modifier.fillMaxWidth().weight(1f).fadeInOnChange(UiState.tab)) {
            when (UiState.tab) {
                Tab.HOME -> HomeScreen()
                Tab.LIVE -> BrowseScreen(Kind.LIVE)
                Tab.GUIDE -> GuideScreen()
                Tab.MOVIES -> BrowseScreen(Kind.MOVIE)
                Tab.SERIES -> BrowseScreen(Kind.SERIES)
                Tab.SEARCH -> SearchScreen()
            }
        }
    }
    // Aviso de programas con recordatorio.
    ReminderPopup { r -> Actions.playLive(mainCtx, listOf(r.channel), 0) }
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
            Spacer(Modifier.width(10.dp))
            androidx.compose.material3.Text(
                BrandName(), color = androidx.compose.ui.graphics.Color.White, fontSize = 19.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, maxLines = 1,
            )
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
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Tab.values().forEach { t ->
                                TopTab(
                                    text = t.label,
                                    // Buscar: solo la lupa.
                                    icon = if (t == Tab.SEARCH) Icons.Filled.Search else null,
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
            // Perfil en uso (solo si hay varios).
            val profPv = com.suso.miiptv.data.Repo.prefs.version
            val profs = remember(profPv) { com.suso.miiptv.data.Repo.prefs.profiles() }
            if (profs.size > 1) {
                ProfileAvatar(com.suso.miiptv.data.Repo.prefs.currentProfile(), 26.dp)
                Spacer(Modifier.width(12.dp))
            }
            // Hora discreta: más pequeña y en gris tenue.
            Clock(fontSize = 14, color = Color(0xFF7C7C7C))
            Spacer(Modifier.width(14.dp))
            // Con la VPN conectada, el círculo del centro de la rueda se pone verde.
            val vpnOn = com.suso.miiptv.vpn.VpnManager.state == com.suso.miiptv.vpn.VpnManager.State.ON
            TopIcon(Icons.Filled.Settings, "Ajustes", "tab_settings", centerDot = if (vpnOn) Color(0xFF4FD65A) else null) { UiState.push(Screen.Settings) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.divider))
    }
}

/** Hora en la esquina, como en las apps de TV. */
@Composable
fun Clock(fontSize: Int = 17, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFB4B4B4)) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000 + 50)
        }
    }
    androidx.compose.material3.Text(
        // Mismo estilo que las secciones del menú (Inicio, TV en directo...).
        hm(now), color = color, fontSize = fontSize.sp,
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
    icon: ImageVector? = null,
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
        if (icon != null) {
            // Hueco fijo: el menú no se mueve aunque la lupa crezca un poco con el cursor.
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = text, tint = color, modifier = Modifier.size(if (focused) 28.dp else 25.dp))
            }
        } else {
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
}

/** Icono del menú superior (Ajustes) con el mismo estilo que las secciones. */
@Composable
private fun TopIcon(icon: ImageVector, label: String, focusKey: String, centerDot: Color? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // Igual que las secciones del menú: verde al momento cuando tiene el cursor.
    // El verde claro y el gris tienen casi la misma luminosidad: en un icono fino apenas se nota el cambio.
    // Por eso con el cursor, además de verde, la rueda se agranda un poco y lleva un círculo verde suave detrás.
    val color = if (focused) C.gold else Color(0xFFB4B4B4)
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.15f else 1f, Motion.focusSpec(), label = "gear")
    Box(
        Modifier
            .memFocus(focusKey) { focused = it }
            .tvClick(onClick = onClick)
            .padding(4.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (focused) C.gold.copy(alpha = 0.16f) else Color.Transparent)
            .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(26.dp))
        // Relleno del agujero central de la rueda (el icono mide 24 de lado y el agujero ~7 de diámetro).
        if (centerDot != null) Box(Modifier.size(26.dp * 7.4f / 24f).clip(androidx.compose.foundation.shape.CircleShape).background(centerDot))
    }
}
