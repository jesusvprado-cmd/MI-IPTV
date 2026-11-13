package com.suso.miiptv.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
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
import androidx.media3.exoplayer.ExoPlayer
import com.suso.miiptv.data.EpgEntry
import com.suso.miiptv.data.PlayItem
import com.suso.miiptv.data.Repo
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/** Temporizador de apagado: a la hora indicada se para la reproducción. */
object SleepTimer {
    var endAt by mutableLongStateOf(0L)

    /** Parar al terminar la película o el episodio que se está viendo (en vez de seguir con el siguiente). */
    var atEnd by mutableStateOf(false)

    /** Minutos que quedan (0 si está desactivado). */
    fun minutesLeft(now: Long = System.currentTimeMillis()): Int =
        if (endAt <= 0) 0 else (((endAt - now) + 59_999) / 60_000).toInt().coerceAtLeast(0)

    /**
     * Desactivado → 30 → 60 → 90 → 120 min → al terminar este vídeo (si no es un directo) → desactivado.
     */
    fun cycle(live: Boolean) {
        if (atEnd) {
            atEnd = false
            endAt = 0L
            return
        }
        val steps = listOf(30, 60, 90, 120)
        val left = minutesLeft()
        val next = steps.firstOrNull { it > left + 1 }
        when {
            endAt <= 0 -> endAt = System.currentTimeMillis() + 30 * 60_000L
            next != null -> endAt = System.currentTimeMillis() + next * 60_000L
            else -> {
                endAt = 0L
                atEnd = !live
            }
        }
    }
}

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
    fun openExternal(ctx: Context, item: PlayItem, pos: Long = 0, pkg: String? = null): Boolean {
        val chosen = (pkg ?: Repo.prefs.externalPkg)?.takeIf { p -> installedPlayers(ctx).any { it.second == p } }
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(item.url), "video/*")
            .putExtra("title", item.title)
        if (pos > 0) {
            i.putExtra("position", pos) // VLC (milisegundos)
            i.putExtra("from_start", false)
        }
        if (chosen != null) i.setPackage(chosen)
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            true
        } catch (e: ActivityNotFoundException) {
            com.suso.miiptv.ui.AppToast.show("No se pudo abrir el otro reproductor. Se usa el de la app.")
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
    val current: PlayItem get() = items[index.coerceIn(0, items.lastIndex)]

    /** Se está viendo en una ventanita (imagen en imagen, móvil y tablet). */
    var pip by mutableStateOf(false)

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

    /**
     * La app no consigue mostrar la imagen de este vídeo (formato que el aparato no decodifica, o el
     * decodificador no saca ninguna imagen): texto con el formato, para avisar y ofrecer otro reproductor.
     */
    var videoProblem by mutableStateOf<String?>(null)
        private set

    /** Datos técnicos del vídeo (formato, tamaño, decodificador), para las opciones del reproductor. */
    var videoInfo by mutableStateOf<String?>(null)
        private set

    private var firstFrame = false
    private var videoSelected = false
    private var noFrameTicks = 0
    private var decoderName: String? = null
    private var lastVideoFormat: Format? = null

    /** Ya se probó solo el otro formato (TS ↔ HLS) en este canal. */
    private var autoSwitched = false
    /** El cambio automático de formato está pendiente de confirmar (se guarda al salir la primera imagen). */
    private var pendingFormatSave = false

    /**
     * El canal no va: se reconecta una vez con el otro formato de emisión (TS ↔ HLS).
     * Si con él sale imagen, se recuerda para ese canal. Devuelve false si ya se probó o no hay otro formato.
     */
    private fun tryOtherFormat(why: String): Boolean {
        val it = current
        if (!it.live || autoSwitched || swapLiveFormat(it.url) == it.url) return false
        autoSwitched = true
        pendingFormatSave = true
        altLiveFormat = !altLiveFormat
        com.suso.miiptv.data.ErrorLog.add("«${it.title}»: $why · se prueba en ${liveFormatLabel()}")
        notice = "El canal no va · probando en formato ${liveFormatLabel()}"
        error = null
        triedHls = false
        retries = 0
        resetWatchdog()
        val idx = index
        handler.post { if (idx == index) load(current.url, 0, false) }
        return true
    }

    /** Ya se probó en este vídeo el decodificador por software. */
    private var triedSoftware = false

    /** El decodificador del aparato no puede con el vídeo: se reintenta una vez con el de software de Android. */
    private fun trySoftware(why: String): Boolean {
        if (triedSoftware) return false
        triedSoftware = true
        PlayerHolder.softwareVideo = true
        com.suso.miiptv.data.ErrorLog.add("«${current.title}»: $why · se prueba con el decodificador por software")
        notice = "Probando otro decodificador…"
        error = null
        videoProblem = null
        retries = 0
        val idx = index
        val pos = if (current.live) 0 else position
        handler.post { if (idx == index) load(current.url, pos, triedHls) }
        return true
    }

    private fun reportVideoProblem(what: String) {
        if (videoProblem != null) return
        if (trySoftware("sin imagen")) return
        if (tryOtherFormat("sin imagen")) return
        videoProblem = what
        com.suso.miiptv.data.ErrorLog.add("Sin imagen en «${current.title}»: $what" + (decoderName?.let { " · decodificador $it" } ?: ""))
    }

    private fun refreshVideoInfo() {
        val f = lastVideoFormat
        videoInfo = if (f == null) null else listOfNotNull(
            codecName(f.sampleMimeType),
            if (f.width > 0) "${f.width}×${f.height}" else null,
            (f.frameRate.takeIf { it > 0f } ?: measuredFps.takeIf { it > 0f })?.let { "${"%.0f".format(it)} fps" },
            decoderName,
        ).joinToString(" · ")
    }

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

        override fun onRenderedFirstFrame() {
            firstFrame = true
            videoProblem = null
            if (pendingFormatSave) {
                pendingFormatSave = false
                saveAltFormat()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            tracksVersion++
            // ¿Hay vídeo y el aparato no sabe decodificarlo? (pasa con algunos canales SAT en MPEG-2)
            val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            videoSelected = videoGroups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
            if (videoGroups.isNotEmpty()) {
                lastVideoFormat = videoGroups.first().getTrackFormat(0)
                refreshVideoInfo()
                if (videoGroups.none { g -> (0 until g.length).any { g.isTrackSupported(it) } }) {
                    reportVideoProblem("este aparato no decodifica su vídeo (${codecName(lastVideoFormat?.sampleMimeType)}) en la app")
                }
            }
            var video: Format? = null
            var audio: Format? = null
            for (g in tracks.groups) {
                for (i in 0 until g.length) {
                    if (!g.isTrackSelected(i)) continue
                    if (g.type == C.TRACK_TYPE_VIDEO && video == null) video = g.getTrackFormat(i)
                    if (g.type == C.TRACK_TYPE_AUDIO && audio == null) audio = g.getTrackFormat(i)
                }
            }
            video?.let {
                val f = if (it.frameRate <= 0f && measuredFps > 0f) it.buildUpon().setFrameRate(measuredFps).build() else it
                videoFormat = f
                onVideoFormat?.invoke(f)
            }
            streamBadges = badgesOf(video, audio)
            // Película o episodio: se recupera el audio y los subtítulos que tenías puestos. Cada tipo cuando
            // aparece: en algunos vídeos (HLS sobre todo) los subtítulos llegan después que el audio.
            if (!current.live) {
                if (restoredFor != current.key && tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                    restoredFor = current.key
                    restoreTracks(C.TRACK_TYPE_AUDIO)
                }
                if (restoredTextFor != current.key && tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) {
                    restoredTextFor = current.key
                    restoreTracks(C.TRACK_TYPE_TEXT)
                }
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


    /**
     * Muchas emisiones (las de satélite, sobre todo) no dicen a cuántas imágenes por segundo van, y sin ese dato
     * no se puede ajustar la frecuencia de la pantalla (25 fps en una pantalla a 60 Hz = microsaltos).
     * Se mide con las marcas de tiempo de las primeras imágenes.
     */
    @Volatile private var measuredFps = 0f
    private val frameTimes = LongArray(60)
    @Volatile private var frameCount = 0
    private val frameMeter = androidx.media3.exoplayer.video.VideoFrameMetadataListener { pts, _, _, _ ->
        val n = frameCount
        if (n < 0) return@VideoFrameMetadataListener
        if (n < frameTimes.size) {
            frameTimes[n] = pts
            frameCount = n + 1
            return@VideoFrameMetadataListener
        }
        frameCount = -1
        val d = (1 until frameTimes.size).map { frameTimes[it] - frameTimes[it - 1] }.filter { it in 5_000..100_000 }.sorted()
        if (d.size < 20) return@VideoFrameMetadataListener
        val fps = 1_000_000f / d[d.size / 2]
        // Se redondea a las frecuencias habituales (23,976 · 24 · 25 · 29,97 · 30 · 50 · 59,94 · 60).
        val std = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)
        val near = std.minByOrNull { kotlin.math.abs(it - fps) } ?: return@VideoFrameMetadataListener
        if (kotlin.math.abs(near - fps) > 1.5f) return@VideoFrameMetadataListener
        handler.post {
            measuredFps = near
            val f = videoFormat ?: return@post
            if (f.frameRate <= 0f) {
                val nf = f.buildUpon().setFrameRate(near).build()
                videoFormat = nf
                onVideoFormat?.invoke(nf)
                refreshVideoInfo()
            }
        }
    }

    private val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            name: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            decoderName = name
            refreshVideoInfo()
        }

        override fun onVideoCodecError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, videoCodecError: Exception) {
            com.suso.miiptv.data.ErrorLog.add("Error del decodificador de vídeo en «${current.title}»: ${videoCodecError.message}")
        }
    }

    init {
        val prefs = Repo.prefs
        player = PlayerHolder.get(ctx)
        player.playWhenReady = true
        player.volume = 1f
        player.addListener(listener)
        player.setVideoFrameMetadataListener(frameMeter)
        player.addAnalyticsListener(analytics)

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

    // ---------- Retardo del audio ----------

    /** Retardo del sonido de lo que se está viendo (ms; positivo = el sonido va más tarde). */
    var audioDelayMs by mutableIntStateOf(0)
        private set

    /** Se recuerda por canal, por película o por serie entera (no por episodio). */
    private fun delayKey(): String {
        val k = current.channel?.key ?: current.key
        return if (k.startsWith("EP|")) k.substringBeforeLast('|') else k
    }

    private fun loadAudioDelay() {
        audioDelayMs = Repo.prefs.audioDelay(delayKey())
        AudioDelay.ms = audioDelayMs
    }

    fun changeAudioDelay(deltaMs: Int) {
        val v = (audioDelayMs + deltaMs).coerceIn(-3000, 3000)
        audioDelayMs = v
        AudioDelay.ms = v
        Repo.prefs.setAudioDelay(delayKey(), v)
    }

    fun resetAudioDelay() = changeAudioDelay(-audioDelayMs)

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
    private var restoredTextFor: String? = null

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

    private fun restoreTracks(type: Int) {
        val saved = Repo.prefs.trackChoice(current.key) ?: return
        if (type == C.TRACK_TYPE_AUDIO) {
            saved.optString("a").takeIf { it.isNotEmpty() }?.let { id -> findTrack(C.TRACK_TYPE_AUDIO, id)?.let { applyTrack(C.TRACK_TYPE_AUDIO, it) } }
            // «Subtítulos desactivados» no necesita que existan pistas de texto.
            if (saved.optString("t") == "off") applyTrack(C.TRACK_TYPE_TEXT, null)
            return
        }
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
            // Quitar los subtítulos vale solo para este vídeo (la elección se guarda en selectTrack);
            // antes los quitaba también en todos los demás.
            b.setTrackTypeDisabled(type, true)
        } else {
            val groups = player.currentTracks.groups
            if (opt.groupIndex !in groups.indices) return
            val g = groups[opt.groupIndex]
            b.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, opt.trackIndex))
            val format = g.getTrackFormat(opt.trackIndex)
            if (type == C.TRACK_TYPE_AUDIO) {
                // Antes, al elegir una pista a mano cambiaba también el idioma preferido de toda la app:
                // si un día elegías el inglés, desde entonces todo lo demás salía en inglés. Ahora la
                // elección queda solo para este vídeo (se guarda en selectTrack) y el resto sigue
                // saliendo en el idioma de Ajustes. «und» (sin idioma) no cuenta como idioma.
                val lang = format.language?.takeIf { it.isNotBlank() && it != "und" }
                if (lang != null) b.setPreferredAudioLanguages(lang)
            } else {
                // Igual que el audio: la elección es solo para este vídeo; el idioma de Ajustes no cambia.
                val lang = format.language?.takeIf { it.isNotBlank() && it != "und" }
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
            // Tipo de sonido y canales: «Español · AC3 5.1», «Inglés · AAC estéreo».
            val codec = audioCodecName(f)
            val ch = when (f.channelCount) {
                1 -> "mono"
                2 -> "estéreo"
                6 -> "5.1"
                8 -> "7.1"
                else -> null
            }
            listOfNotNull(codec, ch).joinToString(" ").takeIf { it.isNotBlank() }?.let { parts.add(it) }
            if ((f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) parts.add("audiodescripción")
            if ((f.roleFlags and C.ROLE_FLAG_COMMENTARY) != 0) parts.add("comentarios")
        }
        if (type == C.TRACK_TYPE_TEXT && (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) parts.add("forzados")
        return parts.joinToString(" · ")
    }

    fun start() {
        if (items.isEmpty()) return
        val it = items[PlayQueue.index]
        val alt = usesAlt(it)
        val shown = if (alt) swapLiveFormat(it.url) else it.url
        // Viene de la previsualización y ya se está viendo ese canal: se sigue sin cortar.
        if (PlayQueue.handoff && it.live && PlayerHolder.currentUrl() == shown && player.playbackState != Player.STATE_IDLE) {
            altLiveFormat = alt
            index = PlayQueue.index
            loadAudioDelay()
            error = null
            buffering = player.playbackState == Player.STATE_BUFFERING
            isPlaying = player.isPlaying
            if (!player.isPlaying) player.play()
            it.channel?.let { ch -> Repo.prefs.lastLive = ch.key }
            loadEpg()
            // El vídeo ya estaba en marcha en la previsualización: no llegará un aviso de «pistas nuevas», así que
            // se toman ahora sus datos (y se vuelven a medir los fps) para ajustar la frecuencia de la pantalla.
            firstFrame = true
            measuredFps = 0f
            frameCount = 0
            player.videoFormat?.let { vf ->
                videoSelected = true
                lastVideoFormat = vf
                videoFormat = vf
                refreshVideoInfo()
                onVideoFormat?.invoke(vf)
            }
            return
        }
        play(PlayQueue.index, PlayQueue.startPos)
    }

    /** Al salir de la pantalla completa a la lista de canales, la emisión sigue en la previsualización. */
    // Solo en la tele: en móvil y tablet no hay previsualización en la lista, así que al salir se corta (si no, seguía sonando).
    private fun keepForPreview(finishing: Boolean): Boolean =
        finishing && PlayQueue.handoff && current.live && !com.suso.miiptv.data.Device.touch

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
        noImageReloads = 0
        autoReloads = 0
        goodSince = 0L
        reloadPending = false
        triedSoftware = false
        PlayerHolder.softwareVideo = false
        autoSwitched = false
        pendingFormatSave = false
        lastVideoFormat = null
        decoderName = null
        videoInfo = null
        saveProgress()
        lastAutoSave = 0L
        scrubPos = null
        restoredFor = null
        restoredTextFor = null
        handler.removeCallbacksAndMessages(null)
        index = i
        loadAudioDelay()
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
        // Formato elegido para este canal (TS o HLS).
        altLiveFormat = usesAlt(it)
        load(it.url, pos, false)
        if (it.live) {
            it.channel?.let { ch -> Repo.prefs.lastLive = ch.key }
            loadEpg()
        } else {
            epg = emptyList()
        }
    }

    // ---------- Formato de emisión (TS / HLS) y frecuencia de la pantalla, desde las opciones ----------

    /** true = se usa el otro formato (TS ↔ HLS) en los canales, para probar si alguno va mejor. */
    var altLiveFormat by mutableStateOf(false)
        private set

    /** En el móvil los canales SAT solo van en HLS: se abren así por defecto (si la dirección es TS). */
    private fun defaultAlt(it: PlayItem): Boolean =
        it.live && com.suso.miiptv.data.Device.touch && SAT_NAME.containsMatchIn(it.title) &&
            !it.url.contains(".m3u8", true) && swapLiveFormat(it.url) != it.url

    private fun usesAlt(it: PlayItem): Boolean =
        it.live && (defaultAlt(it) || it.channel?.let { c -> Repo.prefs.usesAltFormat(c.key) } == true)

    /** Se recuerda el formato elegido para el canal (los SAT del móvil vuelven siempre a HLS al abrirlos). */
    private fun saveAltFormat() {
        val it = current
        if (defaultAlt(it)) return
        it.channel?.let { c -> Repo.prefs.setAltFormat(c.key, altLiveFormat) }
    }

    /** Formato con el que se está viendo el canal: "TS" o "HLS". */
    fun liveFormatLabel(): String {
        val u = if (altLiveFormat) swapLiveFormat(current.url) else current.url
        return if (u.contains(".m3u8", true)) "HLS" else "TS"
    }

    /** Cambia entre TS y HLS y vuelve a abrir el canal. */
    fun toggleLiveFormat() {
        if (!current.live) return
        altLiveFormat = !altLiveFormat
        // Se recuerda para este canal: la próxima vez se abre ya con el formato elegido.
        saveAltFormat()
        triedHls = false
        load(current.url, 0, false)
    }

    /** Última imagen conocida del vídeo (para la frecuencia de la pantalla y sus fps). */
    var videoFormat by mutableStateOf<Format?>(null)
        private set

    /** Vuelve a aplicar (o quitar) el ajuste de la frecuencia de la pantalla con el vídeo actual. */
    fun reapplyFrameRate() {
        videoFormat?.let { onVideoFormat?.invoke(it) }
    }

    private fun load(rawUrl: String, pos: Long, forceHls: Boolean) {
        val url = if (altLiveFormat && current.live) swapLiveFormat(rawUrl) else rawUrl
        val item = PlayerHolder.mediaItem(url, forceHls)
        buffering = true
        firstFrame = false
        noFrameTicks = 0
        videoProblem = null
        measuredFps = 0f
        frameCount = 0
        player.setMediaItem(item, pos)
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
        autoReloads = 0
        reloadPending = false
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
        // Cortes de red, y servidor saturado o caído un momento (HTTP 500, 502, 503 y 504, muy típicos en IPTV):
        // reintentamos solos unas cuantas veces.
        val httpStatus = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val busyServer = code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && httpStatus in TRANSIENT_HTTP
        if (code in 2000..2999 && (code != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS || busyServer) && retries < 3) {
            retries++
            buffering = true
            val idx = index
            handler.postDelayed({
                if (idx == index) load(current.url, if (current.live) 0 else position, triedHls)
            }, 2000L * retries)
            return
        }
        // Programa emitido / desde el principio: cada proveedor usa un formato distinto; se prueba el siguiente.
        if (it.key.startsWith("CU|") && code in 2000..3999) {
            // Se apunta cada intento (sin usuario ni contraseña) para ver en Ajustes qué responde el servidor.
            val http = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            com.suso.miiptv.data.ErrorLog.add("Desde el principio: ${http?.let { c -> "HTTP $c" } ?: e.errorCodeName} en ${maskUrl(it.url)}")
            val next = Repo.nextCatchup(it.url)
            if (next != null) {
                val n = it.copy(url = next)
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                    items = items.map { x -> if (x === it) n else x }
                }
                triedHls = false
                load(next, 0, false)
                return
            }
        }
        // El decodificador de vídeo del aparato falla: se prueba una vez con el de software.
        if (code in 4000..4999 && trySoftware("el decodificador del aparato falla (${e.errorCodeName})")) return
        com.suso.miiptv.data.ErrorLog.add("Reproducción («${it.title}»): ${e.errorCodeName} (${e.message ?: "sin detalle"})")
        // Directo que no abre: antes de dar el error, se prueba solo el otro formato (TS ↔ HLS).
        if (it.live && code in 2000..4999 && tryOtherFormat("no abre")) return
        // Directo que no hay manera de abrir: plan B con la VPN antes de dar el error.
        if (it.live && code in 2000..2999 && tryVpnFallback()) return
        buffering = false
        error = when {
            code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                val http = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                httpMessage(http, catchup = it.key.startsWith("CU|"), live = it.live)
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
            // Un directo que "termina" suele ser un corte: volvemos a conectar (con un límite de intentos).
            reconnectLive("emisión terminada", null)
            return
        }
        saveProgress()
        // Temporizador «al terminar este vídeo»: no se pasa al siguiente.
        if (SleepTimer.atEnd) {
            SleepTimer.atEnd = false
            com.suso.miiptv.ui.AppToast.show("Temporizador: reproducción detenida", true)
            onFinished?.invoke()
            return
        }
        if (index < items.lastIndex && Repo.prefs.autoNext) {
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
        // Lista e índice cambian a la vez (si no, durante un instante el índice apunta fuera de la lista nueva).
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            items = listOf(pi)
            index = 0
        }
        play(0, 0)
    }

    /** Si se está viendo un programa desde el principio, vuelve al directo. Devuelve false si no. */
    fun backToLive(): Boolean {
        val b = liveBack ?: return false
        liveBack = null
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            items = b.first
            index = b.second.coerceIn(0, b.first.lastIndex)
        }
        play(index, 0)
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
    /** Reconexiones de este canal sin haber salido nunca ninguna imagen. */
    private var noImageReloads = 0

    /** Reconexiones automáticas seguidas de este directo sin llegar a verse bien un rato. */
    private var autoReloads = 0
    /** Desde cuándo se ve bien el directo (0 = ahora no). */
    private var goodSince = 0L
    /** Hay una reconexión programada: el vigilante no programa otra. */
    private var reloadPending = false

    /**
     * Reconecta un directo que se ha cortado o congelado, cada vez esperando algo más (1,5 s, 3, 6, 12 y 20 s).
     * Tras [MAX_AUTO_RELOADS] intentos seguidos sin que el canal llegue a verse, se deja de insistir y se avisa:
     * un canal caído ya no gasta conexión ni calienta el aparato sin fin.
     */
    private fun reconnectLive(why: String, msg: String?) {
        if (reloadPending) return
        autoReloads++
        if (autoReloads > MAX_AUTO_RELOADS) {
            com.suso.miiptv.data.ErrorLog.add("«${current.title}»: no responde tras $MAX_AUTO_RELOADS reconexiones ($why); se deja de intentar")
            handler.removeCallbacksAndMessages(null)
            player.stop()
            buffering = false
            notice = null
            error = "Este canal no responde ahora mismo. Pulsa Reintentar o prueba más tarde."
            return
        }
        val wait = (1500L shl (autoReloads - 1).coerceAtMost(4)).coerceAtMost(20_000L)
        if (msg != null) notice = "$msg (intento $autoReloads de $MAX_AUTO_RELOADS)"
        reloadPending = true
        val idx = index
        handler.postDelayed({
            reloadPending = false
            if (idx == index && current.live) load(current.url, 0, triedHls)
        }, wait)
    }

    private fun resetWatchdog() {
        lastPos = -1L
        stuckSince = 0L
    }

    /** Se llama cada medio segundo: si la imagen de un directo no avanza, se reconecta el canal solo. */
    private fun watchdog() {
        if (!current.live || error != null || videoProblem != null || !player.playWhenReady || player.mediaItemCount == 0) {
            stuckSince = 0L
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val pos = player.currentPosition
        val advancing = player.playbackState == Player.STATE_READY && player.isPlaying && pos != lastPos
        lastPos = pos
        if (advancing) {
            stuckSince = 0L
            // Medio minuto viéndose bien: la cuenta de reconexiones vuelve a empezar.
            if (goodSince == 0L) goodSince = now else if (now - goodSince > 30_000L) autoReloads = 0
            return
        }
        goodSince = 0L
        if (reloadPending) return
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
        val estado = when (player.playbackState) { Player.STATE_BUFFERING -> "cargando"; Player.STATE_READY -> "parado"; else -> "estado ${player.playbackState}" }
        com.suso.miiptv.data.ErrorLog.add(
            "Imagen congelada en «${current.title}» ($estado" + (if (firstFrame) "" else ", sin ninguna imagen") + ") · vídeo: " +
                (videoInfo ?: "sin datos del vídeo"),
        )
        // Sin ninguna imagen, o cortes repetidos: se prueba solo el otro formato (TS ↔ HLS).
        if ((!firstFrame || freezeCount >= 2) && tryOtherFormat("imagen congelada")) return
        // Nunca salió imagen y ya se reconectó: no es un corte, es que el aparato no muestra este vídeo.
        if (!firstFrame && player.playbackState == Player.STATE_READY) {
            noImageReloads++
            if (noImageReloads >= 2) {
                reportVideoProblem("no consigue mostrar su vídeo (" + (videoInfo ?: codecName(lastVideoFormat?.sampleMimeType)) + ")")
                return
            }
        }
        // Tres cortes en dos minutos: plan B con la VPN (si está permitido).
        if (freezeCount >= 3 && tryVpnFallback()) return
        reconnectLive("imagen congelada", "Imagen congelada · reconectando el canal")
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

    /** Salta directamente (sin la barra de avance), p. ej. para saltar la intro. */
    fun skipBy(ms: Long) {
        val d = player.duration
        val t = (player.currentPosition + ms).let { if (d > 0) it.coerceAtMost(d - 1000) else it }.coerceAtLeast(0)
        player.seekTo(t)
        position = t
    }

    /** Pasa al siguiente de la lista (siguiente episodio), donde se dejó si ya se había empezado. */
    fun playNext(): Boolean {
        if (current.live || index >= items.lastIndex) return false
        val next = index + 1
        play(next, Repo.prefs.progress(items[next].key)?.pos ?: 0L)
        return true
    }

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

    /** Arrastrando la barra (móvil): se mueve la marca y se ve el minuto; se salta al soltar. */
    fun scrubToFraction(f: Float) {
        if (current.live) return
        val d = player.duration
        if (d <= 0) return
        handler.removeCallbacks(commitScrubRunnable)
        scrubPos = (d * f.coerceIn(0f, 1f)).toLong().coerceAtMost(d - 1000).coerceAtLeast(0)
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
        // Se oye pero no se ve: el vídeo está elegido y avanza, pero en 8 s no ha salido ninguna imagen.
        if (!firstFrame && videoSelected && player.isPlaying) {
            noFrameTicks++
            if (noFrameTicks >= 16) reportVideoProblem("el decodificador de este aparato no muestra su vídeo (${codecName(lastVideoFormat?.sampleMimeType)})")
        }
        // Película o episodio: el punto por el que vas se guarda cada minuto (antes solo al salir del vídeo,
        // y si la app se cerraba de golpe o se iba la luz se perdía lo visto).
        if (!current.live && player.isPlaying) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastAutoSave == 0L) {
                lastAutoSave = now
            } else if (now - lastAutoSave >= AUTO_SAVE_MS) {
                lastAutoSave = now
                saveProgress()
            }
        }
        watchdog()
    }

    /** Última vez que se guardó solo el punto por el que vas (0 = aún no en este vídeo). */
    private var lastAutoSave = 0L

    fun isFavorite(): Boolean = current.channel?.let { Repo.prefs.isFavorite(it.key) } ?: false

    fun toggleFavorite(): Boolean? = current.channel?.let { Repo.prefs.toggleFavorite(it) }

    fun saveProgress() {
        if (player.mediaItemCount == 0 || index !in items.indices || current.live) return
        // Lo visto «desde el principio» o ya emitido no va a «Continuar viendo».
        if (current.key.startsWith("CU|")) return
        val d = player.duration
        if (d <= 0) return
        val pos = player.currentPosition
        Repo.prefs.saveProgress(current, pos, d)
        // Episodio terminado: el siguiente pasa a «Continuar viendo» (desde el principio) si aún no lo has empezado.
        if (current.key.startsWith("EP|") && pos >= d * com.suso.miiptv.data.Prefs.FINISHED) {
            items.getOrNull(index + 1)
                ?.takeIf { it.key.startsWith("EP|") && Repo.prefs.progress(it.key) == null }
                ?.let { Repo.prefs.queueNext(it) }
        }
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
        // La previsualización va siempre sin retardo.
        AudioDelay.ms = 0
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        try {
            loudness?.release()
        } catch (e: Exception) {
            // Sin importancia.
        }
        player.removeListener(listener)
        player.removeAnalyticsListener(analytics)
        player.clearVideoFrameMetadataListener(frameMeter)
        if (speed != 1f) player.setPlaybackSpeed(1f)
        // El reproductor es compartido: no se destruye, solo se para (salvo si sigue en la previsualización).
        if (!keepForPreview(true)) {
            player.stop()
            player.clearMediaItems()
        }
    }
}

private val SAT_NAME = Regex("\\bSAT\\b", RegexOption.IGNORE_CASE)


/** Nombre del formato de vídeo para mostrarlo. */
/** Nombre corto del tipo de sonido de una pista (null si no se sabe). */
internal fun audioCodecName(f: Format): String? {
    val m = f.sampleMimeType ?: return null
    val c = f.codecs.orEmpty().lowercase()
    return when (m) {
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_E_AC3 -> "E-AC3"
        MimeTypes.AUDIO_E_AC3_JOC -> "Atmos"
        MimeTypes.AUDIO_AC4 -> "AC4"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG_L2, MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_FLAC,
        MimeTypes.AUDIO_VORBIS -> when (m) {
            MimeTypes.AUDIO_AAC -> if (c.contains("mp4a.40.5") || c.contains("mp4a.40.29")) "HE-AAC" else "AAC"
            MimeTypes.AUDIO_MPEG_L2 -> "MP2"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            else -> "Vorbis"
        }
        else -> m.substringAfter('/').substringAfter("x-").uppercase().takeIf { it.length <= 8 }
    }
}

internal fun codecName(mime: String?): String = when (mime) {
    MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_H265 -> "H.265"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.VIDEO_VP9 -> "VP9"
    null -> "desconocido"
    else -> mime.substringAfter('/').uppercase()
}

/** El otro formato de emisión de un canal: «.ts» ↔ «.m3u8» (si la dirección no acaba en ninguno, igual). */
/** Respuestas del servidor que suelen ser pasajeras (saturado o reiniciándose): se reintenta. */
private val TRANSIENT_HTTP = setOf(500, 502, 503, 504)

/** Reconexiones automáticas seguidas de un directo antes de dejar de intentarlo. */
private const val MAX_AUTO_RELOADS = 6

/** Cada cuánto se guarda solo el punto por el que vas en una película o episodio. */
private const val AUTO_SAVE_MS = 60_000L

internal fun swapLiveFormat(url: String): String {
    // La extensión va antes de «?» o «#» (direcciones con «?token=…»).
    val cut = url.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) url.length else it }
    val path = url.substring(0, cut)
    val rest = url.substring(cut)
    return when {
        path.endsWith(".m3u8", true) -> path.dropLast(5) + ".ts" + rest
        path.endsWith(".ts", true) -> path.dropLast(3) + ".m3u8" + rest
        else -> url
    }
}

/**
 * Qué decir según el código con el que el servidor rechaza un vídeo: no es lo mismo que no exista (404)
 * que una cuenta sin acceso (401/403) o el límite de conexiones (429/509).
 */
internal fun httpMessage(http: Int?, catchup: Boolean, live: Boolean): String {
    val c = if (http != null) " (HTTP $http)" else ""
    val what = if (live) "este canal" else "este contenido"
    return when (http) {
        404, 410 ->
            if (catchup) "Este programa no está grabado en el servidor$c."
            else "El servidor dice que $what no existe$c. Puede que lo hayan quitado de la lista."
        401, 403 ->
            "La cuenta no tiene acceso ahora mismo$c. Puede que haya otra conexión abierta con la misma cuenta " +
                "o que la suscripción haya caducado."
        429, 509 -> "Se ha llegado al límite de conexiones de la cuenta$c. Cierra la app en el otro aparato y vuelve a intentarlo."
        in 500..599 -> "El servidor tiene un problema ahora mismo$c. Prueba dentro de un rato."
        else -> "El servidor no deja abrir $what$c."
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

/** Dirección sin usuario ni contraseña, para el registro de errores. */
internal fun maskUrl(url: String): String = url
    .replace(Regex("^https?://[^/]+"), "")
    .replace(Regex("/timeshift/[^/]+/[^/]+/"), "/timeshift/*/*/")
    .replace(Regex("username=[^&]*&password=[^&]*"), "username=*&password=*")
