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

    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var url: String? = null
    private var retriever: MediaMetadataRetriever? = null
    private var failed = false
    private val cache = LruCache<Long, Bitmap>(40)

    private const val STEP_MS = 10_000L

    fun key(pos: Long) = (pos / STEP_MS) * STEP_MS

    /** Pide la miniatura de [pos] para el vídeo [videoUrl] (se espera un poco por si sigues pulsando). */
    fun request(videoUrl: String, pos: Long) {
        if (Build.VERSION.SDK_INT < 23) return
        if (videoUrl != url) reset(videoUrl)
        if (failed || videoUrl.contains(".m3u8", true)) return
        val k = key(pos)
        cache.get(k)?.let {
            frame = k to it
            return
        }
        job?.cancel()
        job = scope.launch {
            delay(220)
            val bmp = withContext(io) { grab(k) } ?: return@launch
            cache.put(k, bmp)
            frame = k to bmp
        }
    }

    private fun reset(newUrl: String?) {
        job?.cancel()
        val r = retriever
        retriever = null
        if (r != null) scope.launch(io) { runCatching { r.release() } }
        url = newUrl
        failed = false
        cache.evictAll()
        frame = null
    }

    /** Libera todo al salir del reproductor o cambiar de vídeo. */
    fun release() = reset(null)

    @RequiresApi(23)
    private fun grab(k: Long): Bitmap? {
        val u = url ?: return null
        return try {
            val r = retriever ?: MediaMetadataRetriever().also {
                it.setDataSource(HttpSource(u))
                retriever = it
            }
            val us = k * 1000
            val b = if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            } else {
                r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                    val h = (320f * full.height / full.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(full, 320, h, true).also { if (it !== full) full.recycle() }
                }
            }
            b
        } catch (e: Throwable) {
            failed = true
            null
        }
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

        private fun fetch(index: Long): ByteArray {
            blocks.get(index)?.let { return it }
            val start = index * block
            val req = Request.Builder().url(url)
                .header("User-Agent", Http.userAgent)
                .header("Range", "bytes=$start-${start + block - 1}")
                .build()
            Http.client.newCall(req).execute().use { r ->
                if (r.code != 206) throw IOException("El servidor no permite leer trozos (HTTP ${r.code})")
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
                val data = fetch(idx)
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
