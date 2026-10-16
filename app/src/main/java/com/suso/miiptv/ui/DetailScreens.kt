package com.suso.miiptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.suso.miiptv.data.MovieDetail
import com.suso.miiptv.data.PlayItem
import com.suso.miiptv.data.Prefs
import com.suso.miiptv.data.Repo
import com.suso.miiptv.data.SeriesDetail
import com.suso.miiptv.player.PlayQueue
import kotlinx.coroutines.delay

// ---------------- Piezas comunes ----------------

/** Imagen grande a la derecha que se funde con el negro, como en las capturas. */
@Composable
private fun BoxScope.HeroImage(url: String?) {
    if (url == null) return
    Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.5f).fillMaxHeight(0.82f)) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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

@Composable
private fun RelatedRow(item: Item) {
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
                PosterCard(x.name, x.image, "rel_${x.key}", Modifier.width(90.dp)) { Actions.open(x) }
            }
        }
    }
}

/** Sinopsis que ocupa el hueco disponible y se recorta con «…» si no cabe. */
@Composable
private fun FittingPlot(text: String, modifier: Modifier, maxLinesCap: Int = 8) {
    BoxWithConstraints(modifier) {
        val lh = with(LocalDensity.current) { 21.sp.toDp() }
        val lines = if (constraints.hasBoundedHeight) (maxHeight / lh).toInt().coerceIn(1, maxLinesCap) else maxLinesCap
        Text(text, color = Color(0xFFE6E6E6), fontSize = 15.sp, lineHeight = 21.sp, maxLines = lines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmallLoading() {
    CircularProgressIndicator(color = C.gold, modifier = Modifier.size(32.dp))
}

private fun plotLines(text: String?): String? = text?.replace("\\n", "\n")

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

    // Todo cabe en la pantalla: el foco en «Reproducir» ya no desplaza la ficha ni corta el título.
    Box(Modifier.fillMaxSize().background(C.bg)) {
        HeroImage(det?.backdrop ?: det?.poster ?: item.image)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val showRelated = maxHeight >= 470.dp
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth(0.62f)
                        .weight(1f)
                        .padding(start = 48.dp, top = 28.dp, end = 16.dp, bottom = 6.dp),
                ) {
                    Text(
                        det?.title ?: item.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                        lineHeight = 36.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    val meta = listOfNotNull(det?.year, det?.duration, det?.genre?.replace(",", " /")).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, color = C.text2, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    det?.rating?.takeIf { it != "0" && it != "0.0" }?.let {
                        Spacer(Modifier.height(2.dp))
                        Text("★ $it", color = C.gold, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    plotLines(det?.plot)?.let { FittingPlot(it, Modifier.weight(1f, fill = false)) }
                    det?.director?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("Dirección: $it", color = C.text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    det?.cast?.let {
                        Text("Reparto: $it", color = C.text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(16.dp))
                    if (det == null) {
                        SmallLoading()
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            TvButton(
                                if (prog != null) "Continuar ${fmtTime(prog.pos)}" else "Reproducir",
                                "d_play",
                                icon = Icons.Filled.PlayArrow,
                                filled = true,
                                focusRequester = playFr,
                            ) { play(prog?.pos ?: 0L) }
                            if (prog != null) TvButton("Empezar", "d_restart") { play(0L) }
                            TvButton(null, "d_fav", icon = if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder) {
                                Actions.toggleFav(ctx, item)
                            }
                            det.trailer?.let { t -> TvButton("Tráiler", "d_trailer") { Actions.openTrailer(ctx, t) } }
                        }
                    }
                }
                if (showRelated) {
                    RelatedRow(item)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
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
    var open by remember(item.key) { mutableStateOf<Int?>(null) }
    val det = d
    LaunchedEffect(det) {
        if (det != null && open == null && det.seasons.isNotEmpty()) open = det.seasons[0].first
    }
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
        HeroImage(det?.backdrop ?: det?.poster ?: item.image)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth(0.62f).padding(start = 48.dp, top = 28.dp, end = 16.dp)) {
                Text(
                    det?.title ?: item.name, color = C.gold, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    lineHeight = 36.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                val meta = listOfNotNull(det?.year, det?.genre?.replace(",", " /")).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = C.text2, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                plotLines(det?.plot)?.let {
                    Text(it, color = Color(0xFFE6E6E6), fontSize = 15.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (lastEp != null && det != null) {
                        TvButton(
                            "Continuar " + (lastEp.play.subtitle?.substringBefore(" - ") ?: ""),
                            "s_cont",
                            icon = Icons.Filled.PlayArrow,
                            filled = true,
                            focusRequester = firstFr,
                        ) { continueLast() }
                    }
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
                            Box(Modifier.fillMaxWidth(0.62f).padding(start = 48.dp, end = 16.dp)) {
                                SeasonRow(season, eps.size, open == season) { open = if (open == season) null else season }
                            }
                        }
                        if (open == season) {
                            this.itemsIndexed(eps, key = { _, ep -> "e${ep.id}" }) { i, ep ->
                                val p = Repo.prefs.progress(epKey(item.id, ep))
                                Box(Modifier.fillMaxWidth(0.62f).padding(start = 48.dp, end = 16.dp)) {
                                    EpisodeRow(ep, p, "ep_${ep.id}") { playEpisodes(eps, i) }
                                }
                            }
                        }
                    }
                }
                this.item(key = "related") {
                    Spacer(Modifier.height(24.dp))
                    RelatedRow(item)
                }
            }
        }
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
            .background(if (focused) C.gold else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (expanded) "▾" else "▸", color = fg, fontSize = 16.sp, modifier = Modifier.width(22.dp))
        Text("Temporada $season", color = fg, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
