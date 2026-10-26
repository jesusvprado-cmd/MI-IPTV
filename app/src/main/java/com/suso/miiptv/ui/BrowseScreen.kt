package com.suso.miiptv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
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
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
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
        if (changed && st.loadedSel != null) {
            st.grid = LazyGridState()
            st.lastIdx = -1
        }
        st.loadedSel = sel
        st.lastItems = res
        content = res
    }

    // ▶ desde una categoría lleva siempre a la cuadrícula: a la última tarjeta usada si se ve, o a la primera visible.
    // (Se calcula solo al pulsar ▶, sin redibujar la pantalla en cada movimiento.)
    val ready = content?.isNotEmpty() == true
    fun gridTarget(): FocusRequester {
        if (!ready) return FocusRequester.Cancel
        val visible = st.grid.layoutInfo.visibleItemsInfo.map { it.index }
        val i = if (st.lastIdx in visible) st.lastIdx else visible.firstOrNull()
        return i?.let { st.reqs[it] } ?: FocusRequester.Default
    }

    // ¿Está el cursor en la lista de categorías? (para marcar solo una categoría a la vez)
    var catsFocused by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 700.dp
        // Móvil en vertical: categorías en una fila deslizable arriba en vez de la columna de la izquierda.
        val chips = com.suso.miiptv.data.Device.touch && maxWidth < 600.dp
        Row(Modifier.fillMaxSize()) {
            if (!chips) LazyColumn(
                state = st.cats,
                modifier = Modifier.width(if (narrow) 140.dp else 290.dp).fillMaxHeight()
                    .onFocusChanged { catsFocused = it.hasFocus },
                contentPadding = PaddingValues(start = if (narrow) 6.dp else 22.dp, end = 6.dp, top = 14.dp, bottom = 24.dp),
            ) {
                itemsIndexed(allCats, key = { i, c -> "$i:${c.id}" }) { _, c ->
                    val cfr = remember { FocusRequester() }
                    DisposableEffect(c.id, cfr) {
                        st.catReqs[c.id] = cfr
                        onDispose { if (st.catReqs[c.id] === cfr) st.catReqs.remove(c.id) }
                    }
                    Box(Modifier.focusRequester(cfr).focusProperties { right = gridTarget() }) {
                    CategoryRow(
                        name = c.name,
                        selected = c.id == sel,
                        focusKey = "cat_${kind.name}_${c.id}",
                        onFocused = { pending = c.id },
                        cursorInMenu = catsFocused,
                    ) {
                        pending = null
                        st.selected = c.id
                    }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (chips) CategoryChips(allCats, sel) { id -> st.selected = id }
                if (kind == Kind.LIVE && !narrow && !com.suso.miiptv.data.Device.touch) LiveHeader(st.focused) // en táctil, sin previsualización: más sitio para los canales
                val its = content
                val ie = contentErr
                when {
                    its == null -> SkeletonGrid(kind)
                    ie != null && its.isEmpty() -> ErrorBox(ie) { Repo.reload() }
                    its.isEmpty() -> EmptyText(
                        if (sel == FAV) "Aún no tienes favoritos aquí.\nAñádelos con el botón ♥ de la ficha o manteniendo pulsado OK."
                        else "No hay contenido en esta categoría"
                    )
                    else -> CompositionLocalProvider(LocalBringIntoViewSpec provides RevealBringIntoView) {
                    val tvGrid = !com.suso.miiptv.data.Device.touch
                    // Móvil: canales 3 por fila, con los márgenes iguales a ambos lados.
                    val compact = isCompactPhone()
                    val phoneGrid = kind == Kind.LIVE && compact
                    LazyVerticalGrid(
                        // Canales pequeños para ver más de un vistazo (en la tele, 6 por fila).
                        columns = if (phoneGrid) GridCells.Fixed(3)
                        else GridCells.Adaptive(if (kind == Kind.LIVE) (if (tvGrid) 92.dp else 112.dp) else 128.dp),
                        state = st.grid,
                        modifier = Modifier
                            .fillMaxSize()
                            // ◀ desde la cuadrícula vuelve a la categoría elegida, no a la que quede a la misma altura.
                            .focusProperties {
                                exit = { d -> if (d == FocusDirection.Left) (st.catReqs[sel] ?: FocusRequester.Default) else FocusRequester.Default }
                            }
                            .focusGroup()
                            .fadeInOnChange(its),
                        contentPadding = if (compact) PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 40.dp)
                        else PaddingValues(start = 14.dp, end = 30.dp, top = 14.dp, bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (phoneGrid) 10.dp else if (compact) 14.dp else if (kind == Kind.LIVE) (if (tvGrid) 12.dp else 14.dp) else 18.dp),
                        verticalArrangement = Arrangement.spacedBy(if (phoneGrid) 10.dp else if (kind == Kind.LIVE) (if (tvGrid) 12.dp else 14.dp) else 20.dp),
                    ) {
                        itemsIndexed(its, key = { i, x -> "$i:${x.key}" }) { i, x ->
                            val fk = "g_${kind.name}_${sel}_${x.key}"
                            val fr = remember { FocusRequester() }
                            DisposableEffect(i, fr) {
                                st.reqs[i] = fr
                                onDispose { if (st.reqs[i] === fr) st.reqs.remove(i) }
                            }
                            val m = Modifier.focusRequester(fr)
                            if (kind == Kind.LIVE) {
                                ChannelCard(
                                    x.name, x.image, fk, m,
                                    onFocus = { f -> if (f) { st.focused = x; st.lastIdx = i } },
                                    onLong = { Actions.toggleFav(ctx, x) },
                                    nameSize = if (tvGrid) (Sz.body.value - 1.5f).sp else if (phoneGrid) Sz.small else Sz.body,
                                ) { Actions.playLive(ctx, its, i, fromPreview = !narrow) }
                            } else {
                                PosterCard(
                                    x.name, x.image, fk, m,
                                    item = x,
                                    onFocus = { f -> if (f) st.lastIdx = i },
                                    onLong = { Actions.toggleFav(ctx, x) },
                                    favBadge = sel != FAV,
                                ) { Actions.open(x) }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}


/** Categorías como fila de «píldoras» deslizable (móvil). */
@Composable
fun CategoryChips(cats: List<Category>, selected: String?, onPick: (String) -> Unit) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.foundation.lazy.LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items(cats.size) { i ->
            val c = cats[i]
            val sel = c.id == selected
            Text(
                c.name, maxLines = 1, fontSize = Sz.small,
                color = if (sel) Color.Black else Color(0xFFDDDDDD),
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(if (sel) C.gold else Color(0xFF1F2A21))
                    .clickable { onPick(c.id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
