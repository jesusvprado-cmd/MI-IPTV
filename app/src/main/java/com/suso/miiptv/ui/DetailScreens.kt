package com.suso.miiptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.suso.miiptv.data.Episode
import com.suso.miiptv.data.Item
import com.suso.miiptv.data.Kind
import com.suso.miiptv.data.MovieDetail
import com.suso.miiptv.data.PlayItem
import com.suso.miiptv.data.Prefs
import com.suso.miiptv.data.Repo
import com.suso.miiptv.data.SeriesDetail
import com.suso.miiptv.player.PlayQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------- Piezas comunes ----------------

/** Lo que se muestra en la cabecera de una ficha (la película o serie, o un título relacionado). */
internal data class HeaderInfo(
    val key: String,
    val title: String,
    val meta: String,
    val rating: String?,
    val plot: String?,
    val director: String?,
    val cast: String?,
    val image: String?,
)

private fun plotLines(text: String?): String? = text?.replace("\\n", "\n")

private fun MovieDetail.header() = HeaderInfo(
    key = item.key,
    title = title,
    meta = listOfNotNull(year, duration, genre?.replace(",", " /")).joinToString(" · "),
    rating = rating?.takeIf { it != "0" && it != "0.0" },
    plot = plotLines(plot),
    director = director,
    cast = cast,
    image = backdrop ?: poster ?: item.image,
)

private fun SeriesDetail.header() = HeaderInfo(
    key = item.key,
    title = title,
    meta = listOfNotNull(year, genre?.replace(",", " /")).joinToString(" · "),
    rating = rating?.takeIf { it != "0" && it != "0.0" },
    plot = plotLines(plot),
    director = null,
    cast = cast,
    image = backdrop ?: poster ?: item.image,
)

private fun basicHeader(item: Item) = HeaderInfo(item.key, item.name, "", item.rating, null, null, null, item.image)

internal suspend fun loadHeader(item: Item): HeaderInfo = try {
    if (item.kind == Kind.SERIES) Repo.seriesDetail(item).header() else Repo.movieDetail(item).header()
} catch (e: Exception) {
    basicHeader(item)
}

/** Imagen grande a la derecha que se funde con el negro; cambia con un fundido. */
@Composable
private fun BoxScope.HeroImage(url: String?) {
    Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.5f).fillMaxHeight(0.82f)) {
        Crossfade(targetState = url, animationSpec = tween(400), label = "hero") { u ->
            if (u != null) {
                AsyncImage(model = u, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(C.bg, C.bg.copy(alpha = 0.55f), Color.Transparent, Color.Transparent))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, C.bg.copy(alpha = 0.7f), C.bg))
            )
        )
    }
}

/**
 * Fila de títulos relacionados. Al pasar por encima de uno se avisa con [onPreview]
 * para que la cabecera muestre sus datos sin pulsar OK.
 */
@Composable
private fun RelatedRow(item: Item, onPreview: (Item) -> Unit) {
    var related by remember(item.key) { mutableStateOf<List<Item>>(emptyList()) }
    LaunchedEffect(item.key) {
        if (item.categoryId.isBlank()) return@LaunchedEffect
        related = try {
            Repo.items(item.kind, item.categoryId).filter { it.key != item.key && Repo.visible(it) }.take(30)
        } catch (e: Exception) {
            emptyList()
        }
    }
    if (related.isEmpty()) return
    Column(Modifier.fillMaxWidth().fadeInOnChange(related.size)) {
        Text(
            "Títulos relacionados", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 48.dp, bottom = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(related, key = { i, x -> "$i:${x.key}" }) { _, x ->
                PosterCard(
                    x.name, x.image, "rel_${x.key}", Modifier.width(90.dp),
                    onFocus = { f -> if (f) onPreview(x) },
                ) { Actions.open(x) }
            }
        }
    }
}

/**
 * Cabecera con título, datos y sinopsis. Cambia con un fundido cuando se pasa por un
 * título relacionado. [onPlotOverflow] avisa si la sinopsis no cabe entera.
 */
@Composable
private fun HeaderText(
    info: HeaderInfo,
    titleColor: Color,
    maxPlotLines: Int,
    modifier: Modifier,
    onPlotOverflow: (String, Boolean) -> Unit,
) {
    AnimatedContent(
        targetState = info,
        transitionSpec = { fadeIn(tween(Motion.FADE_MS)) togetherWith fadeOut(tween(Motion.FADE_MS / 2)) },
        contentKey = { it.key },
        label = "header",
        modifier = modifier,
    ) { h ->
        Column(Modifier.fillMaxWidth()) {
            Text(
                h.title, color = titleColor, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                lineHeight = 36.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            if (h.meta.isNotBlank()) Text(h.meta, color = C.text2, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            h.rating?.let {
                Spacer(Modifier.height(2.dp))
                Text("★ $it", color = C.gold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(10.dp))
            h.plot?.let { p -> FittingPlot(p, Modifier.weight(1f, fill = false), maxPlotLines) { o -> onPlotOverflow(h.key, o) } }
            h.director?.let {
                Spacer(Modifier.height(6.dp))
                Text("Dirección: $it", color = C.text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            h.cast?.let {
                Text("Reparto: $it", color = C.text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Sinopsis que ocupa el hueco disponible y se recorta con «…» si no cabe. */
@Composable
private fun FittingPlot(text: String, modifier: Modifier, maxLinesCap: Int, onOverflow: (Boolean) -> Unit) {
    BoxWithConstraints(modifier) {
        val lh = with(LocalDensity.current) { 21.sp.toDp() }
        val lines = if (constraints.hasBoundedHeight) (maxHeight / lh).toInt().coerceIn(1, maxLinesCap) else maxLinesCap
        Text(
            text, color = Color(0xFFE6E6E6), fontSize = 15.sp, lineHeight = 21.sp,
            maxLines = lines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { r -> onOverflow(r.hasVisualOverflow) },
        )
    }
}

/** Panel con la sinopsis completa, el reparto y la dirección. Se cierra con Atrás. */
@Composable
private fun FullPlotPanel(info: HeaderInfo, onClose: () -> Unit) {
    BackHandler { onClose() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { fr.requestFocus() }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xF2000000)).fadeInOnChange(info.key),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 32.dp)
                .focusRequester(fr)
                .onPreviewKeyEvent { e ->
                    // OK no hace nada aquí (se cierra solo con Atrás); se consume para que no llegue a los botones de detrás.
                    if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) return@onPreviewKeyEvent true
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> {
                            scope.launch { scroll.animateScrollBy(180f) }
                            true
                        }
                        Key.DirectionUp -> {
                            scope.launch { scroll.animateScrollBy(-180f) }
                            true
                        }
                        // Izquierda/derecha no salen del panel.
                        Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                }
                .focusable()
                .verticalScroll(scroll),
        ) {
            Text(info.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
            if (info.meta.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(info.meta, color = C.text2, fontSize = 15.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text(info.plot ?: "", color = Color(0xFFEDEDED), fontSize = 17.sp, lineHeight = 25.sp)
            info.director?.let {
                Spacer(Modifier.height(14.dp))
                Text("Dirección: $it", color = C.text2, fontSize = 15.sp)
            }
            info.cast?.let {
                Spacer(Modifier.height(6.dp))
                Text("Reparto: $it", color = C.text2, fontSize = 15.sp, lineHeight = 21.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text("▲▼ desplazar · Atrás para cerrar", color = C.gold, fontSize = 13.sp)
        }
    }
}

/**
 * Lógica común: qué cabecera se ve (la propia o la de un relacionado enfocado)
 * y si la sinopsis propia se ha recortado.
 */
private class HeaderState {
    var previewItem by mutableStateOf<Item?>(null)
    var previewInfo by mutableStateOf<HeaderInfo?>(null)
    var overflow by mutableStateOf(false)
    var fullOpen by mutableStateOf(false)
    val cache = HashMap<String, HeaderInfo>()
    val moreFr = FocusRequester()
}

@Composable
private fun rememberHeaderState(key: String): HeaderState {
    val st = remember(key) { HeaderState() }
    // Al cerrar el panel de la sinopsis, el foco vuelve a «Leer más».
    var wasOpen by remember(key) { mutableStateOf(false) }
    LaunchedEffect(st.fullOpen) {
        if (st.fullOpen) {
            wasOpen = true
        } else if (wasOpen) {
            wasOpen = false
            delay(60)
            runCatching { st.moreFr.requestFocus() }
        }
    }
    LaunchedEffect(st.previewItem?.key) {
        val p = st.previewItem
        if (p == null) {
            st.previewInfo = null
            return@LaunchedEffect
        }
        delay(350)
        val h = st.cache[p.key] ?: loadHeader(p).also { st.cache[p.key] = it }
        if (st.previewItem?.key == p.key) st.previewInfo = h
    }
    return st
}

@Composable
private fun SmallLoading() {
    CircularProgressIndicator(color = C.gold, modifier = Modifier.size(32.dp))
}

// ---------------- Ficha de película ----------------

@Composable
fun MovieDetailScreen(item: Item) {
    val ctx = LocalContext.current
    var d by remember(item.key) { mutableStateOf<MovieDetail?>(null) }
    LaunchedEffect(item.key) {
        d = try {
            Repo.movieDetail(item)
        } catch (e: Exception) {
            MovieDetail(item, item.name, null, null, null, null, item.image, null, null, null, null, null)
        }
    }
    val pv = Repo.prefs.version
    val fav = remember(pv) { Repo.prefs.isFavorite(item.key) }
    val prog = remember(pv) { Repo.prefs.progress(item.key) }
    val playFr = remember { FocusRequester() }
    val restoring = remember { FocusMemory.restore != null }
    val det = d
    LaunchedEffect(det != null) {
        if (det != null && !restoring) {
            delay(60)
            runCatching { playFr.requestFocus() }
        }
    }
    val hs = rememberHeaderState(item.key)
    val own = det?.header() ?: basicHeader(item)
    val shown = hs.previewInfo ?: own
    val previewing = hs.previewInfo != null

    fun play(from: Long) {
        val m = d ?: return
        val sub = listOfNotNull(m.year, m.genre).joinToString(" · ").ifBlank { null }
        try {
            PlayQueue.start(
                ctx,
                listOf(PlayItem(item.key, m.title, sub, m.poster ?: item.image, Repo.streamUrl(m.item), false)),
                0,
                from,
            )
        } catch (e: Exception) {
            Actions.toast(ctx, e.message ?: "No se puede reproducir")
        }
    }

    // Todo cabe en la pantalla: el foco en «Reproducir» no desplaza la ficha ni corta el título.
    Box(Modifier.fillMaxSize().background(C.bg)) {
        HeroImage(shown.image)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val showRelated = maxHeight >= 470.dp
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth(0.62f)
                        .weight(1f)
                        .padding(start = 48.dp, top = 28.dp, end = 16.dp, bottom = 6.dp),
                ) {
                    HeaderText(shown, Color.White, 8, Modifier.weight(1f, fill = false)) { k, o ->
                        if (k == own.key) hs.overflow = o
                    }
                    Spacer(Modifier.height(16.dp))
                    if (det == null) {
                        SmallLoading()
                    } else {
                        Row(
                            Modifier
                                .graphicsLayer { alpha = if (previewing) 0.35f else 1f }
                                .onFocusChanged { if (it.hasFocus) hs.previewItem = null },
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TvButton(
                                if (prog != null) "Continuar ${fmtTime(prog.pos)}" else "Reproducir",
                                "d_play",
                                icon = Icons.Filled.PlayArrow,
                                filled = true,
                                focusRequester = playFr,
                            ) { play(prog?.pos ?: 0L) }
                            if (prog != null) TvButton("Empezar", "d_restart") { play(0L) }
                            if (hs.overflow) TvButton("Leer más", "d_more", focusRequester = hs.moreFr) { hs.fullOpen = true }
                            TvButton(null, "d_fav", icon = if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder) {
                                Actions.toggleFav(ctx, item)
                            }
                            det.trailer?.let { t -> TvButton("Tráiler", "d_trailer") { Actions.openTrailer(ctx, t) } }
                        }
                    }
                }
                if (showRelated) {
                    RelatedRow(item) { x -> hs.previewItem = x }
                    Spacer(Modifier.height(22.dp))
                }
            }
        }
        if (hs.fullOpen) FullPlotPanel(own) { hs.fullOpen = false }
    }
}

// ---------------- Ficha de serie ----------------

private fun epKey(seriesId: String, ep: Episode) = "EP|$seriesId|${ep.id}"

@Composable
fun SeriesDetailScreen(item: Item) {
    val ctx = LocalContext.current
    var d by remember(item.key) { mutableStateOf<SeriesDetail?>(null) }
    var failed by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(item.key) {
        d = try {
            Repo.seriesDetail(item)
        } catch (e: Exception) {
            failed = true
            SeriesDetail(item, item.name, null, null, null, item.image, null, null, null, emptyList())
        }
    }
    // Todas las temporadas empiezan plegadas; se despliegan con OK.
    var open by remember(item.key) { mutableStateOf<Int?>(null) }
    val seasonList = rememberLazyListState()
    val seasonFrs = remember(item.key) { HashMap<Int, FocusRequester>() }
    // Al abrir una temporada: se cierran las demás, la temporada sube arriba de la lista y conserva el foco.
    LaunchedEffect(open) {
        val o = open ?: return@LaunchedEffect
        val idx = d?.seasons?.indexOfFirst { it.first == o } ?: -1
        if (idx < 0) return@LaunchedEffect
        delay(30)
        runCatching { seasonList.animateScrollToItem(idx) }
        runCatching { seasonFrs[o]?.requestFocus() }
    }
    val det = d
    val pv = Repo.prefs.version
    val fav = remember(pv) { Repo.prefs.isFavorite(item.key) }
    val lastEp: Prefs.Progress? = remember(pv) { Repo.prefs.progressList().firstOrNull { it.play.key.startsWith("EP|${item.id}|") } }
    val firstFr = remember { FocusRequester() }
    val restoring = remember { FocusMemory.restore != null }
    LaunchedEffect(det != null) {
        if (det != null && !restoring) {
            delay(60)
            runCatching { firstFr.requestFocus() }
        }
    }
    val hs = rememberHeaderState(item.key)
    val own = det?.header() ?: basicHeader(item)
    val shown = hs.previewInfo ?: own
    val previewing = hs.previewInfo != null
    val clearPreview = Modifier.onFocusChanged { if (it.hasFocus) hs.previewItem = null }

    fun playEpisodes(eps: List<Episode>, index: Int) {
        val m = d ?: return
        val list = eps.map { ep ->
            PlayItem(
                key = epKey(item.id, ep),
                title = m.title,
                subtitle = "S${ep.season}E${ep.number} - ${ep.title}",
                image = m.poster ?: item.image,
                url = ep.url,
                live = false,
            )
        }
        val pos = Repo.prefs.progress(list[index].key)?.pos ?: 0L
        PlayQueue.start(ctx, list, index, pos)
    }

    fun continueLast() {
        val m = d ?: return
        val p = lastEp ?: return
        for ((_, eps) in m.seasons) {
            val i = eps.indexOfFirst { epKey(item.id, it) == p.play.key }
            if (i >= 0) {
                playEpisodes(eps, i)
                return
            }
        }
        Actions.resume(ctx, p)
    }

    // Cabecera fija arriba; solo se desplaza la lista de temporadas y episodios.
    Box(Modifier.fillMaxSize().background(C.bg)) {
        HeroImage(shown.image)
        BoxWithConstraints(Modifier.fillMaxSize()) {
        // Cabecera de altura fija: al pasar por los relacionados, la lista de abajo no sube ni baja.
        val headerH = (maxHeight * 0.46f).coerceIn(210.dp, 320.dp)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth(0.62f).height(headerH).padding(start = 48.dp, top = 28.dp, end = 16.dp)) {
                HeaderText(shown, C.gold, 4, Modifier.fillMaxWidth().weight(1f)) { k, o ->
                    if (k == own.key) hs.overflow = o
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .graphicsLayer { alpha = if (previewing) 0.35f else 1f }
                        .then(clearPreview),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (lastEp != null && det != null) {
                        TvButton(
                            "Continuar " + (lastEp.play.subtitle?.substringBefore(" - ") ?: ""),
                            "s_cont",
                            icon = Icons.Filled.PlayArrow,
                            filled = true,
                            focusRequester = firstFr,
                        ) { continueLast() }
                    }
                    if (hs.overflow) TvButton("Leer más", "s_more", focusRequester = hs.moreFr) { hs.fullOpen = true }
                    TvButton(
                        null,
                        "s_fav",
                        icon = if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        focusRequester = if (lastEp == null) firstFr else null,
                    ) { Actions.toggleFav(ctx, item) }
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                state = seasonList,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    det == null -> this.item(key = "loading") {
                        Box(Modifier.padding(start = 48.dp)) { SmallLoading() }
                    }
                    det.seasons.isEmpty() -> this.item(key = "empty") {
                        Text(
                            if (failed) "No se pudieron cargar los episodios." else "No se han encontrado episodios.",
                            color = C.text2, fontSize = 15.sp, modifier = Modifier.padding(start = 48.dp),
                        )
                    }
                    else -> det.seasons.forEach { (season, eps) ->
                        this.item(key = "s$season") {
                            val sfr = seasonFrs.getOrPut(season) { FocusRequester() }
                            Box(
                                Modifier.animateItem().fillMaxWidth(0.62f).padding(start = 48.dp, end = 16.dp)
                                    .focusRequester(sfr).then(clearPreview)
                            ) {
                                SeasonRow(season, eps.size, open == season) { open = if (open == season) null else season }
                            }
                        }
                        if (open == season) {
                            this.itemsIndexed(eps, key = { _, ep -> "e${ep.id}" }) { i, ep ->
                                val p = Repo.prefs.progress(epKey(item.id, ep))
                                Box(Modifier.animateItem().fillMaxWidth(0.62f).padding(start = 48.dp, end = 16.dp).then(clearPreview)) {
                                    EpisodeRow(ep, p, "ep_${ep.id}") { playEpisodes(eps, i) }
                                }
                            }
                        }
                    }
                }
                this.item(key = "related") {
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(24.dp))
                        RelatedRow(item) { x -> hs.previewItem = x }
                    }
                }
            }
        }
        }
        if (hs.fullOpen) FullPlotPanel(own) { hs.fullOpen = false }
    }
}

@Composable
private fun SeasonRow(season: Int, count: Int, expanded: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) Color.Black else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .memFocus("season_$season") { focused = it }
            .tvClick(onClick = onClick)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    focused -> C.gold
                    expanded -> Color(0xFF2A2416)
                    else -> Color.Transparent
                }
            )
            .then(if (expanded && !focused) Modifier.border(1.dp, C.gold.copy(alpha = 0.6f), RoundedCornerShape(6.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tc = if (expanded && !focused) C.gold else fg
        Text(if (expanded) "▾" else "▸", color = tc, fontSize = 16.sp, modifier = Modifier.width(22.dp))
        Text("Temporada $season", color = tc, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("$count episodios", color = if (focused) Color.Black else C.text2, fontSize = 13.sp)
    }
}

@Composable
private fun EpisodeRow(ep: Episode, progress: Prefs.Progress?, focusKey: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 26.dp)
            .memFocus(focusKey) { focused = it }
            .tvClick(onClick = onClick)
            .clip(shape)
            .background(if (focused) Color(0xFF2A2A2A) else Color.Transparent)
            .then(if (focused) Modifier.border(1.5.dp, C.gold, shape) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${ep.number}", color = C.gold, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
        Column(Modifier.weight(1f)) {
            Text(ep.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress != null && progress.dur > 0) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(0.5f).height(3.dp).background(Color(0xFF444444))) {
                    Box(Modifier.fillMaxWidth((progress.pos.toFloat() / progress.dur).coerceIn(0f, 1f)).fillMaxHeight().background(C.gold))
                }
            }
        }
        ep.duration?.let { Text(it, color = C.text2, fontSize = 13.sp) }
    }
}
