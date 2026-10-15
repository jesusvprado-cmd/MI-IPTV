package com.suso.miiptv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.suso.miiptv.data.Item
import com.suso.miiptv.data.Kind
import com.suso.miiptv.data.Prefs
import com.suso.miiptv.data.Repo
import com.suso.miiptv.player.PlayQueue

object Actions {
    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun open(item: Item) {
        when (item.kind) {
            Kind.MOVIE -> UiState.push(Screen.Movie(item))
            Kind.SERIES -> UiState.push(Screen.Series(item))
            Kind.LIVE -> Unit
        }
    }

    fun playLive(ctx: Context, list: List<Item>, index: Int) {
        try {
            PlayQueue.start(ctx, list.map { Repo.playItem(it) }, index, 0)
        } catch (e: Exception) {
            toast(ctx, e.message ?: "No se puede reproducir")
        }
    }

    fun resume(ctx: Context, p: Prefs.Progress) {
        PlayQueue.start(ctx, listOf(p.play), 0, p.pos)
    }

    fun toggleFav(ctx: Context, item: Item) {
        val added = Repo.prefs.toggleFavorite(item)
        toast(ctx, if (added) "Añadido a favoritos" else "Quitado de favoritos")
    }

    fun openTrailer(ctx: Context, t: String) {
        val url = if (t.startsWith("http")) t else "https://www.youtube.com/watch?v=$t"
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(ctx, "No hay ninguna app para ver el tráiler (instala YouTube)")
        }
    }
}
