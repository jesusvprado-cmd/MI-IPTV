package com.suso.miiptv.player

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.suso.miiptv.data.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Miniaturas al avanzar o retroceder en películas y series (como en Netflix).
 * Se sacan del propio vídeo: se lee solo el trozo necesario del archivo y se toma el fotograma clave más cercano.
 * Si el servidor no lo permite (o el vídeo es HLS), simplemente no se muestran.
 */
object Thumbs {
    /** Miniatura lista: posición (ms, redondeada) e imagen. */
    var frame by mutableStateOf<Pair<Long, Bitmap>?>(null)
        private set

    /** Motivo por el que no hay miniaturas para este vídeo (se muestra en lugar de la imagen). */
    var error by mutableStateOf<String?>(null)
        private set

    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var worker: Job? = null
    @Volatile private var url: String? = null
    private var retriever: MediaMetadataRetriever? = null
    @Volatile private var failed = false
    private val cache = LruCache<Long, Bitmap>(90)

    /** Lo último que se ha pedido (se atiende siempre primero) y las que se adelantan en la dirección del avance. */
    @Volatile private var wanted: Long? = null
    private val ahead = java.util.concurrent.ConcurrentLinkedDeque<Long>()
    private var lastAsked: Long? = null
    private var lastShownFor: Long = -1

    private const val STEP_MS = 10_000L
    private const val NEAR_MS = 90_000L

    fun key(pos: Long) = (pos / STEP_MS) * STEP_MS

    /** ¿La miniatura [f] sirve para la posición [pos]? (la exacta o una cercana mientras llega la exacta) */
    fun fits(f: Pair<Long, Bitmap>?, pos: Long): Boolean = f != null && kotlin.math.abs(f.first - key(pos)) <= NEAR_MS

    /** Pide la miniatura de [pos] para el vídeo [videoUrl]. */
    fun request(videoUrl: String, pos: Long) {
        if (Build.VERSION.SDK_INT < 23) {
            error = "Necesita Android 6 o superior"
            return
        }
        if (videoUrl != url) reset(videoUrl)
        if (videoUrl.contains(".m3u8", true)) {
            error = "No disponible en vídeos HLS"
            return
        }
        if (failed) return
        val k = key(pos)
        // Se adelantan las siguientes en la dirección en que avanzas.
        val prev = lastAsked
        lastAsked = k
        lastShownFor = k
        showBest(k)
        if (cache.get(k) != null) return
        wanted = k
        if (prev != null && prev != k) {
            val step = k - prev
            ahead.clear()
            for (i in 1..3) ahead.add(key(k + step * i).coerceAtLeast(0))
        }
        ensureWorker()
    }

    /** Muestra la miniatura exacta o, si aún no está, la más cercana que ya tengamos. */
    private fun showBest(k: Long) {
        cache.get(k)?.let {
            frame = k to it
            return
        }
        val near = cache.snapshot().keys.minByOrNull { kotlin.math.abs(it - k) }
        if (near != null && kotlin.math.abs(near - k) <= NEAR_MS) cache.get(near)?.let { frame = near to it }
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val k = wanted?.also { wanted = null } ?: ahead.pollFirst() ?: break
                if (cache.get(k) != null || failed) continue
                val u = url
                val bmp = withContext(io) { if (u == url) grab(k) else null }
                if (bmp != null) {
                    error = null
                    cache.put(k, bmp)
                    showBest(lastShownFor)
                }
                if (failed) break
            }
        }
    }

    private fun reset(newUrl: String?) {
        worker?.cancel()
        worker = null
        wanted = null
        ahead.clear()
        lastAsked = null
        val r = retriever
        retriever = null
        val ex = extractor
        extractor = null
        val c = codec
        codec = null
        source = null
        videoTrack = -1
        scope.launch(io) {
            runCatching { r?.release() }
            runCatching { ex?.release() }
            runCatching { c?.release() }
        }
        url = newUrl
        failed = false
        error = null
        cache.evictAll()
        frame = null
    }

    /** Libera todo al salir del reproductor o cambiar de vídeo. */
    fun release() = reset(null)

    private fun fail(msg: String) {
        failed = true
        scope.launch { error = msg }
    }

    private var source: HttpSource? = null
    private var extractor: android.media.MediaExtractor? = null
    private var videoTrack = -1
    private var codec: android.media.MediaCodec? = null

    @RequiresApi(23)
    private fun grab(k: Long): Bitmap? {
        val u = url ?: return null
        val src = source ?: HttpSource(u).also { source = it }
        val us = k * 1000
        // 1) Decodificador por software: no compite con el del reproductor (en la Mi Box solo hay uno de hardware).
        val soft = try {
            softwareFrame(src, us)
        } catch (e: Throwable) {
            null
        }
        if (soft != null) return soft
        // 2) Método estándar de Android.
        return try {
            val r = retriever ?: MediaMetadataRetriever().also {
                it.setDataSource(src)
                retriever = it
            }
            val b = if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            } else {
                r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                    val h = (320f * full.height / full.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(full, 320, h, true).also { if (it !== full) full.recycle() }
                }
            }
            if (b == null) fail(src.lastError ?: "El vídeo no deja sacar imágenes")
            b
        } catch (e: Throwable) {
            fail(
                src.lastError ?: when {
                    e is IOException && e.message != null -> e.message!!
                    else -> "El vídeo no deja sacar imágenes"
                }
            )
            null
        }
    }

    /** Busca el fotograma clave anterior a [us] y lo decodifica con un decodificador de software. */
    @RequiresApi(23)
    private fun softwareFrame(src: HttpSource, us: Long): Bitmap? {
        val ex = extractor ?: android.media.MediaExtractor().also {
            it.setDataSource(src)
            extractor = it
            for (i in 0 until it.trackCount) {
                if (it.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrack = i
                    break
                }
            }
            if (videoTrack >= 0) it.selectTrack(videoTrack)
        }
        if (videoTrack < 0) return null
        val fmt = ex.getTrackFormat(videoTrack)
        val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: return null
        val name = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { ci ->
                !ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) } &&
                    (ci.name.startsWith("OMX.google.") || ci.name.startsWith("c2.android.") ||
                        (Build.VERSION.SDK_INT >= 29 && ci.isSoftwareOnly))
            }?.name ?: return null
        ex.seekTo(us, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        // El decodificador se reutiliza entre miniaturas (crearlo cada vez es lento).
        val codec = this.codec ?: android.media.MediaCodec.createByCodecName(name).also {
            fmt.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            it.configure(fmt, null, null, 0)
            it.start()
            this.codec = it
        }
        try {
            codec.flush()
            val info = android.media.MediaCodec.BufferInfo()
            var fed = 0
            var eos = false
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                if (!eos) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = if (fed < 1) ex.readSampleData(buf, 0) else -1
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                            fed++
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    if (info.size > 0) {
                        val img = codec.getOutputImage(oi)
                        val bmp = img?.let { toBitmap(it) }
                        img?.close()
                        codec.releaseOutputBuffer(oi, false)
                        if (bmp != null) return bmp
                    } else {
                        codec.releaseOutputBuffer(oi, false)
                    }
                    if ((info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
            return null
        } catch (e: Throwable) {
            runCatching { codec.release() }
            this.codec = null
            throw e
        }
    }

    /** Imagen YUV del decodificador → miniatura de 320 px. */
    private fun toBitmap(img: android.media.Image): Bitmap? {
        if (img.format != android.graphics.ImageFormat.YUV_420_888) return null
        val crop = img.cropRect
        val w = crop.width()
        val h = crop.height()
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]
        val u = img.planes[1]
        val v = img.planes[2]
        var o = 0
        for (row in 0 until h) {
            val base = (row + crop.top) * y.rowStride + crop.left * y.pixelStride
            for (col in 0 until w) nv21[o++] = y.buffer.get(base + col * y.pixelStride)
        }
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val vi = (row + crop.top / 2) * v.rowStride + (col + crop.left / 2) * v.pixelStride
                val ui = (row + crop.top / 2) * u.rowStride + (col + crop.left / 2) * u.pixelStride
                nv21[o++] = v.buffer.get(vi)
                nv21[o++] = u.buffer.get(ui)
            }
        }
        val out = java.io.ByteArrayOutputStream()
        android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
            .compressToJpeg(android.graphics.Rect(0, 0, w, h), 80, out)
        val bytes = out.toByteArray()
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit((w / 320).coerceAtLeast(1))
        }
        val full = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val th = (320f * full.height / full.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(full, 320, th, true).also { if (it !== full) full.recycle() }
        return scaled.copy(Bitmap.Config.RGB_565, false).also { scaled.recycle() }
    }

    /**
     * Lee el vídeo por trozos con peticiones HTTP «Range» (con la VPN y la identificación de la app),
     * guardando los últimos trozos leídos para no repetir descargas.
     */
    @RequiresApi(23)
    private class HttpSource(private val url: String) : MediaDataSource() {
        private val block = 256 * 1024
        private val blocks = LruCache<Long, ByteArray>(24)
        private var total = -1L

        /** Último problema de red (para explicarlo en pantalla). */
        @Volatile
        var lastError: String? = null

        private fun fetch(index: Long): ByteArray {
            blocks.get(index)?.let { return it }
            val start = index * block
            val req = Request.Builder().url(url)
                .header("User-Agent", Http.userAgent)
                .header("Range", "bytes=$start-${start + block - 1}")
                .build()
            Http.client.newCall(req).execute().use { r ->
                if (r.code != 206) {
                    val msg = if (r.code == 200) "El servidor no permite leer partes del vídeo"
                    else "El servidor no da una segunda conexión (HTTP ${r.code})"
                    lastError = msg
                    throw IOException(msg)
                }
                if (total < 0) {
                    total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                }
                val data = r.body?.bytes() ?: ByteArray(0)
                blocks.put(index, data)
                return data
            }
        }

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (total >= 0 && position >= total) return -1
            var done = 0
            var pos = position
            while (done < size) {
                val idx = pos / block
                val data = try {
                    fetch(idx)
                } catch (e: IOException) {
                    if (lastError == null) {
                        lastError = when (e) {
                            is java.net.SocketTimeoutException -> "El servidor tarda demasiado"
                            is java.net.UnknownHostException -> "Sin conexión con el servidor"
                            else -> e.message ?: "Error de red"
                        }
                    }
                    throw e
                }
                val inBlock = (pos - idx * block).toInt()
                if (inBlock >= data.size) break
                val n = minOf(size - done, data.size - inBlock)
                System.arraycopy(data, inBlock, buffer, offset + done, n)
                done += n
                pos += n
                if (data.size < block) break
            }
            return if (done == 0) -1 else done
        }

        override fun getSize(): Long {
            if (total < 0) runCatching { fetch(0) }
            return total
        }

        override fun close() {
            blocks.evictAll()
        }
    }
}
