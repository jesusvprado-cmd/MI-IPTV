package com.suso.miiptv.data

import com.suso.miiptv.vpn.VpnManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    const val DEFAULT_UA = "VLC/3.0.20 LibVLC/3.0.20"

    @Volatile
    var userAgent: String = DEFAULT_UA

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // «Solo con VPN»: sin VPN conectada no sale nada (listas, guía, logos, vídeo...).
            .addInterceptor { chain ->
                val r = chain.request()
                if (r.url.host !in VPN_SETUP_HOSTS && VpnManager.blocksTraffic()) throw VpnRequired()
                chain.proceed(r)
            }
            .build()
    }

    /** Lo que hace falta para poder conectar la VPN (alta de la cuenta WARP): se deja pasar siempre. */
    private val VPN_SETUP_HOSTS = setOf("api.cloudflareclient.com")

    /**
     * Para descargar catálogo, guías y logos: el mismo cliente, pero con un tiempo máximo total de 10 minutos
     * (antes, una guía enorme que llegaba a gotas podía dejar la descarga colgada para siempre).
     * El reproductor sigue con [client], sin límite total (un directo dura lo que haga falta).
     */
    private val dataClient: OkHttpClient by lazy { client.newBuilder().callTimeout(10, TimeUnit.MINUTES).build() }

    @Volatile
    private var busyUntil = 0L

    /** El servidor ha pedido esperar (HTTP 429) hace menos de 5 minutos. */
    fun busy(): Boolean = System.currentTimeMillis() < busyUntil

    fun open(url: String): Response {
        // Si la VPN se está conectando, esperamos a que termine para no salir sin ella.
        var waited = 0
        while (VpnManager.isConnecting() && waited < 15_000) {
            Thread.sleep(100)
            waited += 100
        }
        val req = try {
            Request.Builder().url(url).header("User-Agent", userAgent).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("La dirección no es válida")
        }
        val r = try {
            dataClient.newCall(req).execute()
        } catch (e: java.net.UnknownHostException) {
            throw IOException("No se encuentra el servidor. Revisa la dirección y tu conexión.")
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("El servidor tarda demasiado en responder.")
        } catch (e: java.io.InterruptedIOException) {
            throw IOException("La descarga ha tardado demasiado y se ha cortado.")
        }
        if (!r.isSuccessful) {
            val c = r.code
            r.close()
            // «Demasiadas peticiones»: durante 5 minutos no se pide más guía (el proveedor corta si se insiste).
            if (c == 429) {
                busyUntil = System.currentTimeMillis() + 5 * 60_000L
                throw IOException("El servidor pide esperar un poco (demasiadas peticiones, HTTP 429).")
            }
            throw IOException(httpMessage(c))
        }
        return r
    }

    /** Mensaje para la persona, sin jerga: qué pasa y qué hacer (el código va al final, para poder consultarlo). */
    fun httpMessage(c: Int): String = when (c) {
        401, 403 -> "Acceso denegado. Revisa el usuario y la contraseña, o si la suscripción ha caducado (HTTP $c)."
        404 -> "El servidor no reconoce esa dirección. Revisa la dirección de la lista (HTTP $c)."
        in 520..527, 502, 503, 504 ->
            "El servidor de tu proveedor no responde: está caído o saturado. Suele arreglarse solo; prueba dentro de un rato (HTTP $c)."
        in 500..599 -> "El servidor de tu proveedor ha fallado. Prueba dentro de un rato (HTTP $c)."
        else -> "El servidor respondió con un error (HTTP $c)."
    }

    fun text(url: String): String = open(url).use { it.body?.string() ?: "" }
}

/** Ajuste «Solo con VPN» activado y la VPN no está conectada: no se sale a internet. */
class VpnRequired : IOException("La VPN no está conectada y tienes activado «Solo con VPN». Conéctala en Ajustes → VPN.")

/** La respuesta del servidor se cortó a medias: lleva lo que se pudo leer (no se guarda como copia buena). */
class PartialRead(val partial: Any, cause: Throwable?) : IOException("La respuesta del servidor llegó cortada", cause)

/**
 * Respuestas del servidor guardadas en el aparato (catálogo, lista M3U, guía de cada canal).
 * La app arranca con lo guardado al momento y solo vuelve a descargar cuando la copia es vieja;
 * si el servidor no responde, se usa la copia aunque sea vieja. Solo se guardan respuestas que valen
 * (nunca una lista vacía o un error del servidor). «Actualizar lista» lo borra todo.
 */
object DiskCache {
    /** Catálogo (categorías, canales, películas, series, lista M3U): 6 horas. */
    const val CATALOG_AGE = 6 * 3600_000L

    /** Guía de cada canal: 2 horas. */
    const val GUIDE_AGE = 2 * 3600_000L

    @Volatile
    private var dir: java.io.File? = null

    fun init(ctx: android.content.Context) {
        dir = java.io.File(ctx.cacheDir, "catalogo").apply { mkdirs() }
    }

    fun dirOrNull(): java.io.File? = dir

    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(url: String): java.io.File? {
        val d = dir ?: return null
        val h = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return java.io.File(d, h)
    }

    fun <T> read(url: String, maxAge: Long, valid: (T) -> Boolean, parse: (java.io.InputStream) -> T): T {
        val f = fileFor(url) ?: return partialOk { Http.open(url).use { r -> parse(r.body!!.byteStream()) } }
        // 1) Copia reciente: se usa tal cual.
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAge) {
            val r = runCatching { f.inputStream().buffered(65536).use(parse) }.getOrNull()
            if (r != null && valid(r)) return r
            f.delete()
        }
        // 2) Se descarga a un archivo temporal. Si no se puede escribir en el aparato (Android ha vaciado la caché o
        //    no queda espacio, como pasa en algunos Chromecast), se lee directamente del servidor sin guardar copia.
        val tmp = java.io.File(f.parentFile, f.name + "." + System.nanoTime() + ".tmp")
        val out = try {
            f.parentFile?.mkdirs()
            tmp.outputStream()
        } catch (e: Exception) {
            null
        } ?: return partialOk { Http.open(url).use { r -> parse(r.body!!.byteStream().buffered(65536)) } }
        try {
            out.use { o -> Http.open(url).use { r -> r.body!!.byteStream().copyTo(o, 65536) } }
        } catch (e: Exception) {
            tmp.delete()
            // Sin conexión o servidor caído: la copia vieja, si la hay.
            if (f.exists()) {
                val r = runCatching { f.inputStream().buffered(65536).use(parse) }.getOrNull()
                if (r != null && valid(r)) {
                    ErrorLog.add("Servidor sin respuesta (${e.message}): se usa la copia guardada")
                    return r
                }
            }
            throw e
        }
        // 3) Se lee lo descargado y, si vale, pasa a ser la copia guardada.
        //    Si no vale (el servidor responde «bien» pero vacío, o con una página de error), se usa la copia
        //    guardada en vez de enseñar el catálogo en blanco.
        try {
            val r = tmp.inputStream().buffered(65536).use(parse)
            if (valid(r)) {
                f.delete()
                if (!tmp.renameTo(f)) tmp.delete()
                return r
            }
            tmp.delete()
            oldCopy(f, valid, parse)?.let {
                ErrorLog.add("El servidor ha respondido vacío: se usa la copia guardada")
                return it
            }
            return r
        } catch (e: Exception) {
            tmp.delete()
            if (e is kotlinx.coroutines.CancellationException) throw e
            oldCopy(f, valid, parse)?.let {
                ErrorLog.add("Respuesta del servidor no válida (${e.message}): se usa la copia guardada")
                return it
            }
            if (e is PartialRead) {
                ErrorLog.add("Respuesta del servidor cortada y sin copia guardada: se muestra lo que llegó")
                @Suppress("UNCHECKED_CAST")
                return e.partial as T
            }
            throw e
        }
    }

    /** Sin copia en el aparato: una respuesta cortada se enseña tal cual (mejor que nada). */
    @Suppress("UNCHECKED_CAST")
    private inline fun <T> partialOk(block: () -> T): T = try {
        block()
    } catch (e: PartialRead) {
        e.partial as T
    }

    /** La copia guardada, aunque sea vieja, si se puede leer y vale; null si no. */
    private fun <T> oldCopy(f: java.io.File, valid: (T) -> Boolean, parse: (java.io.InputStream) -> T): T? {
        if (!f.exists()) return null
        return runCatching { f.inputStream().buffered(65536).use(parse) }.getOrNull()?.takeIf(valid)
    }
}
