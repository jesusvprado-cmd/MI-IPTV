package com.suso.miiptv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.Normalizer

data class SearchResult(val live: List<Item>, val movies: List<Item>, val series: List<Item>)

/** Punto único de acceso a los datos. Se usa siempre desde el hilo principal. */
object Repo {
    lateinit var prefs: Prefs
        private set
    var config: Config? = null
        private set
    var source: Source? = null
        private set

    /** Cambia al recargar o cambiar de lista, para que las pantallas vuelvan a cargar. */
    var dataVersion by mutableIntStateOf(0)
        private set

    private val catCache = HashMap<Kind, List<Category>>()
    private val itemCache = HashMap<String, List<Item>>()
    private val epgCache = HashMap<String, Pair<Long, List<EpgEntry>>>()
    private var searchIndex: List<Pair<String, Item>>? = null
    private val searchMutex = Mutex()

    fun isReady() = ::prefs.isInitialized

    fun init(ctx: Context) {
        if (isReady()) return
        prefs = Prefs(ctx.applicationContext)
        prefs.loadConfig()?.let { activate(it, createSource(it)) }
    }

    fun createSource(cfg: Config): Source = if (cfg.type == "xtream") XtreamSource(cfg) else M3uSource(cfg)

    private fun activate(cfg: Config, src: Source) {
        config = cfg
        Http.userAgent = cfg.userAgent.ifBlank { Http.DEFAULT_UA }
        source = src
        clear()
    }

    fun save(cfg: Config, src: Source = createSource(cfg)) {
        prefs.saveConfig(cfg)
        activate(cfg, src)
    }

    /** Vuelve a descargar la lista desde cero. */
    fun reload() {
        config?.let { activate(it, createSource(it)) }
    }

    private fun clear() {
        catCache.clear()
        itemCache.clear()
        epgCache.clear()
        searchIndex = null
        dataVersion++
    }

    private fun src(): Source = source ?: throw IOException("No hay ninguna lista configurada.")

    suspend fun categories(kind: Kind): List<Category> {
        catCache[kind]?.let { return it }
        val l = src().categories(kind)
        catCache[kind] = l
        return l
    }

    suspend fun items(kind: Kind, cat: String?): List<Item> {
        val k = "${kind.name}|${cat ?: "*"}"
        itemCache[k]?.let { return it }
        val everything = itemCache["${kind.name}|*"]
        val l = if (cat != null && everything != null) everything.filter { it.categoryId == cat } else src().items(kind, cat)
        itemCache[k] = l
        return l
    }

    suspend fun latest(kind: Kind): List<Item> {
        val k = "${kind.name}|__recent"
        itemCache[k]?.let { return it }
        val everything = items(kind, null)
        val l = withContext(Dispatchers.Default) { everything.sortedByDescending { it.added }.take(300) }
        itemCache[k] = l
        return l
    }

    fun favorites(kind: Kind): List<Item> = prefs.favorites().filter { it.kind == kind }

    suspend fun movieDetail(item: Item): MovieDetail = src().movieDetail(item)

    suspend fun seriesDetail(item: Item): SeriesDetail = src().seriesDetail(item)

    suspend fun epg(item: Item): List<EpgEntry> {
        val now = System.currentTimeMillis()
        val c = epgCache[item.key]
        if (c != null && now - c.first < 10 * 60_000L) return c.second.filter { it.end > now }
        val l = try {
            src().epg(item)
        } catch (e: Exception) {
            emptyList()
        }
        epgCache[item.key] = now to l
        return l
    }

    fun streamUrl(item: Item): String = item.url ?: src().streamUrl(item)

    fun playItem(item: Item): PlayItem = PlayItem(
        key = item.key,
        title = item.name,
        image = item.image,
        url = streamUrl(item),
        live = item.kind == Kind.LIVE,
        channel = if (item.kind == Kind.LIVE) item else null,
    )

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    suspend fun search(q: String): SearchResult {
        val idx = searchMutex.withLock {
            searchIndex ?: run {
                val everything = ArrayList<Item>()
                for (k in Kind.values()) {
                    everything.addAll(
                        try {
                            items(k, null)
                        } catch (e: Exception) {
                            emptyList()
                        }
                    )
                }
                val built = withContext(Dispatchers.Default) { everything.map { norm(it.name) to it } }
                searchIndex = built
                built
            }
        }
        val nq = norm(q.trim())
        val found = withContext(Dispatchers.Default) { idx.filter { it.first.contains(nq) }.map { it.second } }
        return SearchResult(
            live = found.filter { it.kind == Kind.LIVE }.take(60),
            movies = found.filter { it.kind == Kind.MOVIE }.take(60),
            series = found.filter { it.kind == Kind.SERIES }.take(60),
        )
    }
}
