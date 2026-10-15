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
            .build()
    }

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
            client.newCall(req).execute()
        } catch (e: java.net.UnknownHostException) {
            throw IOException("No se encuentra el servidor. Revisa la dirección y tu conexión.")
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("El servidor tarda demasiado en responder.")
        }
        if (!r.isSuccessful) {
            val c = r.code
            r.close()
            throw IOException(
                if (c == 401 || c == 403) "Acceso denegado (HTTP $c). Revisa usuario y contraseña."
                else "El servidor respondió con un error (HTTP $c)."
            )
        }
        return r
    }

    fun text(url: String): String = open(url).use { it.body?.string() ?: "" }
}
