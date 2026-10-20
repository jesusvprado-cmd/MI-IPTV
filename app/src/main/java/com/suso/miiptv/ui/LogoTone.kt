package com.suso.miiptv.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.pow

/**
 * Detecta los logos oscuros (p. ej. Telecinco o La 1, azul marino) que casi no se ven sobre el fondo negro,
 * para ponerles detrás un fondo claro.
 */
object LogoTone {
    /** Fondo claro para los logos oscuros. */
    val light = Color(0xFFECECEF)

    private val dark = mutableStateMapOf<String, Boolean>()

    fun isDark(url: String?): Boolean = url != null && dark[url.substringBefore("#b=")] == true

    private fun lin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun analyze(url: String, bmp: Bitmap) {
        if (dark.containsKey(url)) return
        val result = try {
            val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
            val bg = lin(0x1C)
            var opaque = 0
            var visible = 0
            for (y in 0 until small.height) for (x in 0 until small.width) {
                val p = small.getPixel(x, y)
                if ((p ushr 24) < 110) continue
                opaque++
                val l = 0.2126 * lin((p shr 16) and 0xFF) + 0.7152 * lin((p shr 8) and 0xFF) + 0.0722 * lin(p and 0xFF)
                if ((l + 0.05) / (bg + 0.05) >= 3.0) visible++
            }
            if (small !== bmp) small.recycle()
            // Si menos de un tercio del logo destaca sobre el fondo oscuro, se considera oscuro.
            opaque > 20 && visible.toDouble() / opaque < 0.35
        } catch (e: Exception) {
            false
        }
        dark[url] = result
    }
}

/**
 * Imagen de logo de canal que avisa a [LogoTone] para saber si es oscuro.
 * Si el logo de calidad no carga, se usa el de la lista ([fallback]) en lugar de dejar el recuadro vacío.
 */
@Composable
fun ChannelLogoImage(url: String, modifier: Modifier = Modifier, fallback: String? = null, onModel: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    // «…#b=2» indica un número (o «UHD») que la app dibuja sobre el logo principal.
    val badge = remember(url) {
        url.substringAfter("#b=", "").substringBefore("&c=").takeIf { it.isNotEmpty() }?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    }
    // «&c=RRGGBB»: el número va en ese color, sin recuadro (como en el propio logo).
    val badgeColor = remember(url) {
        url.substringAfter("&c=", "").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
    }
    val clean = remember(url) { url.substringBefore("#b=") }
    var model by remember(url) { mutableStateOf(clean) }
    Box(modifier) {
        AsyncImage(
            model = remember(model) { ImageRequest.Builder(ctx).data(model).allowHardware(false).build() },
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { s ->
                val d = s.result.drawable
                if (d is BitmapDrawable) d.bitmap?.let { LogoTone.analyze(model, it) }
                onModel(model)
            },
            onError = {
                if (model == clean && fallback != null && fallback != clean) model = fallback
            },
        )
        if (badge != null && model == clean) {
            if (badgeColor != null) {
                Text(
                    badge, color = badgeColor, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 2.dp),
                )
            } else {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF0B1E4D))
                        .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(badge, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}
