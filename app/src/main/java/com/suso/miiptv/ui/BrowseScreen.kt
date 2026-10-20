package com.suso.miiptv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suso.miiptv.data.Category
import com.suso.miiptv.data.EpgEntry
import com.suso.miiptv.data.Item
import com.suso.miiptv.data.Kind
import com.suso.miiptv.data.Repo
import kotlinx.coroutines.delay

const val FAV = "__fav"
const val RECENT = "__recent"

/** Pantallas "TV en directo", "Películas" y "Series": categorías a la izquierda y cuadrícula a la derecha. */
@Composable
fun BrowseScreen(kind: Kind) {
    val ctx = LocalContext.current
    val st = UiState.browseState(kind)
    val dv = Repo.dataVersion
    val pv = Repo.prefs.version
    val hv = Repo.prefs.hiddenVersion

    var cats by remember(kind, dv) { mutableStateOf<List<Category>?>(null) }
    var catErr by remember(kind, dv) { mutableStateOf<String?>(null) }
    LaunchedEffect(kind, dv, hv) {
        try {
            cats = Repo.categories(kind)
        } catch (e: Exception) {
            catErr = e.message ?: "No se pudieron cargar las categorías"
        }
    }

    val ce = catErr
    if (ce != null) {
        ErrorBox(ce) { Repo.reload() }
        return
    }
    val list = cats
    if (list == null) {
        if (kind == Kind.LIVE) Loading() else SkeletonGrid(kind)
        return
    }

    val specials = if (kind == Kind.LIVE) listOf(Category(FAV, "Favoritos"))
    else listOf(Category(FAV, "Favoritos"), Category(RECENT, "Últimos añadidos"))
    val allCats = specials + list
    val sel = st.selected?.takeIf { s -> s == FAV || s == RECENT || list.any { it.id == s } }
        ?: (list.firstOrNull()?.id ?: FAV)

    // Con el mando, la categoría se elige al pasar por encima (con una pequeña pausa).
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        delay(400)
        if (st.selected != p) st.selected = p
    }

    var content by remember(kind) { mutableStateOf(if (st.loadedSel == sel) st.lastItems else null) }
    var contentErr by remember(kind) { mutableStateOf<String?>(null) }
    LaunchedEffect(kind, sel, dv, hv, if (sel == FAV) pv else -1) {
        val changed = st.loadedSel != sel
        if (changed) content = null
        contentErr = null
        val res: List<Item> = try {
            when (sel) {
                FAV -> Repo.favorites(kind)
                RECENT -> Repo.latest(kind)
                else -> Repo.items(kind, sel)
            }
        } catch (e: Exception) {
            contentErr = e.message ?: "No se pudo cargar el contenido"
            emptyList()
        }
        if (changed && st.loadedSel != null) st.grid = LazyGridState()
        st.loadedSel = sel
        st.lastItems = res
        content = res
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 700.dp
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                state = st.cats,
                modifier = Modifier.width(if (narrow) 140.dp else 290.dp).fillMaxHeight(),
                contentPadding = PaddingValues(start = if (narrow) 6.dp else 22.dp, end = 6.dp, top = 14.dp, bottom = 24.dp),
            ) {
                itemsIndexed(allCats, key = { i, c -> "$i:${c.id}" }) { _, c ->
                    CategoryRow(
                        name = c.name,
                        selected = c.id == sel,
                        focusKey = "cat_${kind.name}_${c.id}",
                        onFocused = { pending = c.id },
                    ) {
                        pending = null
                        st.selected = c.id
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (kind == Kind.LIVE && !narrow) LiveHeader(st.focused)
                val its = content
                val ie = contentErr
                when {
                    its == null -> SkeletonGrid(kind)
                    ie != null && its.isEmpty() -> ErrorBox(ie) { Repo.reload() }
                    its.isEmpty() -> EmptyText(
                        if (sel == FAV) "Aún no tienes favoritos aquí.\nAñádelos con el botón ♥ de la ficha o manteniendo pulsado OK."
                        else "No hay contenido en esta categoría"
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (kind == Kind.LIVE) 150.dp else 128.dp),
                        state = st.grid,
                        modifier = Modifier.fillMaxSize().fadeInOnChange(its),
                        contentPadding = PaddingValues(start = 14.dp, end = 30.dp, top = 14.dp, bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        itemsIndexed(its, key = { i, x -> "$i:${x.key}" }) { i, x ->
                            val fk = "g_${kind.name}_${sel}_${x.key}"
                            if (kind == Kind.LIVE) {
                                ChannelCard(
                                    x.name, x.image, fk,
                                    onFocus = { f -> if (f) st.focused = x },
                                    onLong = { Actions.toggleFav(ctx, x) },
                                ) { Actions.playLive(ctx, its, i, fromPreview = !narrow) }
                            } else {
                                PosterCard(
                                    x.name, x.image, fk,
                                    item = x,
                                    onLong = { Actions.toggleFav(ctx, x) },
                                ) { Actions.open(x) }
                            }
                        }
                    }
                }
            }
        }
    }
}
