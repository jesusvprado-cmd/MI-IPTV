package com.suso.miiptv.data

import android.util.Xml
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Guía de programación externa (XMLTV), elegida en Ajustes → Guía.
 * Se descarga una vez (y se renueva cada 6 horas) y se empareja con los canales por su id de guía
 * o por el nombre (sin «HD», «FHD», prefijos de país...).
 */
object ExternalEpg {
    private val mutex = Mutex()
    private var loadedUrl: String? = null
    private var loadedAt = 0L
    private var byKey: Map<String, List<EpgEntry>> = emptyMap()

    /** Para volver a descargarla («Actualizar la guía ahora»). */
    fun reset() {
        loadedUrl = null
        loadedAt = 0
        byKey = emptyMap()
    }

    private fun nameKey(name: String) = "n:" + Logos.clean(ChannelNames.display(name)).key

    suspend fun epg(item: Item): List<EpgEntry> {
        val url = Repo.prefs.epgUrl.trim()
        if (url.isEmpty()) return emptyList()
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (loadedUrl != url || now - loadedAt > 6 * 3_600_000L) {
                loadedUrl = url
                loadedAt = now
                byKey = try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { load(url) }
                } catch (e: Exception) {
                    ErrorLog.add("Guía externa: no se pudo descargar (${e.message ?: e.javaClass.simpleName})")
                    emptyMap()
                }
            }
        }
        val now = System.currentTimeMillis()
        val l = item.epgId?.let { byKey["i:" + it.lowercase()] } ?: byKey[nameKey(item.name)] ?: return emptyList()
        return l.filter { it.end > now }
    }

    private val fmtTz = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val fmtPlain = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

    private fun parseTime(s: String?): Long {
        if (s == null) return 0
        var t = s.trim()
        if (t.length > 14 && t[14] != ' ') t = t.substring(0, 14) + " " + t.substring(14)
        return try {
            if (t.length > 15) fmtTz.parse(t)?.time ?: 0 else fmtPlain.parse(t.take(14))?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private suspend fun load(url: String): Map<String, List<EpgEntry>> {
        // Solo se guardan los canales que hay en tu lista (la guía completa ocupa mucho).
        val live = try {
            Repo.items(Kind.LIVE, null)
        } catch (e: Exception) {
            emptyList()
        }
        val wantIds = HashSet<String>()
        val wantNames = HashSet<String>()
        for (it in live) {
            it.epgId?.let { id -> wantIds.add(id.lowercase()) }
            wantNames.add(nameKey(it.name))
        }
        val now = System.currentTimeMillis()
        val from = now - 3_600_000L
        val to = now + 24 * 3_600_000L
        val chKeys = HashMap<String, MutableSet<String>>()
        val out = HashMap<String, MutableList<EpgEntry>>()

        Http.open(url).use { r ->
            val body = r.body ?: return emptyMap()
            var input: InputStream = BufferedInputStream(body.byteStream(), 65536)
            input.mark(4)
            val b1 = input.read()
            val b2 = input.read()
            input.reset()
            if (b1 == 0x1f && b2 == 0x8b) input = GZIPInputStream(input, 65536)

            val xp = Xml.newPullParser()
            xp.setInput(input, null)
            var ev = xp.eventType
            var curChan: String? = null
            var field = 0
            var pKeys: Set<String>? = null
            var pStart = 0L
            var pEnd = 0L
            var pTitle: String? = null
            var pDesc: String? = null
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (xp.name) {
                        "channel" -> {
                            val c = xp.getAttributeValue(null, "id")
                            curChan = c
                            if (c != null) {
                                val set = chKeys.getOrPut(c) { mutableSetOf() }
                                if (c.lowercase() in wantIds) set += "i:" + c.lowercase()
                                // El id de muchas guías ya es el nombre («La 1 HD», «Antena3.es»...).
                                val nk = nameKey(c.substringBeforeLast('.'))
                                if (nk in wantNames) set += nk
                            }
                        }
                        "display-name" -> if (curChan != null) field = 3
                        "programme" -> {
                            val c = xp.getAttributeValue(null, "channel")
                            pStart = parseTime(xp.getAttributeValue(null, "start"))
                            pEnd = parseTime(xp.getAttributeValue(null, "stop"))
                            val keys = c?.let { chKeys[it] }?.takeIf { it.isNotEmpty() }
                                ?: c?.lowercase()?.takeIf { it in wantIds }?.let { setOf("i:$it") }
                            pKeys = if (keys != null && pEnd > from && pStart < to) keys else null
                            pTitle = null
                            pDesc = null
                        }
                        "title" -> if (pKeys != null && pTitle == null) field = 1
                        "desc" -> if (pKeys != null && pDesc == null) field = 2
                    }
                    XmlPullParser.TEXT -> when (field) {
                        1 -> pTitle = xp.text
                        2 -> pDesc = xp.text
                        3 -> {
                            val n = xp.text?.trim()
                            val c = curChan
                            if (!n.isNullOrEmpty() && c != null) {
                                val nk = nameKey(n)
                                if (nk in wantNames) chKeys.getOrPut(c) { mutableSetOf() } += nk
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (xp.name) {
                        "title", "desc", "display-name" -> field = 0
                        "channel" -> curChan = null
                        "programme" -> {
                            val ks = pKeys
                            if (ks != null) {
                                val e = EpgEntry(pTitle?.trim() ?: "", pDesc?.trim(), pStart, pEnd)
                                for (k in ks) out.getOrPut(k) { mutableListOf() }.add(e)
                            }
                            pKeys = null
                        }
                    }
                }
                ev = xp.next()
            }
        }
        for (l in out.values) l.sortBy { it.start }
        return out
    }
}
