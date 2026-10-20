package com.suso.miiptv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

class Prefs(ctx: Context) {
    data class Progress(val play: PlayItem, val pos: Long, val dur: Long, val ts: Long)

    private val sp = ctx.getSharedPreferences("miiptv", Context.MODE_PRIVATE)

    /** Cambia cada vez que cambian favoritos o progreso, para refrescar la interfaz. */
    var version by mutableIntStateOf(0)
        private set

    fun bump() {
        version++
    }

    fun loadConfig(): Config? =
        sp.getString("config", null)?.let { runCatching { Config.fromJson(JSONObject(it)) }.getOrNull() }

    fun saveConfig(c: Config) {
        sp.edit().putString("config", c.toJson().toString()).apply()
    }

    var lastLive: String?
        get() = sp.getString("lastLive", null)
        set(v) {
            sp.edit().putString("lastLive", v).apply()
        }

    // ---------- Ajustes ----------

    /** Cambia cada vez que cambia un ajuste, para refrescar la pantalla de Ajustes. */
    var settingsVersion by mutableIntStateOf(0)
        private set

    private fun str(k: String, def: String): String = sp.getString(k, def) ?: def
    private fun putStr(k: String, v: String?) {
        sp.edit().putString(k, v).apply()
        settingsVersion++
    }
    private fun putInt(k: String, v: Int) {
        sp.edit().putInt(k, v).apply()
        settingsVersion++
    }
    // ---------------- Audio y subtítulos elegidos en cada película o episodio ----------------

    /** Lo elegido para [key] (o, si es un episodio, para la serie): "a" audio y "t" subtítulos ("off" = sin subtítulos). */
    fun trackChoice(key: String): JSONObject? {
        val own = sp.getString("tracks_$key", null)
        val series = if (key.startsWith("EP|")) sp.getString("tracks_" + key.substringBeforeLast('|'), null) else null
        return (own ?: series)?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    fun saveTrackChoice(key: String, field: String, value: String) {
        val keys = mutableListOf(key)
        if (key.startsWith("EP|")) keys += key.substringBeforeLast('|')
        val e = sp.edit()
        for (k in keys) {
            val o = sp.getString("tracks_$k", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            o.put(field, value)
            e.putString("tracks_$k", o.toString())
        }
        e.apply()
    }

    private fun putBool(k: String, v: Boolean) {
        sp.edit().putBoolean(k, v).apply()
        settingsVersion++
    }

    /** Idioma de audio preferido (código ISO, p. ej. "es"). "orig" = sin preferencia. null = español. */
    var audioLang: String?
        get() = sp.getString("audioLang", null)
        set(v) = putStr("audioLang", v)

    /** Idioma de subtítulos preferido. "" = desactivados, null = nunca elegido. */
    var subLang: String?
        get() = sp.getString("subLang", null)
        set(v) = putStr("subLang", v)

    /** fit, zoom, fill, 16:9, 4:3 */
    var resizeMode: String
        get() = str("resize", "fit")
        set(v) = putStr("resize", v)

    /** low, normal, high */
    var bufferMode: String
        get() = str("buffer", "normal")
        set(v) = putStr("buffer", v)

    /** Segundos por pulsación al avanzar o retroceder. */
    var seekStep: Int
        get() = sp.getInt("seekStep", 10)
        set(v) = putInt("seekStep", v)

    var externalPlayer: Boolean
        get() = sp.getBoolean("external", false)
        set(v) = putBool("external", v)

    /** true = decodificar siempre el audio en la app (para canales que no se oyen). */
    var audioDecode: Boolean
        get() = sp.getBoolean("audioDecode", false)
        set(v) = putBool("audioDecode", v)

    /** Aumento de volumen en dB (0, 3, 6, 10). */
    var volumeBoost: Int
        get() = sp.getInt("boost", 0)
        set(v) = putInt("boost", v)

    /** s, m, l, xl */
    var subSize: String
        get() = str("subSize", "m")
        set(v) = putStr("subSize", v)

    /** white, yellow */
    var subColor: String
        get() = str("subColor", "white")
        set(v) = putStr("subColor", v)

    /** none (contorno negro), band (franja oscura) */
    var subBg: String
        get() = str("subBg", "none")
        set(v) = putStr("subBg", v)

    /** Logos de canales en alta calidad (bancos abiertos) en vez de los de la lista. */
    var hqLogos: Boolean
        get() = sp.getBoolean("hqLogos", true)
        set(v) = putBool("hqLogos", v)

    /** Ajustar la frecuencia de la pantalla al vídeo (teles que lo admitan). */
    var afr: Boolean
        get() = sp.getBoolean("afr", false)
        set(v) = putBool("afr", v)

    /** Miniaturas del vídeo al avanzar o retroceder en películas y series. */
    var seekThumbs: Boolean
        get() = sp.getBoolean("seek_thumbs", true)
        set(v) = putBool("seek_thumbs", v)

    // ---------- VPN ----------

    /** off, warp, wg */
    var vpnMode: String
        get() = str("vpnMode", "off")
        set(v) = putStr("vpnMode", v)

    var warpConf: String?
        get() = sp.getString("warpConf", null)
        set(v) = putStr("warpConf", v)

    var wgConf: String?
        get() = sp.getString("wgConf", null)
        set(v) = putStr("wgConf", v)

    var wgName: String?
        get() = sp.getString("wgName", null)
        set(v) = putStr("wgName", v)

    // ---------- Categorías ocultas y PIN ----------

    /** Cambia cuando se ocultan o muestran categorías. */
    var hiddenVersion by mutableIntStateOf(0)
        private set

    private var hidden: MutableSet<String>? = null

    private fun hiddenSet(): MutableSet<String> {
        hidden?.let { return it }
        val s = HashSet(sp.getStringSet("hidden", emptySet()) ?: emptySet())
        hidden = s
        return s
    }

    fun catKey(kind: Kind, catId: String) = "${kind.name}|$catId"

    fun isHidden(kind: Kind, catId: String): Boolean = catKey(kind, catId) in hiddenSet()

    fun setHidden(keys: Collection<String>, hide: Boolean) {
        val s = hiddenSet()
        if (hide) s.addAll(keys) else s.removeAll(keys.toSet())
        sp.edit().putStringSet("hidden", HashSet(s)).apply()
        hiddenVersion++
    }

    /** Categorías ya vistas alguna vez (para aplicar la detección automática solo a las nuevas). */
    fun unseen(keys: Collection<String>): List<String> {
        val seen = sp.getStringSet("seenCats", emptySet()) ?: emptySet()
        return keys.filter { it !in seen }
    }

    fun markSeen(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val seen = HashSet(sp.getStringSet("seenCats", emptySet()) ?: emptySet())
        seen.addAll(keys)
        sp.edit().putStringSet("seenCats", seen).apply()
    }

    /** PIN para ver o cambiar las categorías ocultas. "" = sin PIN. */
    var pin: String
        get() = str("pin", "")
        set(v) = putStr("pin", v)

    // ---------- Favoritos ----------

    private var favs: MutableList<Item>? = null

    fun favorites(): List<Item> {
        favs?.let { return it }
        val l = mutableListOf<Item>()
        runCatching {
            val a = JSONArray(sp.getString("favs", "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                runCatching { Item.fromJson(o) }.getOrNull()?.let { l.add(it) }
            }
        }
        favs = l
        return l
    }

    fun isFavorite(key: String): Boolean = favorites().any { it.key == key }

    /** Devuelve true si se ha añadido y false si se ha quitado. */
    fun toggleFavorite(item: Item): Boolean {
        val l = favorites().toMutableList()
        val i = l.indexOfFirst { it.key == item.key }
        val added = if (i >= 0) {
            l.removeAt(i)
            false
        } else {
            l.add(0, item)
            true
        }
        favs = l
        val a = JSONArray()
        l.forEach { a.put(it.toJson()) }
        sp.edit().putString("favs", a.toString()).apply()
        bump()
        return added
    }

    // ---------- Continuar viendo ----------

    private var prog: MutableList<Progress>? = null

    fun progressList(): List<Progress> {
        prog?.let { return it }
        val l = mutableListOf<Progress>()
        runCatching {
            val a = JSONArray(sp.getString("prog", "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val p = o.optJSONObject("p") ?: continue
                runCatching {
                    l.add(Progress(PlayItem.fromJson(p), o.optLong("pos"), o.optLong("dur"), o.optLong("ts")))
                }
            }
        }
        prog = l
        return l
    }

    fun progress(key: String): Progress? = progressList().firstOrNull { it.play.key == key }

    fun saveProgress(play: PlayItem, pos: Long, dur: Long) {
        if (play.live || dur <= 0) return
        val l = progressList().filterNot { it.play.key == play.key }.toMutableList()
        if (pos > 30_000 && pos < dur * 0.95) l.add(0, Progress(play, pos, dur, System.currentTimeMillis()))
        while (l.size > 40) l.removeAt(l.lastIndex)
        storeProgress(l)
    }

    fun clearProgress() = storeProgress(mutableListOf())

    private fun storeProgress(l: MutableList<Progress>) {
        prog = l
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("p", it.play.toJson()).put("pos", it.pos).put("dur", it.dur).put("ts", it.ts))
        }
        sp.edit().putString("prog", a.toString()).apply()
        bump()
    }
}
