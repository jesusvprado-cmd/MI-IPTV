package com.suso.miiptv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Colores sacados de las capturas: negro puro, grises y dorado. */
object C {
    val bg = Color(0xFF000000)
    val sel = Color(0xFF1E1E1E)
    val card = Color(0xFF262626)
    val text2 = Color(0xFFA0A0A0)
    val text3 = Color(0xFFDDDDDD)
    val gold = Color(0xFFE8C46A)
    val divider = Color(0xFF3A3A3A)
    val playBtn = Color(0xFFDDF3F2)
    val error = Color(0xFFFF6B6B)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.gold,
            onPrimary = Color.Black,
            secondary = C.gold,
            background = C.bg,
            onBackground = Color.White,
            surface = C.bg,
            onSurface = Color.White,
            surfaceVariant = C.card,
            onSurfaceVariant = C.text2,
            outline = Color(0xFF777777),
        ),
        content = content,
    )
}

private val hmFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

fun hm(ms: Long): String = hmFormat.format(Date(ms))

fun dateStr(ms: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(ms))

fun fmtTime(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}
