package com.suso.miiptv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.suso.miiptv.data.Item
import com.suso.miiptv.data.Logos
import com.suso.miiptv.data.Repo
import kotlinx.coroutines.delay

@Composable
fun Logo(dp: Dp) {
    BrandMark(dp)
}

/** Pestaña del menú superior: con borde si está elegida, blanca si tiene el foco. */
@Composable
fun TabChip(
    text: String,
    selected: Boolean,
    focusKey: String,
    focusRequester: FocusRequester? = null,
    requestInitial: Boolean = false,
    icon: ImageVector? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(if (focused) Color.White else Color.Transparent, Motion.focusSpec(), label = "tabbg")
    val fr = focusRequester ?: remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (requestInitial) {
            delay(80)
            runCatching { fr.requestFocus() }
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .focusRequester(fr)
            .memFocus(focusKey) {
                focused = it
                onFocusChange?.invoke(it)
            }
            .tvClick(onClick = onClick)
            .clip(shape)
            .background(bgColor)
            .then(if (selected && !focused) Modifier.border(1.5.dp, Color.White, shape) else Modifier)
            .padding(horizontal = if (icon != null) 10.dp else 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = text, tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(24.dp))
        }
        if (text.isNotEmpty() && icon == null) {
            Text(
                text,
                color = if (focused) Color.Black else Color.White,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/** Botón de las fichas (REPRODUCIR, corazón, TRÁILER...). */
@Composable
fun TvButton(
    text: String?,
    focusKey: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val bg = when {
        focused -> Color.White
        filled -> C.playBtn
        else -> Color.Transparent
    }
    val fg = if (focused || filled) Color.Black else Color.White
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, Motion.focusSpec(), label = "btn")
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .memFocus(focusKey) { focused = it }
            .tvClick(onClick = onClick)
            .clip(shape)
            .background(bg)
            .then(if (!filled && !focused) Modifier.border(2.dp, Color.White.copy(alpha = 0.85f), shape) else Modifier)
            .padding(horizontal = if (text == null) 14.dp else 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = text, tint = fg, modifier = Modifier.size(24.dp))
        if (icon != null && text != null) Spacer(Modifier.width(8.dp))
        if (text != null) Text(text.uppercase(), color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
    }
}

/** Carátula vertical de película o serie. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    title: String,
    image: String?,
    focusKey: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    subtitle: String? = null,
    item: Item? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var loaded by remember(image) { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, Motion.focusSpec(), label = "poster")
    val borderColor by animateColorAsState(if (focused) Color.White else Color.Transparent, Motion.focusSpec(), label = "pb")
    val shape = RoundedCornerShape(8.dp)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .memFocus(focusKey) {
                    focused = it
                    if (it && image != null) Ambient.image = image
                    onFocus?.invoke(it)
                }
                .tvClick(onLong, onClick)
                .clip(shape)
                .background(C.card)
                .border(2.dp, borderColor, shape)
        ) {
            if (image != null) {
                if (!loaded) Box(Modifier.fillMaxSize().shimmer(RoundedCornerShape(0.dp)))
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onState = { st -> loaded = st !is AsyncImagePainter.State.Loading && st !is AsyncImagePainter.State.Empty },
                )
                // Degradado inferior para que las insignias se lean sobre cualquier carátula.
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.3f)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
                )
            } else {
                Text(
                    title,
                    color = C.text2,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
            }
            PosterBadges(item)
            if (progress != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x99000000))
                ) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(C.gold))
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            title,
            color = if (focused) Color.White else C.text3,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = if (focused) Modifier.basicMarquee() else Modifier,
        )
        if (subtitle != null) {
            Text(subtitle, color = C.text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Tarjeta de canal: logo sobre gris oscuro y nombre debajo. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelCard(
    title: String,
    image: String?,
    focusKey: String,
    modifier: Modifier = Modifier,
    onFocus: ((Boolean) -> Unit)? = null,
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, Motion.focusSpec(), label = "chan")
    val borderColor by animateColorAsState(if (focused) Color.White else Color.Transparent, Motion.focusSpec(), label = "cb")
    val shape = RoundedCornerShape(8.dp)
    // Logo en alta calidad si se encuentra; el nombre de debajo se deja tal cual.
    val sv = Repo.prefs.settingsVersion
    val lv = Logos.version
    val logo = remember(title, image, sv, lv) { Logos.url(title, image) }
    // Los logos oscuros (Telecinco, La 1...) van sobre un fondo claro para que se vean bien.
    var shownLogo by remember(logo) { mutableStateOf(logo) }
    val darkLogo = LogoTone.isDark(shownLogo)
    val whiteLogo = LogoTone.wantsWhite(logo) && shownLogo?.substringBefore('#') == logo?.substringBefore('#')
    val cardBg by animateColorAsState(
        when {
            whiteLogo -> Color.White
            darkLogo -> LogoTone.light
            else -> C.card
        },
        Motion.focusSpec(), label = "cbg",
    )
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10.5f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .memFocus(focusKey) {
                    focused = it
                    onFocus?.invoke(it)
                }
                .tvClick(onLong, onClick)
                .clip(shape)
                .background(cardBg)
                .border(2.dp, if ((darkLogo || whiteLogo) && focused) C.gold else borderColor, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                ChannelLogoImage(logo, Modifier.fillMaxSize().padding(14.dp), fallback = image) { shownLogo = it }
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color(0xFF4A4A4A), modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            title,
            color = if (focused) Color.White else C.text3,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = if (focused) Modifier.basicMarquee() else Modifier,
        )
    }
}

/** Fila de la columna de categorías de la izquierda. */
@Composable
fun CategoryRow(name: String, selected: Boolean, focusKey: String, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val rowBg by animateColorAsState(
        when {
            focused -> Color.White
            selected -> C.sel
            else -> Color.Transparent
        },
        Motion.focusSpec(),
        label = "catbg",
    )
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .memFocus(focusKey) {
                focused = it
                if (it) onFocused()
            }
            .tvClick(onClick = onClick)
            .clip(shape)
            .background(rowBg)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Text(
            name,
            color = when {
                focused -> Color.Black
                selected -> Color.White
                else -> Color(0xFFCFCFCF)
            },
            fontSize = 15.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Loading(text: String = "Cargando…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = C.gold)
            Spacer(Modifier.height(14.dp))
            Text(text, color = C.text2, fontSize = 15.sp)
        }
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(message, color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center)
            TvButton("Reintentar", "err_retry", filled = true, onClick = onRetry)
        }
    }
}

@Composable
fun EmptyText(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = C.text2, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}
