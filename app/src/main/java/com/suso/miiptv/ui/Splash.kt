package com.suso.miiptv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val GoldBrush = Brush.linearGradient(listOf(Color(0xFFFAE4A5), Color(0xFFC9963E)))

/** Marca de la app: aro dorado con el triángulo de reproducir. [ring] de 0 a 1 dibuja el aro poco a poco. */
@Composable
fun BrandMark(size: Dp, ring: Float = 1f, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val brush = Brush.linearGradient(listOf(Color(0xFFFAE4A5), Color(0xFFC9963E)), Offset.Zero, Offset(s, s))
        val stroke = s * 0.055f
        drawArc(
            brush, startAngle = -90f, sweepAngle = 360f * ring, useCenter = false,
            topLeft = Offset(stroke / 2 + s * 0.04f, stroke / 2 + s * 0.04f),
            size = androidx.compose.ui.geometry.Size(s * 0.92f - stroke, s * 0.92f - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        val c = s / 2
        val r = s * 0.22f
        val cx = c + s * 0.035f
        val p = Path().apply {
            moveTo(cx - r * 0.62f, c - r)
            lineTo(cx + r * 0.95f, c)
            lineTo(cx - r * 0.62f, c + r)
            close()
        }
        drawPath(p, brush)
        drawPath(p, brush, style = Stroke(s * 0.05f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

private var splashShown = false

/** Pantalla de arranque animada (solo la primera vez que se abre la app en cada sesión). */
@Composable
fun SplashOverlay() {
    val firstTime = remember { !splashShown }
    if (!firstTime) return
    var visible by remember { mutableStateOf(true) }
    val ring = remember { Animatable(0f) }
    val mark = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { mark.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        launch { ring.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        delay(350)
        text.animateTo(1f, tween(550, easing = LinearEasing))
        delay(450)
        splashShown = true
        visible = false
    }
    AnimatedVisibility(visible = visible, enter = androidx.compose.animation.EnterTransition.None, exit = fadeOut(tween(450))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x553D2E10), Color.Transparent),
                        radius = 900f,
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BrandMark(
                    120.dp, ring.value,
                    Modifier.graphicsLayer {
                        val sc = 0.82f + 0.18f * mark.value
                        scaleX = sc
                        scaleY = sc
                        alpha = mark.value
                    },
                )
                Spacer(Modifier.height(26.dp))
                Text(
                    "Mi IPTV",
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.graphicsLayer {
                        alpha = text.value
                        translationY = (1f - text.value) * 24f
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "TV  ·  CINE  ·  SERIES",
                    color = C.gold,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp,
                    modifier = Modifier.graphicsLayer { alpha = text.value * 0.9f },
                )
                Spacer(Modifier.height(30.dp))
                Box(Modifier.width(140.dp).height(2.dp).background(Color(0x22FFFFFF))) {
                    Box(
                        Modifier
                            .width(140.dp * ring.value)
                            .height(2.dp)
                            .background(GoldBrush)
                    )
                }
            }
        }
    }
}
