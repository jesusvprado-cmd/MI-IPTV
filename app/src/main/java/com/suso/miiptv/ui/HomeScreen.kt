package com.suso.miiptv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.suso.miiptv.data.Category
import com.suso.miiptv.data.Item
import com.suso.miiptv.data.Kind
import com.suso.miiptv.data.Repo

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen() {
    val dv = Repo.dataVersion
    val hv = Repo.prefs.hiddenVersion
    val pv = Repo.prefs.version
    val ver = "$dv-$hv"
    var base by remember { mutableStateOf(if (UiState.homeVer == ver) UiState.homeCache else null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ver) {
        if (UiState.homeVer == ver && UiState.homeCache != null) {
            base = UiState.homeCache
            return@LaunchedEffect
        }
        base = null
        error = null
        try {
            val rows = mutableListOf<HomeRow>()
            val defs = listOf(
                Triple(Kind.LIVE, "TV en directo", true),
                Triple(Kind.MOVIE, "Películas", false),
                Triple(Kind.SERIES, "Series", false),
            )
            for ((kind, label, required) in defs) {
                val cats: List<Category> = try {
                    Repo.categories(kind)
                } catch (e: Exception) {
                    if (required) throw e else emptyList()
                }
                val first = cats.firstOrNull() ?: continue
                val list: List<Item> = try {
                    Repo.items(kind, first.id)
                } catch (e: Exception) {
                    emptyList()
                }
                if (list.isNotEmpty()) rows.add(HomeRow("home_${kind.name}", "$label · ${first.name}", kind, list.take(40)))
            }
            UiState.homeCache = rows
            UiState.homeVer = ver
            base = rows
        } catch (e: Exception) {
            error = e.message ?: "No se pudo cargar la lista"
        }
    }

    val extra = remember(pv, hv) {
        buildList<HomeRow> {
            val prog = Repo.prefs.progressList()
            if (prog.isNotEmpty()) add(HomeRow("home_cont", "Continuar viendo", null, emptyList(), prog))
            listOf(
                Kind.LIVE to "Canales favoritos",
                Kind.MOVIE to "Películas favoritas",
                Kind.SERIES to "Series favoritas",
            ).forEach { (k, t) ->
                val l = Repo.favorites(k)
                if (l.isNotEmpty()) add(HomeRow("home_fav_${k.name}", t, k, l))
            }
        }
    }

    val err = error
    val b = base
    when {
        err != null -> ErrorBox(err) { Repo.reload() }
        b == null -> SkeletonHome()
        else -> {
            val rows = extra + b
            val hero = remember(b) { heroCandidates(b) }
            if (rows.isEmpty()) {
                EmptyText("La lista no tiene contenido")
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides RevealBringIntoView) {
                LazyColumn(
                    state = UiState.homeList,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 40.dp),
                ) {
                    if (hero.isNotEmpty()) item(key = "hero") { HeroCarousel(hero) }
                    items(rows, key = { it.key }) { row ->
                        // Las filas horizontales mantienen el desplazamiento normal de TV.
                        CompositionLocalProvider(LocalBringIntoViewSpec provides TvBringIntoView) { HomeRowView(row) }
                    }
                }
                }
            }
        }
    }
}

/** Qué fila de Inicio tiene el foco (para atenuar las demás). */
object HomeFocus {
    var row by mutableStateOf<String?>(null)
}

/** Fila horizontal con título, como en la pantalla de Inicio de las capturas. */
@Composable
fun HomeRowView(row: HomeRow) {
    val ctx = LocalContext.current
    // La fila con el foco se ve entera; las demás se atenúan un poco, con transición suave.
    val active = HomeFocus.row
    val dim = active != null && active != row.key
    val alpha by animateFloatAsState(if (dim) 0.55f else 1f, tween(420), label = "rowa")
    val shift by animateFloatAsState(if (active == row.key) 1f else 0f, tween(420), label = "rows")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 22.dp)
            .onFocusChanged { s ->
                if (s.hasFocus) HomeFocus.row = row.key
                else if (HomeFocus.row == row.key) HomeFocus.row = null
            }
            .graphicsLayer { this.alpha = alpha },
    ) {
        SectionTitle(
            row.title,
            Modifier
                .padding(start = sideGap(), bottom = 10.dp)
                .graphicsLayer { translationX = shift * 6.dp.toPx() },
        )
        LazyRow(
            state = UiState.rowState(row.key),
            contentPadding = PaddingValues(horizontal = sideGap(), vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val prog = row.progress
            if (prog != null) {
                itemsIndexed(prog, key = { i, p -> "$i:${p.play.key}" }) { _, p ->
                    PosterCard(
                        title = p.play.title,
                        image = p.play.image,
                        focusKey = "${row.key}:${p.play.key}",
                        modifier = Modifier.width(130.dp),
                        progress = if (p.dur > 0) p.pos.toFloat() / p.dur else null,
                        // Episodio siguiente a uno terminado (aún sin empezar).
                        subtitle = if (p.pos == 0L && p.play.key.startsWith("EP|")) "Siguiente · " + (p.play.subtitle ?: "") else p.play.subtitle,
                    ) { Actions.resume(ctx, p) }
                }
            } else {
                itemsIndexed(row.items, key = { i, x -> "$i:${x.key}" }) { i, x ->
                    val fk = "${row.key}:${x.key}"
                    if (x.kind == Kind.LIVE) {
                        ChannelCard(x.name, x.image, fk, Modifier.width(170.dp), onLong = { Actions.toggleFav(ctx, x) }) {
                            Actions.playLive(ctx, row.items, i)
                        }
                    } else {
                        PosterCard(x.name, x.image, fk, Modifier.width(130.dp), item = x, onLong = { Actions.toggleFav(ctx, x) }) {
                            Actions.open(x)
                        }
                    }
                }
            }
        }
    }
}
