package com.suso.miiptv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

/**
 * Recuerda qué elemento tenía el foco del mando para devolverlo
 * al volver atrás desde una ficha.
 */
object FocusMemory {
    var last: String? = null
    /** El que tenía el foco antes de [last]. */
    var prev: String? = null
    var restore: String? = null

    /** Elementos con [memFocus] que están en pantalla, por su clave (para devolverles el foco). */
    val requesters = HashMap<String, FocusRequester>()

    /** Devuelve el foco al elemento con esa clave si sigue en pantalla. */
    fun focus(key: String?): Boolean {
        val fr = key?.let { requesters[it] } ?: return false
        return runCatching { fr.requestFocus() }.isSuccess
    }
}

/** Va siempre ANTES de tvClick en la cadena de modificadores. */
@Composable
fun Modifier.memFocus(key: String, onFocus: ((Boolean) -> Unit)? = null): Modifier {
    val fr = remember { FocusRequester() }
    DisposableEffect(key, fr) {
        FocusMemory.requesters[key] = fr
        onDispose { if (FocusMemory.requesters[key] === fr) FocusMemory.requesters.remove(key) }
    }
    LaunchedEffect(key) {
        if (FocusMemory.restore == key) {
            FocusMemory.restore = null
            delay(40)
            runCatching { fr.requestFocus() }
        }
    }
    return this
        .focusRequester(fr)
        .onFocusChanged { s ->
            if (s.isFocused && FocusMemory.last != key) {
                FocusMemory.prev = FocusMemory.last
                FocusMemory.last = key
            }
            onFocus?.invoke(s.isFocused)
        }
}

/** Pulsación con el mando (OK) o el dedo, sin efecto de onda. Mantener pulsado = onLongClick. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvClick(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier =
    this.combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onLongClick = onLongClick,
        onClick = onClick,
    )
