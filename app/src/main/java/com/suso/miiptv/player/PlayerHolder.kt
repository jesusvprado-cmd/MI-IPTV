package com.suso.miiptv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.suso.miiptv.data.Http
import com.suso.miiptv.data.Item
import com.suso.miiptv.data.Repo

/**
 * Un único reproductor para toda la app. Lo comparten la previsualización de TV en directo
 * y la pantalla completa, así al pulsar OK el canal sigue sin cortarse ni volver a conectar
 * (y nunca se abren dos conexiones a la vez con la cuenta IPTV).
 */
@OptIn(UnstableApi::class)
object PlayerHolder {
    private var player: ExoPlayer? = null
    private var signature = ""

    /** true mientras la pantalla completa está abierta. */
    @Volatile
    var fullscreen = false

    /** Cambia cada vez que se vuelve a la pantalla principal (la previsualización se vuelve a enganchar). */
    var epoch by mutableIntStateOf(0)
        private set

    var previewError by mutableStateOf<String?>(null)
        private set

    private var triedHls = false

    private val previewListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (fullscreen) return
            val p = player ?: return
            val url = currentUrl() ?: return
            if (error.errorCode in 3000..3999 && !triedHls && !url.contains(".m3u8", true)) {
                triedHls = true
                load(p, url, true)
                return
            }
            previewError = "Sin señal"
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) previewError = null
        }
    }

    fun get(ctx: Context): ExoPlayer {
        val prefs = Repo.prefs
        val sig = "${prefs.bufferMode}|${prefs.audioDecode}|${Http.userAgent}"
        player?.let { existing ->
            if (sig == signature || fullscreen) return existing
            // Han cambiado los ajustes de reproducción: se crea uno nuevo.
            existing.release()
            player = null
        }
        val http = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.userAgent)
        // El decodificador de audio de la app (FFmpeg) se usa como respaldo, o siempre si así se ha elegido.
        val renderers = DefaultRenderersFactory(ctx.applicationContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                if (prefs.audioDecode) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
        val load = when (prefs.bufferMode) {
            "low" -> DefaultLoadControl.Builder().setBufferDurationsMs(6_000, 20_000, 1_000, 2_000)
            "high" -> DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 3_000, 6_000)
            else -> DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_000, 4_000)
        }.build()
        val exo = ExoPlayer.Builder(ctx.applicationContext, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(load)
            .build()
        exo.playWhenReady = true
        exo.addListener(previewListener)
        player = exo
        signature = sig
        return exo
    }

    fun currentUrl(): String? = player?.currentMediaItem?.localConfiguration?.uri?.toString()

    fun load(exo: ExoPlayer, url: String, forceHls: Boolean) {
        val b = MediaItem.Builder().setUri(url)
        if (forceHls || url.contains(".m3u8", ignoreCase = true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        exo.setMediaItem(b.build())
        exo.prepare()
        exo.play()
    }

    /** Reproduce un canal en el recuadro de previsualización (con el volumen más bajo). */
    fun playPreview(ctx: Context, item: Item, force: Boolean = false) {
        if (fullscreen) return
        val url = try {
            Repo.streamUrl(item)
        } catch (e: Exception) {
            return
        }
        val exo = get(ctx)
        exo.volume = 0.5f
        if (!force && currentUrl() == url && exo.playbackState != Player.STATE_IDLE) {
            if (!exo.isPlaying) exo.play()
            return
        }
        previewError = null
        triedHls = false
        load(exo, url, false)
    }

    fun stopPreview() {
        if (fullscreen) return
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        previewError = null
    }

    /** La pantalla principal vuelve a estar visible. */
    fun onMainVisible() {
        epoch++
    }
}
