package com.suso.miiptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suso.miiptv.data.Item
import com.suso.miiptv.data.Kind
import com.suso.miiptv.data.Repo
import com.suso.miiptv.player.PlayQueue
import kotlinx.coroutines.delay

/** Menú al mantener pulsado un canal, película o serie: favorito, guía, ficha u otro reproductor. */
object ItemMenu {
    var item by mutableStateOf<Item?>(null)
        private set

    /** Película o episodio de «Continuar viendo». */
    var progress by mutableStateOf<com.suso.miiptv.data.Prefs.Progress?>(null)
        private set

    fun open(x: Item) {
        progress = null
        item = x
    }

    fun open(p: com.suso.miiptv.data.Prefs.Progress) {
        item = null
        progress = p
    }

    fun close() {
        item = null
        progress = null
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ItemMenuHost() {
    val x = ItemMenu.item
    val p = ItemMenu.progress
    if (x == null && p == null) return
    val key = x?.key ?: p!!.play.key
    val title = x?.name ?: p!!.play.title
    val ctx = LocalContext.current
    val fr = remember(key) { FocusRequester() }
    // Con el mando, el menú se abre manteniendo OK: al soltarlo, ese OK no debe pulsar la primera opción.
    var armed by remember(key) { mutableStateOf(false) }
    BackHandler { ItemMenu.close() }
    LaunchedEffect(key) {
        delay(80)
        runCatching { fr.requestFocus() }
    }
    val pv = Repo.prefs.version
    val fav = remember(pv, key) { x != null && Repo.prefs.isFavorite(key) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            // Tocar fuera cierra el menú.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { ItemMenu.close() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(16.dp))
                .background(C.card)
                .border(1.dp, C.gold.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 22.dp, vertical = 20.dp)
                // El cursor no se escapa a la pantalla de detrás mientras está abierto.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .onPreviewKeyEvent { e ->
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (!armed && ok) {
                        if (e.type == KeyEventType.KeyUp) armed = true
                        true
                    } else false
                }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = Color.White, fontSize = Sz.sub, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p != null) {
                p.play.subtitle?.let { Text(it, color = C.text2, fontSize = Sz.small, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TvButton(if (p.pos > 0) "Continuar" else "Ver", "im_resume", icon = Icons.Filled.PlayArrow, vertical = true, focusRequester = fr) {
                    ItemMenu.close()
                    Actions.resume(ctx, p)
                }
                TvButton("Marcar como visto", "im_seen", vertical = true) {
                    Repo.prefs.setWatched(p.play.key, true)
                    Repo.prefs.removeProgress(p.play.key)
                    ItemMenu.close()
                }
                TvButton("Quitar de «Continuar viendo»", "im_remove", vertical = true) {
                    Repo.prefs.removeProgress(p.play.key)
                    ItemMenu.close()
                }
                TvButton("Ver con otro reproductor", "im_ext", vertical = true) {
                    ItemMenu.close()
                    PlayQueue.openExternal(ctx, p.play, p.pos)
                }
                TvButton("Cancelar", "im_cancel", vertical = true) { ItemMenu.close() }
                return@Column
            }
            x!!
            TvButton(
                if (fav) "Quitar de favoritos" else "Añadir a favoritos", "im_fav",
                icon = if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                iconTint = if (fav) C.fav else null, vertical = true, focusRequester = fr,
            ) {
                Actions.toggleFav(ctx, x)
                ItemMenu.close()
            }
            if (x.kind == Kind.LIVE) {
                TvButton("Ver su programación en la guía", "im_guide", vertical = true) {
                    ItemMenu.close()
                    GuideState.sel = x.categoryId.ifEmpty { null } ?: GuideState.sel
                    UiState.tab = Tab.GUIDE
                }
            } else {
                TvButton("Ver la ficha", "im_open", vertical = true) {
                    ItemMenu.close()
                    Actions.open(x)
                }
            }
            if (x.kind != Kind.SERIES) {
                TvButton("Ver con otro reproductor", "im_ext", vertical = true) {
                    ItemMenu.close()
                    PlayQueue.openExternal(ctx, Repo.playItem(x))
                }
            }
            TvButton("Cancelar", "im_cancel", vertical = true) { ItemMenu.close() }
        }
    }
}
