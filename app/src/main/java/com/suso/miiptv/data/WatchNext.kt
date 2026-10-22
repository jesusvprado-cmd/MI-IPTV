package com.suso.miiptv.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import org.json.JSONObject

/**
 * Fila «Continuar viendo» de la pantalla de inicio de Android TV (fuera de la app):
 * las películas y episodios a medias aparecen allí y se reanudan con OK.
 */
object WatchNext {
    private const val MAX = 10

    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try {
                doSync(app)
            } catch (e: Throwable) {
                // Teles o aparatos sin esa fila: no pasa nada.
            }
        }.start()
    }

    @Synchronized
    private fun doSync(ctx: Context) {
        val sp = ctx.getSharedPreferences("miiptv_watchnext", Context.MODE_PRIVATE)
        val ids = runCatching { JSONObject(sp.getString("ids", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val wanted = Repo.prefs.progressList().take(MAX)
        val keep = wanted.map { it.play.key }.toSet()
        val cr = ctx.contentResolver
        // Quita lo que ya no está a medias.
        ids.keys().asSequence().toList().filter { it !in keep }.forEach { k ->
            runCatching { cr.delete(TvContractCompat.buildWatchNextProgramUri(ids.getLong(k)), null, null) }
            ids.remove(k)
        }
        for (p in wanted) {
            val b = WatchNextProgram.Builder()
                .setType(if (p.play.key.startsWith("EP|")) TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(p.ts)
                .setLastPlaybackPositionMillis(p.pos.toInt())
                .setDurationMillis(p.dur.toInt())
                .setTitle(p.play.title)
                .setInternalProviderId(p.play.key)
                .setIntentUri(Uri.parse("pepino://resume?key=" + Uri.encode(p.play.key)))
            p.play.subtitle?.let { b.setDescription(it) }
            p.play.image?.let {
                b.setPosterArtUri(Uri.parse(it))
                b.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
            }
            val values = b.build().toContentValues()
            val id = if (ids.has(p.play.key)) ids.getLong(p.play.key) else -1L
            if (id >= 0) {
                val n = runCatching { cr.update(TvContractCompat.buildWatchNextProgramUri(id), values, null, null) }.getOrDefault(0)
                if (n > 0) continue
                ids.remove(p.play.key)
            }
            val uri = cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values) ?: continue
            ids.put(p.play.key, ContentUris.parseId(uri))
        }
        sp.edit().putString("ids", ids.toString()).apply()
    }
}
