package com.suso.miiptv.ui

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
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun Logo(dp: Dp) {
    Canvas(Modifier.size(dp)) {
        drawCircle(C.gold)
        val w = size.width
        val p = Path().apply {
            moveTo(w * 0.40f, w * 0.29f)
            lineTo(w * 0.73f, w * 0.50f)
            lineTo(w * 0.40f, w * 0.71f)
            close()
        }
        drawPath(p, Color.Black)
    }
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
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
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
            .memFocus(focusKey) { focused = it }
            .tvClick(onClick = onClick)
            .clip(shape)
            .background(if (focused) Color.White else Color.Transparent)
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
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "btn")
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
    onFocus: ((Boolean) -> Unit)? = null,
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "poster")
    val shape = RoundedCornerShape(8.dp)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .memFocus(focusKey) {
                    focused = it
                    onFocus?.invoke(it)
                }
                .tvClick(onLong, onClick)
                .clip(shape)
                .background(C.card)
                .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
        ) {
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
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
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "chan")
    val shape = RoundedCornerShape(8.dp)
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
                .background(C.card)
                .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                )
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
            .background(
                when {
                    focused -> Color.White
                    selected -> C.sel
                    else -> Color.Transparent
                }
            )
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
