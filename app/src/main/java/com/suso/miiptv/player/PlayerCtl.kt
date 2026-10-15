package com.suso.miiptv.player

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.suso.miiptv.data.EpgEntry
import com.suso.miiptv.data.Http
import com.suso.miiptv.data.PlayItem
import com.suso.miiptv.data.Repo
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/** Cola de reproducción que se pasa del menú al reproductor. */
object PlayQueue {
    var items: List<PlayItem> = emptyList()
        private set
    var index = 0
        private set
    var startPos = 0L
        private set

    fun start(ctx: Context, list: List<PlayItem>, idx: Int, pos: Long) {
        if (list.isEmpty()) return
        items = list
        index = idx.coerceIn(0, list.lastIndex)
        startPos = pos
        val i = Intent(ctx, PlayerActivity::class.java)
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}

@OptIn(UnstableApi::class)
class PlayerCtl(ctx: Context) {
    val items: List<PlayItem> = PlayQueue.items
    var index by mutableIntStateOf(PlayQueue.index)
        private set
    val current: PlayItem get() = items[index]

    var isPlaying by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var epg by mutableStateOf<List<EpgEntry>>(emptyList())
        private set

    /** Cambia cuando cambian las pistas disponibles o la elegida. */
    var tracksVersion by mutableIntStateOf(0)
        private set

    var onFinished: (() -> Unit)? = null

    private var triedHls = false
    private var retries = 0
    private var started = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    val player: ExoPlayer

    init {
        val http = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.userAgent)
        val renderers = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 2_000, 4_000)
            .build()
        player = ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(load)
            .build()
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    error = null
                    retries = 0
                    duration = player.duration.coerceAtLeast(0)
                }
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(e: PlaybackException) {
                handleError(e)
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
            }
        })

        // Idiomas preferidos: el último que eligió el usuario (o español por defecto).
        val prefs = Repo.prefs
        val tb = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(prefs.audioLang ?: "es")
        val sub = prefs.subLang
        if (!sub.isNullOrEmpty()) tb.setPreferredTextLanguage(sub)
        if (sub == "") tb.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        player.trackSelectionParameters = tb.build()
    }

    // ---------- Audio y subtítulos ----------

    data class TrackOption(val label: String, val selected: Boolean, val groupIndex: Int, val trackIndex: Int)

    /** type: C.TRACK_TYPE_AUDIO o C.TRACK_TYPE_TEXT */
    fun trackOptions(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        val groups = player.currentTracks.groups
        for (gi in groups.indices) {
            val g = groups[gi]
            if (g.type != type) continue
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                out.add(TrackOption(trackLabel(g.getTrackFormat(ti), out.size + 1, type), g.isTrackSelected(ti), gi, ti))
            }
        }
        return out
    }

    /** opt = null desactiva ese tipo de pista (solo tiene sentido para subtítulos). */
    fun selectTrack(type: Int, opt: TrackOption?) {
        val b = player.trackSelectionParameters.buildUpon()
        if (opt == null) {
            b.setTrackTypeDisabled(type, true)
            if (type == C.TRACK_TYPE_TEXT) Repo.prefs.subLang = ""
        } else {
            val groups = player.currentTracks.groups
            if (opt.groupIndex !in groups.indices) return
            val g = groups[opt.groupIndex]
            b.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, opt.trackIndex))
            val lang = g.getTrackFormat(opt.trackIndex).language
            if (type == C.TRACK_TYPE_AUDIO) {
                if (lang != null) {
                    Repo.prefs.audioLang = lang
                    b.setPreferredAudioLanguage(lang)
                }
            } else {
                Repo.prefs.subLang = lang ?: "und"
                if (lang != null) b.setPreferredTextLanguage(lang)
            }
        }
        player.trackSelectionParameters = b.build()
        tracksVersion++
    }

    fun subtitlesOff(): Boolean =
        player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
            trackOptions(C.TRACK_TYPE_TEXT).none { it.selected }

    private fun trackLabel(f: Format, n: Int, type: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
            val name = Locale(code).getDisplayLanguage(Locale("es"))
            if (name.isBlank() || name.equals(code, true)) code.uppercase()
            else name.replaceFirstChar { it.uppercase() }
        }
        val label = f.label?.takeIf { it.isNotBlank() }
        val parts = mutableListOf<String>()
        parts.add(label ?: lang ?: if (type == C.TRACK_TYPE_AUDIO) "Audio $n" else "Subtítulos $n")
        if (label != null && lang != null && !label.contains(lang, true)) parts.add(lang)
        if (type == C.TRACK_TYPE_AUDIO) {
            when (f.channelCount) {
                1 -> "Mono"
                2 -> "Estéreo"
                6 -> "5.1"
                8 -> "7.1"
                else -> null
            }?.let { parts.add(it) }
        }
        if (type == C.TRACK_TYPE_TEXT && (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) parts.add("forzados")
        return parts.joinToString(" · ")
    }

    fun start() {
        if (items.isEmpty()) return
        play(PlayQueue.index, PlayQueue.startPos)
    }

    fun play(i: Int, pos: Long) {
        if (i !in items.indices) return
        saveProgress()
        handler.removeCallbacksAndMessages(null)
        index = i
        error = null
        triedHls = false
        retries = 0
        position = pos
        duration = 0
        val it = current
        load(it.url, pos, false)
        if (it.live) {
            it.channel?.let { ch -> Repo.prefs.lastLive = ch.key }
            loadEpg()
        } else {
            epg = emptyList()
        }
    }

    private fun load(url: String, pos: Long, forceHls: Boolean) {
        val b = MediaItem.Builder().setUri(url)
        if (forceHls || url.contains(".m3u8", ignoreCase = true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        buffering = true
        player.setMediaItem(b.build(), pos)
        player.prepare()
        player.play()
    }

    private fun loadEpg() {
        val ch = current.channel ?: run {
            epg = emptyList()
            return
        }
        val idx = index
        scope.launch {
            val l = Repo.epg(ch)
            if (idx == index) epg = l
        }
    }

    fun retry() {
        val it = current
        error = null
        load(it.url, if (it.live) 0 else position, triedHls)
    }

    private fun handleError(e: PlaybackException) {
        val it = current
        val code = e.errorCode
        if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        // Algunos servidores dan HLS aunque la dirección no lo diga: probamos una vez.
        if (code in 3000..3999 && !triedHls && !it.url.contains(".m3u8", ignoreCase = true)) {
            triedHls = true
            load(it.url, if (it.live) 0 else position, true)
            return
        }
        // Cortes de red: reintentamos solos unas cuantas veces.
        if (code in 2000..2999 && code != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && retries < 3) {
            retries++
            buffering = true
            val idx = index
            handler.postDelayed({
                if (idx == index) load(current.url, if (current.live) 0 else position, triedHls)
            }, 2000L * retries)
            return
        }
        buffering = false
        error = when {
            code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                val http = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                "El servidor rechazó el canal" + (if (http != null) " (HTTP $http)" else "") +
                    ". Puede que la cuenta tenga otra conexión abierta o que el canal no esté disponible."
            }
            code in 2000..2999 -> "No se puede conectar con el servidor. Revisa tu conexión."
            code in 3000..3999 -> "Este contenido tiene un formato no compatible."
            code in 4000..4999 -> "Este dispositivo no puede decodificar este vídeo."
            else -> "No se puede reproducir (${e.errorCodeName})."
        }
    }

    private fun onEnded() {
        val it = current
        if (it.live) {
            // Un directo que "termina" suele ser un corte: volvemos a conectar.
            handler.postDelayed({ if (current.live) load(current.url, 0, triedHls) }, 1500)
            return
        }
        saveProgress()
        if (index < items.lastIndex) {
            val next = index + 1
            play(next, Repo.prefs.progress(items[next].key)?.pos ?: 0L)
        } else {
            onFinished?.invoke()
        }
    }

    fun zap(delta: Int) {
        if (!current.live || items.size < 2) return
        val n = ((index + delta) % items.size + items.size) % items.size
        play(n, 0)
    }

    fun togglePlay() {
        when {
            error != null || player.playbackState == Player.STATE_IDLE -> retry()
            player.isPlaying -> player.pause()
            else -> {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
        }
    }

    fun seekBy(ms: Long) {
        if (current.live) return
        val d = player.duration
        val target = (player.currentPosition + ms).coerceAtLeast(0).let { if (d > 0) it.coerceAtMost(d - 1000) else it }
        player.seekTo(target)
        position = target
    }

    fun seekToFraction(f: Float) {
        if (current.live) return
        val d = player.duration
        if (d <= 0) return
        val target = (d * f.coerceIn(0f, 1f)).toLong()
        player.seekTo(target)
        position = target
    }

    fun tick() {
        if (player.mediaItemCount == 0) return
        position = player.currentPosition.coerceAtLeast(0)
        val d = player.duration
        if (d > 0) duration = d
        // Refresca la guía cuando termina el programa actual.
        val e = epg.firstOrNull()
        if (current.live && e != null && e.end < System.currentTimeMillis() - 60_000) loadEpg()
    }

    fun isFavorite(): Boolean = current.channel?.let { Repo.prefs.isFavorite(it.key) } ?: false

    fun toggleFavorite(): Boolean? = current.channel?.let { Repo.prefs.toggleFavorite(it) }

    fun saveProgress() {
        if (player.mediaItemCount == 0 || items.isEmpty() || current.live) return
        val d = player.duration
        if (d <= 0) return
        Repo.prefs.saveProgress(current, player.currentPosition, d)
    }

    fun onStart() {
        if (!started) {
            started = true
            return
        }
        if (current.live) load(current.url, 0, triedHls) else player.play()
    }

    fun onStop() {
        saveProgress()
        // En directo cortamos la conexión: muchas cuentas solo permiten una a la vez.
        if (current.live) player.stop() else player.pause()
    }

    fun release() {
        saveProgress()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        player.release()
    }
}
