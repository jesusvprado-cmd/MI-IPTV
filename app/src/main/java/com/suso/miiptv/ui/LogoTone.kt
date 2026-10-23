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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
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
    /** Fondo para los logos oscuros: blanco, como el resto de logos sobre blanco (antes gris claro). */
    val light = Color.White

    private val dark = mutableStateMapOf<String, Boolean>()

    fun isDark(url: String?): Boolean = url != null && dark[url.substringBefore('#')] == true

    /** Logos marcados para verse sobre fondo blanco («…#w=1»). */
    fun wantsWhite(url: String?): Boolean = url != null && url.substringAfter('#', "").split('&').contains("w=1")

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
 * Retoca los colores de un logo:
 *  - «swap»: logos de dos colores (p. ej. rojo con letras blancas) → se intercambian: lo de color pasa a blanco y lo blanco al color.
 *  - «k»: lo blanco/gris pasa a negro (y lo negro a blanco); lo que tiene color se queda igual.
 */
class LogoRecolor(private val mode: String) : coil.transform.Transformation {
    override val cacheKey: String = "recolor2-$mode"

    override suspend fun transform(input: Bitmap, size: coil.size.Size): Bitmap {
        val w = input.width
        val h = input.height
        val px = IntArray(w * h)
        input.getPixels(px, 0, w, 0, 0, w, h)
        fun sat(p: Int): Int {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            return maxOf(r, g, b) - minOf(r, g, b)
        }
        if (mode == "swap") {
            // Color principal: la media de los píxeles opacos con color.
            var n = 0L; var sr = 0L; var sg = 0L; var sb = 0L
            for (p in px) if ((p ushr 24) > 200 && sat(p) > 80) { n++; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
            if (n == 0L) return input
            val cr = (sr / n).toInt(); val cg = (sg / n).toInt(); val cb = (sb / n).toInt()
            val dr = 255 - cr; val dg = 255 - cg; val db = 255 - cb
            val len = (dr * dr + dg * dg + db * db).toFloat().coerceAtLeast(1f)
            for (i in px.indices) {
                val p = px[i]
                val a = p ushr 24
                if (a == 0) continue
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                // t = 0 en el color, 1 en el blanco.
                val t = (((r - cr) * dr + (g - cg) * dg + (b - cb) * db) / len).coerceIn(0f, 1f)
                // Se intercambian: color → blanco, blanco → color.
                val nr = (255 + (cr - 255) * t).toInt(); val ng = (255 + (cg - 255) * t).toInt(); val nb = (255 + (cb - 255) * t).toInt()
                px[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        } else if (mode == "k") {
            // Fondo del logo (el color más repetido en el borde): se quita, para que quede solo el logo sobre el blanco.
            val border = HashMap<Int, Int>()
            for (x in 0 until w) { border.merge(px[x] or 0x0F0F0F, 1, Int::plus); border.merge(px[(h - 1) * w + x] or 0x0F0F0F, 1, Int::plus) }
            for (y in 0 until h) { border.merge(px[y * w] or 0x0F0F0F, 1, Int::plus); border.merge(px[y * w + w - 1] or 0x0F0F0F, 1, Int::plus) }
            val bg = border.maxByOrNull { it.value }?.key ?: 0
            val bgOpaque = (bg ushr 24) > 200
            val br = (bg shr 16) and 0xFF; val bgG = (bg shr 8) and 0xFF; val bb = bg and 0xFF
            for (i in px.indices) {
                val p = px[i]
                if ((p ushr 24) < 40) { px[i] = 0; continue }
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                if (bgOpaque && kotlin.math.abs(r - br) + kotlin.math.abs(g - bgG) + kotlin.math.abs(b - bb) < 60) { px[i] = 0; continue }
                if (sat(p) > 60) continue
                // Blanco/gris → negro.
                px[i] = (p and 0xFF000000.toInt()) or (p.inv() and 0x00FFFFFF)
            }
            // Se recorta al logo para que se vea más grande.
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            for (y in 0 until h) for (x in 0 until w) {
                val p = px[y * w + x]
                if ((p ushr 24) < 40) continue
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            if (x1 > x0 && y1 > y0) {
                val m = ((x1 - x0) * 0.03f).toInt()
                x0 = (x0 - m).coerceAtLeast(0); y0 = (y0 - m).coerceAtLeast(0)
                x1 = (x1 + m).coerceAtMost(w - 1); y1 = (y1 + m).coerceAtMost(h - 1)
                val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                full.setPixels(px, 0, w, 0, 0, w, h)
                return Bitmap.createBitmap(full, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
            }
        } else return input
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }
}

/**
 * Imagen de logo de canal que avisa a [LogoTone] para saber si es oscuro.
 * Si el logo de calidad no carga, se usa el de la lista ([fallback]) en lugar de dejar el recuadro vacío.
 */
@Composable
fun ChannelLogoImage(url: String, modifier: Modifier = Modifier, fallback: String? = null, onModel: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    // Detrás de «#» van ajustes de la app: b=número a dibujar, c=color del número, w=1 fondo blanco.
    val params = remember(url) {
        url.substringAfter('#', "").split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }
    val badge = remember(url) { params["b"]?.takeIf { it.isNotEmpty() }?.let { java.net.URLDecoder.decode(it, "UTF-8") } }
    val badgeColor = remember(url) { params["c"]?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } }
    val clean = remember(url) { url.substringBefore('#') }
    var model by remember(url) { mutableStateOf(clean) }
    // Copia del mismo logo en otro servidor (jsDelivr): algunas redes (p. ej. datos móviles en días de fútbol)
    // bloquean raw.githubusercontent.com y el logo bueno no carga.
    val mirror = remember(url) { githubMirror(clean) }
    // ¿Se está mostrando el logo bueno (original o su copia), o el de la lista?
    val primary = model == clean || model == mirror
    // Número de color (Liga de Campeones): va a la izquierda del logo, sin taparlo.
    val cornerNumber = params["p"] == "br"
    val sideNumber = badge != null && badgeColor != null && primary && !cornerNumber
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (sideNumber) {
            Text(
                badge!!, color = badgeColor!!, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    Box(Modifier.weight(1f).fillMaxHeight()) {
        AsyncImage(
            model = remember(model) {
                ImageRequest.Builder(ctx).data(model).allowHardware(false).apply {
                    // Retoque de color pedido para el logo de la lista (solo al de la URL original).
                    params["x"]?.let { if (model == clean || model == mirror) transformations(LogoRecolor(it)) }
                }.build()
            },
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { s ->
                val d = s.result.drawable
                val key = if (model == mirror) clean else model
                if (d is BitmapDrawable) d.bitmap?.let { LogoTone.analyze(key, it) }
                onModel(key)
            },
            onError = { err ->
                // Una vez por servidor: queda en el registro de errores qué servidor de logos no responde.
                val host = runCatching { android.net.Uri.parse(model).host }.getOrNull()
                if (host != null && logoHostsFailed.add(host)) {
                    com.suso.miiptv.data.ErrorLog.add("Logo sin cargar desde $host: ${err.result.throwable.message ?: err.result.throwable.javaClass.simpleName}")
                }
                when {
                    model == clean && mirror != null -> model = mirror
                    (model == clean || model == mirror) && fallback != null && fallback != clean -> model = fallback
                }
            },
        )
        if (badge != null && primary && badgeColor != null && cornerNumber) {
            // Número de color en la esquina inferior derecha, sin recuadro (Liga RFEF).
            Text(
                badge, color = badgeColor, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 18.sp,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        if (badge != null && primary && badgeColor == null) {
            run {
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
}

/** Misma imagen de GitHub servida por jsDelivr (por si la red bloquea raw.githubusercontent.com). */
internal fun githubMirror(url: String): String? {
    val m = Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$").find(url) ?: return null
    val (user, repo, branch, path) = m.destructured
    return "https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path"
}

private val logoHostsFailed = java.util.Collections.synchronizedSet(HashSet<String>())
