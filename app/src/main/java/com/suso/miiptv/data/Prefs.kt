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

    /** Último idioma de audio elegido (código ISO, p. ej. "es"). */
    var audioLang: String?
        get() = sp.getString("audioLang", null)
        set(v) {
            sp.edit().putString("audioLang", v).apply()
        }

    /** Último idioma de subtítulos elegido. "" = desactivados, null = nunca elegido. */
    var subLang: String?
        get() = sp.getString("subLang", null)
        set(v) {
            sp.edit().putString("subLang", v).apply()
        }

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
