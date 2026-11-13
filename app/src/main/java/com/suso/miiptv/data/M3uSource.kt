package com.suso.miiptv.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class M3uSource(private val cfg: Config) : Source {
    private val mutex = Mutex()
    private var loaded = false
    private val cats = HashMap<Kind, MutableList<Category>>()
    private val all = HashMap<Kind, MutableList<Item>>()
    private val episodes = HashMap<String, MutableList<Episode>>()
    private var tvgUrl: String? = null

    private suspend fun ensure() {
        mutex.withLock {
            if (!loaded) {
                io { parse() }
                loaded = true
            }
        }
    }

    // ---------- Lista M3U ----------

    private fun parse() {
        // La lista se guarda en el aparato (6 h): la app arranca sin esperar a descargarla.
        DiskCache.read(cfg.m3uUrl, DiskCache.CATALOG_AGE, valid = { it }) { input ->
            for (k in Kind.values()) {
                cats[k] = mutableListOf()
                all[k] = mutableListOf()
            }
            episodes.clear()
            val catSeen = HashMap<Kind, HashSet<String>>()
            val seriesIdx = HashMap<String, Item>()

            fun addCat(kind: Kind, group: String) {
                val seen = catSeen.getOrPut(kind) { HashSet() }
                if (seen.add(group)) cats[kind]!!.add(Category(group, group))
            }

            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            var attrs: Map<String, String> = emptyMap()
            var name = ""
            var pending = false
            var lineNo = 0L
            while (true) {
                val raw = reader.readLine() ?: break
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#EXTM3U")) {
                    val a = parseAttrs(line)
                    tvgUrl = (a["url-tvg"] ?: a["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()?.ifEmpty { null }
                    continue
                }
                if (line.startsWith("#EXTINF")) {
                    attrs = parseAttrs(line)
                    name = extinfName(line)
                    pending = true
                    continue
                }
                if (line.startsWith("#EXTGRP:")) {
                    if (attrs["group-title"].isNullOrBlank()) attrs = attrs + ("group-title" to line.substringAfter(':').trim())
                    continue
                }
                if (line.startsWith("#")) continue
                if (!pending) continue
                pending = false

                val url = line
                val group = attrs["group-title"]?.trim()?.takeIf { it.isNotEmpty() } ?: "Sin categoría"
                val title = name.ifBlank { attrs["tvg-name"] ?: url }
                val logo = attrs["tvg-logo"].clean()
                var kind = classify(url)
                val se = SE_REGEX.find(title)
                if (kind == Kind.MOVIE && se != null) kind = Kind.SERIES
                lineNo++

                when (kind) {
                    Kind.LIVE, Kind.MOVIE -> {
                        addCat(kind, group)
                        val list = all[kind]!!
                        list.add(
                            Item(
                                kind, url, title, logo, group,
                                epgId = attrs["tvg-id"].clean(),
                                num = list.size + 1,
                                added = lineNo,
                                url = url,
                            )
                        )
                    }
                    Kind.SERIES -> {
                        val sName = se?.groupValues?.get(1)?.trim()?.trimEnd('-', '|', ':', '.', ' ')?.trim()
                            ?.ifEmpty { null } ?: title
                        val sid = "$group|$sName"
                        if (!seriesIdx.containsKey(sid)) {
                            addCat(Kind.SERIES, group)
                            val s = Item(Kind.SERIES, sid, sName, logo, group, added = lineNo)
                            seriesIdx[sid] = s
                            all[Kind.SERIES]!!.add(s)
                        }
                        val epList = episodes.getOrPut(sid) { mutableListOf() }
                        val season = se?.groupValues?.get(2)?.toIntOrNull() ?: 1
                        val number = se?.groupValues?.get(3)?.toIntOrNull() ?: (epList.size + 1)
                        val epTitle = if (se != null) cleanEpisodeTitle(title).takeIf { it != title } else null
                        epList.add(Episode(url, season, number, epTitle ?: "Episodio $number", null, logo, null, url))
                    }
                }
            }
            all.values.any { it.isNotEmpty() }
        }
        if (all.values.all { it.isEmpty() }) throw IOException("La lista está vacía o no es una lista M3U válida.")
    }

    private fun classify(url: String): Kind {
        val l = url.lowercase()
        return when {
            "/series/" in l -> Kind.SERIES
            "/movie/" in l -> Kind.MOVIE
            VOD_EXT.containsMatchIn(l) -> Kind.MOVIE
            else -> Kind.LIVE
        }
    }

    private fun parseAttrs(line: String): Map<String, String> {
        val m = HashMap<String, String>()
        ATTR_REGEX.findAll(line).forEach { m[it.groupValues[1].lowercase()] = it.groupValues[2] }
        return m
    }

    private fun extinfName(line: String): String {
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) return line.substring(i + 1).trim()
        }
        return ""
    }

    // ---------- Source ----------

    override suspend fun check(): AccountInfo? {
        ensure()
        return null
    }

    override suspend fun categories(kind: Kind): List<Category> {
        ensure()
        return cats[kind] ?: emptyList()
    }

    override suspend fun items(kind: Kind, categoryId: String?): List<Item> {
        ensure()
        val l = all[kind] ?: return emptyList()
        return if (categoryId == null) l else l.filter { it.categoryId == categoryId }
    }

    override suspend fun movieDetail(item: Item): MovieDetail = MovieDetail(
        item = item, title = item.name, plot = null, year = yearOf(item.name), duration = null,
        genre = null, poster = item.image, backdrop = null, trailer = null, cast = null,
        director = null, rating = null,
    )

    override suspend fun seriesDetail(item: Item): SeriesDetail {
        ensure()
        val eps = episodes[item.id] ?: emptyList()
        val bySeason = eps.groupBy { it.season }.toSortedMap().map { (s, l) -> s to l.sortedBy { it.number } }
        return SeriesDetail(item, item.name, null, yearOf(item.name), null, item.image, null, null, null, bySeason)
    }

    override fun streamUrl(item: Item): String = item.url ?: ""

    // ---------- Guía ----------

    /** Dirección de la guía: la escrita al configurar la lista o, si no, la que trae la propia lista (url-tvg). */
    private fun guideUrl(): String? = cfg.epgUrl.trim().ifEmpty { tvgUrl.orEmpty() }.ifEmpty { null }

    override suspend fun epg(item: Item): List<EpgEntry> {
        val now = System.currentTimeMillis()
        return fullEpg(item).filter { it.end > now }
    }

    /**
     * La guía XMLTV se lee con [ProviderEpg], igual que la guía completa de Xtream: se guarda en el aparato,
     * caduca a las 6 horas, se reintenta si falla y «Actualizar la guía» la vuelve a pedir.
     * (Antes se leía una sola vez por ejecución: a las 14 horas se quedaba vacía y, si fallaba, no se reintentaba.)
     */
    override suspend fun fullEpg(item: Item): List<EpgEntry> {
        if (item.kind != Kind.LIVE) return emptyList()
        ensure()
        val url = guideUrl() ?: return emptyList()
        return ProviderEpg.entries(item, url) ?: emptyList()
    }

    companion object {
        private val ATTR_REGEX = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
        private val SE_REGEX = Regex("^(.*?)[\\s._\\-|:]*\\b[Ss](\\d{1,2})[\\s._\\-]*[Ee](\\d{1,3})")
        private val VOD_EXT = Regex("\\.(mp4|mkv|avi|mov|wmv|flv|m4v)(\\?|$)")
    }
}
