package com.suso.miiptv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.suso.miiptv.R
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Paleta «pepino»: fondo casi negro con un toque verde, verde claro de acento y verde intenso de marca. */
object C {
    val bg = Color(0xFF050906)
    val sel = Color(0xFF18221A)
    val card = Color(0xFF212B23)
    val text2 = Color(0xFFA0A0A0)
    val text3 = Color(0xFFDDDDDD)
    /** Acento (antes dorado): verde pepino claro. */
    val gold = Color(0xFFA6EE90)
    /** Verde intenso de la marca (icono, etiquetas). */
    val brand = Color(0xFF268332)
    /** Verde de «activado» (favoritos, interruptores). */
    val fav = Color(0xFFA6EE90)
    val divider = Color(0xFF334036)
    val playBtn = Color(0xFFDDF3F2)
    val error = Color(0xFFFF6B6B)
}

/** Tipografía Manrope en toda la app. */
val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
    Font(R.font.manrope_extrabold, FontWeight.Black),
    Font(R.font.manrope_regular, FontWeight.Light),
)

private fun TextStyle.m() = copy(fontFamily = Manrope)

private val AppTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.m(), displayMedium = t.displayMedium.m(), displaySmall = t.displaySmall.m(),
        headlineLarge = t.headlineLarge.m(), headlineMedium = t.headlineMedium.m(), headlineSmall = t.headlineSmall.m(),
        titleLarge = t.titleLarge.m(), titleMedium = t.titleMedium.m(), titleSmall = t.titleSmall.m(),
        bodyLarge = t.bodyLarge.m(), bodyMedium = t.bodyMedium.m(), bodySmall = t.bodySmall.m(),
        labelLarge = t.labelLarge.m(), labelMedium = t.labelMedium.m(), labelSmall = t.labelSmall.m(),
    )
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
        typography = AppTypography,
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
