package com.suso.miiptv.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
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

    /** true si se viene de la previsualización: la emisión continúa sin volver a conectar. */
    var handoff = false
        private set

    fun start(ctx: Context, list: List<PlayItem>, idx: Int, pos: Long, fromPreview: Boolean = false) {
        if (list.isEmpty()) return
        items = list
        index = idx.coerceIn(0, list.lastIndex)
        startPos = pos
        handoff = fromPreview
        if (Repo.prefs.externalPlayer) {
            // El otro reproductor abre su propia conexión: se corta la previsualización.
            PlayerHolder.stopPreview()
            if (openExternal(ctx, list[index], pos)) return
        }
        val i = Intent(ctx, PlayerActivity::class.java)
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Sin animación de cambio de pantalla: la imagen pasa a pantalla completa al momento.
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        ctx.startActivity(i)
        if (ctx is android.app.Activity) {
            @Suppress("DEPRECATION")
            ctx.overridePendingTransition(0, 0)
        }
    }

    /** Reproductores de vídeo instalados (nombre, paquete), sin contar esta app. */
    fun installedPlayers(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("http://example.com/video.mp4"), "video/*")
        return try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probe, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != ctx.packageName }
                .distinctBy { it.first }
                .map { it.second to it.first }
                .sortedBy { it.first.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Abre el vídeo en el reproductor elegido en Ajustes (sin preguntar). Devuelve false si no se pudo. */
    fun openExternal(ctx: Context, item: PlayItem, pos: Long = 0): Boolean {
        val pkg = Repo.prefs.externalPkg?.takeIf { p -> installedPlayers(ctx).any { it.second == p } }
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(item.url), "video/*")
            .putExtra("title", item.title)
        if (pos > 0) {
            i.putExtra("position", pos) // VLC (milisegundos)
            i.putExtra("from_start", false)
        }
        if (pkg != null) i.setPackage(pkg)
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "No se pudo abrir el otro reproductor. Se usa el de la app.", Toast.LENGTH_SHORT).show()
            false
        }
    }
}

@OptIn(UnstableApi::class)
class PlayerCtl(ctx: Context) {
    private val appCtx: Context = ctx.applicationContext
    var items: List<PlayItem> by mutableStateOf(PlayQueue.items)
        private set
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

    /** Se llama cuando se conoce la imagen del vídeo (para ajustar la frecuencia de la pantalla). */
    var onVideoFormat: ((Format) -> Unit)? = null

    var speed by mutableFloatStateOf(1f)
        private set

    /** Calidad real de la emisión (resolución, HDR, sonido), para las insignias del reproductor. */
    var streamBadges by mutableStateOf<List<String>>(emptyList())
        private set

    private var loudness: LoudnessEnhancer? = null

    private var triedHls = false
    private var retries = 0
    private var started = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    val player: ExoPlayer

    private val listener = object : Player.Listener {
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
            var video: Format? = null
            var audio: Format? = null
            for (g in tracks.groups) {
                for (i in 0 until g.length) {
                    if (!g.isTrackSelected(i)) continue
                    if (g.type == C.TRACK_TYPE_VIDEO && video == null) video = g.getTrackFormat(i)
                    if (g.type == C.TRACK_TYPE_AUDIO && audio == null) audio = g.getTrackFormat(i)
                }
            }
            video?.let { onVideoFormat?.invoke(it) }
            streamBadges = badgesOf(video, audio)
            // Película o episodio: se recupera el audio y los subtítulos que tenías puestos.
            if (!current.live && restoredFor != current.key && tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                restoredFor = current.key
                restoreTracks()
            }
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width <= 0) return
            val q = qualityLabel(videoSize.width, videoSize.height)
            if (q != null && streamBadges.firstOrNull() != q) {
                streamBadges = listOf(q) + streamBadges.filter { it !in QUALITY_LABELS }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            applyBoost(audioSessionId)
        }
    }


    init {
        val prefs = Repo.prefs
        player = PlayerHolder.get(ctx)
        player.playWhenReady = true
        player.volume = 1f
        player.addListener(listener)

        // Idiomas preferidos: los mismos que ya usa la previsualización, así al pasar a pantalla completa
        // no se vuelven a elegir pistas (eso obligaba a recargar el canal).
        PlayerHolder.applyTrackPrefs(player)
        applyBoost(player.audioSessionId)
    }

    /** Aumento de volumen (para canales que suenan bajos). */
    private fun applyBoost(sessionId: Int) {
        try {
            loudness?.release()
        } catch (e: Exception) {
            // Sin importancia.
        }
        loudness = null
        val db = Repo.prefs.volumeBoost
        if (db <= 0 || sessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            loudness = LoudnessEnhancer(sessionId).apply {
                setTargetGain(db * 100)
                setEnabled(true)
            }
        } catch (e: Exception) {
            // Algunos dispositivos no lo admiten.
        }
    }

    // ---------- Velocidad y salto ----------

    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    fun nextSpeed() {
        if (current.live) return
        val i = speeds.indexOf(speed)
        speed = speeds[(i + 1) % speeds.size]
        player.setPlaybackSpeed(speed)
    }

    /** Milisegundos a saltar; se acelera si se mantiene pulsado. */
    fun seekStepMs(repeat: Int): Long = Repo.prefs.seekStep * 1000L * (1 + repeat / 5).coerceAtMost(6)

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

    private var restoredFor: String? = null

    /** Identifica una pista para volver a encontrarla otro día: idioma|nombre|posición. */
    private fun trackId(type: Int, opt: TrackOption): String {
        val f = player.currentTracks.groups.getOrNull(opt.groupIndex)?.getTrackFormat(opt.trackIndex)
        val pos = trackOptions(type).indexOfFirst { it.groupIndex == opt.groupIndex && it.trackIndex == opt.trackIndex }
        return "${f?.language ?: ""}|${f?.label ?: ""}|$pos"
    }

    private fun findTrack(type: Int, id: String): TrackOption? {
        val parts = id.split('|')
        if (parts.size < 3) return null
        val opts = trackOptions(type)
        val groups = player.currentTracks.groups
        fun fmt(o: TrackOption) = groups[o.groupIndex].getTrackFormat(o.trackIndex)
        return opts.firstOrNull { (fmt(it).language ?: "") == parts[0] && (fmt(it).label ?: "") == parts[1] }
            ?: opts.firstOrNull { parts[0].isNotEmpty() && (fmt(it).language ?: "") == parts[0] }
            ?: parts[2].toIntOrNull()?.let { opts.getOrNull(it) }
    }

    private fun restoreTracks() {
        val saved = Repo.prefs.trackChoice(current.key) ?: return
        saved.optString("a").takeIf { it.isNotEmpty() }?.let { id -> findTrack(C.TRACK_TYPE_AUDIO, id)?.let { applyTrack(C.TRACK_TYPE_AUDIO, it) } }
        when (val t = saved.optString("t")) {
            "" -> Unit
            "off" -> applyTrack(C.TRACK_TYPE_TEXT, null)
            else -> findTrack(C.TRACK_TYPE_TEXT, t)?.let { applyTrack(C.TRACK_TYPE_TEXT, it) }
        }
    }

    /** opt = null desactiva ese tipo de pista (solo tiene sentido para subtítulos). Se recuerda para esta película o serie. */
    fun selectTrack(type: Int, opt: TrackOption?) {
        if (!current.live) {
            val field = if (type == C.TRACK_TYPE_AUDIO) "a" else "t"
            Repo.prefs.saveTrackChoice(current.key, field, if (opt == null) "off" else trackId(type, opt))
        }
        applyTrack(type, opt)
    }

    private fun applyTrack(type: Int, opt: TrackOption?) {
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
        val it = items[PlayQueue.index]
        // Viene de la previsualización y ya se está viendo ese canal: se sigue sin cortar.
        if (PlayQueue.handoff && it.live && PlayerHolder.currentUrl() == it.url && player.playbackState != Player.STATE_IDLE) {
            index = PlayQueue.index
            error = null
            buffering = player.playbackState == Player.STATE_BUFFERING
            isPlaying = player.isPlaying
            if (!player.isPlaying) player.play()
            it.channel?.let { ch -> Repo.prefs.lastLive = ch.key }
            loadEpg()
            return
        }
        play(PlayQueue.index, PlayQueue.startPos)
    }

    /** Al salir de la pantalla completa a la lista de canales, la emisión sigue en la previsualización. */
    private fun keepForPreview(finishing: Boolean): Boolean = finishing && PlayQueue.handoff && current.live

    /** Canal que se estaba viendo antes del actual (para «canal anterior»). */
    private var prevIndex = -1

    /** Aviso breve que se muestra arriba en el reproductor (reconexiones, VPN...). */
    var notice by mutableStateOf<String?>(null)
        private set

    fun clearNotice() {
        notice = null
    }

    fun play(i: Int, pos: Long) {
        if (i !in items.indices) return
        if (i != index && items.getOrNull(index)?.live == true) prevIndex = index
        resetWatchdog()
        saveProgress()
        scrubPos = null
        restoredFor = null
        handler.removeCallbacksAndMessages(null)
        index = i
        error = null
        triedHls = false
        retries = 0
        position = pos
        duration = 0
        val it = current
        if (it.live && speed != 1f) {
            speed = 1f
            player.setPlaybackSpeed(1f)
        }
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
        // Directo que no hay manera de abrir: plan B con la VPN antes de dar el error.
        if (it.live && code in 2000..2999 && tryVpnFallback()) return
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

    // ---------- Ver desde el principio (programas que el canal guarda) ----------

    /** Lista de canales y canal al que volver al terminar de ver un programa desde el principio. */
    private var liveBack: Pair<List<PlayItem>, Int>? = null

    /** Reproduce un programa ya emitido (o desde su inicio) dentro del mismo reproductor; Atrás vuelve al directo. */
    fun playCatchup(pi: PlayItem) {
        if (liveBack == null) liveBack = items to index
        items = listOf(pi)
        play(0, 0)
    }

    /** Si se está viendo un programa desde el principio, vuelve al directo. Devuelve false si no. */
    fun backToLive(): Boolean {
        val b = liveBack ?: return false
        liveBack = null
        items = b.first
        play(b.second, 0)
        return true
    }

    /** Vuelve al canal que se estaba viendo antes. */
    fun zapBack(): Boolean {
        if (!current.live || prevIndex !in items.indices || prevIndex == index) return false
        play(prevIndex, 0)
        return true
    }

    /** Va al canal con ese número (el de la lista; si no lo tiene, por su posición). */
    fun zapToNumber(n: Int): Boolean {
        if (!current.live) return false
        val i = items.indexOfFirst { it.channel?.num == n }.takeIf { it >= 0 }
            ?: (n - 1).takeIf { items.none { it.channel?.num ?: 0 > 0 } && it in items.indices }
            ?: return false
        if (i != index) play(i, 0)
        return true
    }

    // ---------- Vigilancia del directo: imagen congelada y plan B con la VPN ----------

    private var lastPos = -1L
    private var stuckSince = 0L
    private var freezeCount = 0
    private var freezeWindow = 0L

    private fun resetWatchdog() {
        lastPos = -1L
        stuckSince = 0L
    }

    /** Se llama cada medio segundo: si la imagen de un directo no avanza, se reconecta el canal solo. */
    private fun watchdog() {
        if (!current.live || error != null || !player.playWhenReady || player.mediaItemCount == 0) {
            stuckSince = 0L
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val pos = player.currentPosition
        val advancing = player.playbackState == Player.STATE_READY && player.isPlaying && pos != lastPos
        lastPos = pos
        if (advancing) {
            stuckSince = 0L
            return
        }
        if (stuckSince == 0L) {
            stuckSince = now
            return
        }
        // Congelada (sin avanzar) 8 s, o cargando sin parar 15 s.
        val limit = if (player.playbackState == Player.STATE_BUFFERING) 15_000L else 8_000L
        if (now - stuckSince < limit) return
        stuckSince = 0L
        if (now - freezeWindow > 120_000L) {
            freezeWindow = now
            freezeCount = 0
        }
        freezeCount++
        // Tres cortes en dos minutos: plan B con la VPN (si está permitido).
        if (freezeCount >= 3 && tryVpnFallback()) return
        notice = "Imagen congelada · reconectando el canal"
        load(current.url, 0, triedHls)
    }

    /** Conecta la VPN sola para reintentar un canal que se corta. Devuelve false si no se puede. */
    private fun tryVpnFallback(): Boolean {
        val p = Repo.prefs
        if (!p.vpnFallback) return false
        val st = com.suso.miiptv.vpn.VpnManager.state
        if (st == com.suso.miiptv.vpn.VpnManager.State.ON || st == com.suso.miiptv.vpn.VpnManager.State.CONNECTING) return false
        // Solo si Android ya dio permiso a la VPN alguna vez (aquí no se puede preguntar).
        if (com.suso.miiptv.vpn.VpnManager.permissionIntent(appCtx) != null) return false
        if (p.vpnMode == "off") p.vpnMode = if (p.wgConf != null) "wg" else "warp"
        if (p.vpnMode == "wg" && p.wgConf == null) return false
        freezeCount = 0
        notice = "El canal se corta · activando la VPN"
        buffering = true
        // Al conectarse, la pantalla del reproductor vuelve a abrir el canal por la nueva ruta.
        com.suso.miiptv.vpn.VpnManager.connect()
        return true
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

    /** Posición elegida con ◀ ▶ mientras se avanza o retrocede (aún sin saltar); null si no. */
    var scrubPos by mutableStateOf<Long?>(null)
        private set
    private val commitScrubRunnable = Runnable { commitScrub() }

    /** Avance: se mueve la marca y se ve el minuto; el salto se hace al parar o con OK. */
    fun scrubBy(ms: Long) {
        if (current.live) return
        val d = player.duration
        val base = scrubPos ?: player.currentPosition
        val target = (base + ms).coerceAtLeast(0).let { if (d > 0) it.coerceAtMost(d - 1000) else it }
        scrubPos = target
        handler.removeCallbacks(commitScrubRunnable)
        handler.postDelayed(commitScrubRunnable, 1300)
    }

    fun commitScrub(): Boolean {
        val t = scrubPos ?: return false
        handler.removeCallbacks(commitScrubRunnable)
        player.seekTo(t)
        position = t
        scrubPos = null
        return true
    }

    fun cancelScrub(): Boolean {
        if (scrubPos == null) return false
        handler.removeCallbacks(commitScrubRunnable)
        scrubPos = null
        return true
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
        watchdog()
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

    fun onStop(finishing: Boolean) {
        saveProgress()
        if (keepForPreview(finishing)) return
        // En directo cortamos la conexión: muchas cuentas solo permiten una a la vez.
        if (current.live) player.stop() else player.pause()
    }

    fun release() {
        saveProgress()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        try {
            loudness?.release()
        } catch (e: Exception) {
            // Sin importancia.
        }
        player.removeListener(listener)
        if (speed != 1f) player.setPlaybackSpeed(1f)
        // El reproductor es compartido: no se destruye, solo se para (salvo si sigue en la previsualización).
        if (!keepForPreview(true)) {
            player.stop()
            player.clearMediaItems()
        }
    }
}

private val QUALITY_LABELS = setOf("4K", "QHD", "FHD", "HD", "SD")

private fun qualityLabel(w: Int, h: Int): String? = when {
    w <= 0 || h <= 0 -> null
    w >= 3200 || h >= 2000 -> "4K"
    w >= 2400 || h >= 1400 -> "QHD"
    w >= 1800 || h >= 1000 -> "FHD"
    w >= 1200 || h >= 700 -> "HD"
    else -> "SD"
}

/** Insignias a partir de las pistas elegidas: «FHD», «HDR», «50 fps», «5.1», «Dolby»… */
private fun badgesOf(video: Format?, audio: Format?): List<String> {
    val out = ArrayList<String>()
    if (video != null) {
        qualityLabel(video.width, video.height)?.let { out += it }
        val tr = video.colorInfo?.colorTransfer
        if (tr == C.COLOR_TRANSFER_ST2084 || tr == C.COLOR_TRANSFER_HLG) out += "HDR"
        val fps = video.frameRate
        if (fps >= 47f) out += "${Math.round(fps)} fps"
    }
    if (audio != null) {
        when {
            audio.channelCount >= 8 -> out += "7.1"
            audio.channelCount >= 6 -> out += "5.1"
        }
        val m = audio.sampleMimeType ?: ""
        when {
            m.contains("eac3-joc", true) -> out += "ATMOS"
            m.contains("eac3", true) -> out += "DD+"
            m.contains("ac3", true) || m.contains("ac4", true) -> out += "DOLBY"
            m.contains("dts", true) -> out += "DTS"
        }
    }
    return out
}
