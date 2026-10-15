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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.suso.miiptv.data.Category
import com.suso.miiptv.data.Item
import com.suso.miiptv.data.Kind
import com.suso.miiptv.data.Repo

@Composable
fun HomeScreen() {
    val dv = Repo.dataVersion
    val pv = Repo.prefs.version
    var base by remember { mutableStateOf(if (UiState.homeVer == dv) UiState.homeCache else null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dv) {
        if (UiState.homeVer == dv && UiState.homeCache != null) {
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
            UiState.homeVer = dv
            base = rows
        } catch (e: Exception) {
            error = e.message ?: "No se pudo cargar la lista"
        }
    }

    val extra = remember(pv) {
        buildList<HomeRow> {
            val prog = Repo.prefs.progressList()
            if (prog.isNotEmpty()) add(HomeRow("home_cont", "Continuar viendo", null, emptyList(), prog))
            val favs = Repo.prefs.favorites()
            listOf(
                Kind.LIVE to "Canales favoritos",
                Kind.MOVIE to "Películas favoritas",
                Kind.SERIES to "Series favoritas",
            ).forEach { (k, t) ->
                val l = favs.filter { it.kind == k }
                if (l.isNotEmpty()) add(HomeRow("home_fav_${k.name}", t, k, l))
            }
        }
    }

    val err = error
    val b = base
    when {
        err != null -> ErrorBox(err) { Repo.reload() }
        b == null -> Loading("Cargando tu lista…")
        else -> {
            val rows = extra + b
            if (rows.isEmpty()) {
                EmptyText("La lista no tiene contenido")
            } else {
                LazyColumn(
                    state = UiState.homeList,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 18.dp, bottom = 40.dp),
                ) {
                    items(rows, key = { it.key }) { row -> HomeRowView(row) }
                }
            }
        }
    }
}

/** Fila horizontal con título, como en la pantalla de Inicio de las capturas. */
@Composable
fun HomeRowView(row: HomeRow) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        SectionTitle(row.title, Modifier.padding(start = 40.dp, bottom = 10.dp))
        LazyRow(
            state = UiState.rowState(row.key),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
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
                        subtitle = p.play.subtitle,
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
                        PosterCard(x.name, x.image, fk, Modifier.width(130.dp), onLong = { Actions.toggleFav(ctx, x) }) {
                            Actions.open(x)
                        }
                    }
                }
            }
        }
    }
}
