package com.suso.miiptv.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.suso.miiptv.data.Http
import com.suso.miiptv.data.Repo
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * VPN integrada con WireGuard. Solo pasa por el túnel el tráfico de esta app;
 * el resto del dispositivo sigue con la conexión normal.
 */
object VpnManager {
    enum class State { OFF, CONNECTING, ON, ERROR }

    var state by mutableStateOf(State.OFF)
        private set

    /** Texto de error o de estado para mostrar en pantalla. */
    var message by mutableStateOf<String?>(null)
        private set

    /** IP y país de salida cuando está conectada. */
    var info by mutableStateOf<String?>(null)
        private set

    /** Aumenta cada vez que se conecta o desconecta (el reproductor recarga el canal). */
    var changes by mutableIntStateOf(0)
        private set

    private lateinit var app: Context
    private var backend: GoBackend? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = "miiptv"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN) {
                scope.launch {
                    if (state == State.ON) {
                        state = State.OFF
                        info = null
                    }
                }
            }
        }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    fun isConnecting() = state == State.CONNECTING

    /** Intent de permiso de Android si todavía no se ha aceptado la conexión VPN; null si ya está aceptada. */
    fun permissionIntent(ctx: Context): Intent? = try {
        VpnService.prepare(ctx)
    } catch (e: Exception) {
        null
    }

    /** Al abrir la app: si la VPN estaba activada y el permiso ya está concedido, conecta sola. */
    fun autoStart() {
        if (!::app.isInitialized) return
        if (Repo.prefs.vpnMode != "off" && permissionIntent(app) == null) connect()
    }

    fun connect() {
        val mode = Repo.prefs.vpnMode
        if (mode == "off") {
            disconnect()
            return
        }
        job?.cancel()
        state = State.CONNECTING
        message = if (mode == "warp") "Conectando a WARP…" else "Conectando…"
        info = null
        job = scope.launch {
            try {
                val saved = if (mode == "warp") Repo.prefs.warpConf else Repo.prefs.wgConf
                val text = saved ?: if (mode == "warp") {
                    withContext(Dispatchers.IO) { Warp.register() }.also { Repo.prefs.warpConf = it }
                } else {
                    throw IllegalStateException("Primero carga un archivo de configuración WireGuard (.conf).")
                }
                val pkg = app.packageName
                val conf = withContext(Dispatchers.IO) { Config.parse(onlyThisApp(text, pkg).byteInputStream()) }
                withContext(Dispatchers.IO) {
                    val b = backend ?: GoBackend(app).also { backend = it }
                    try {
                        b.setState(tunnel, Tunnel.State.DOWN, null)
                    } catch (e: Exception) {
                        // No estaba conectada.
                    }
                    b.setState(tunnel, Tunnel.State.UP, conf)
                }
                state = State.ON
                message = null
                resetConnections()
                changes++
                refreshInfo()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state = State.ERROR
                message = explain(e)
                resetConnections()
                changes++
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        val wasOn = state == State.ON || state == State.CONNECTING
        state = State.OFF
        message = null
        info = null
        val b = backend ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    b.setState(tunnel, Tunnel.State.DOWN, null)
                } catch (e: Exception) {
                    // Ya estaba desconectada.
                }
            }
            resetConnections()
            if (wasOn) changes++
        }
    }

    /** Vuelve a pedir una cuenta WARP nueva (si la actual deja de funcionar). */
    fun renewWarp() {
        Repo.prefs.warpConf = null
        if (Repo.prefs.vpnMode == "warp") connect()
    }

    fun refreshInfo() {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build()
                    Http.client.newCall(req).execute().use { it.body?.string() ?: "" }
                } catch (e: Exception) {
                    null
                }
            }
            if (r == null) {
                if (state == State.ON) info = "Conectada (no se pudo comprobar la IP)"
                return@launch
            }
            val m = r.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val warp = when (m["warp"]) {
                "on", "plus" -> " · WARP activo"
                else -> ""
            }
            if (state == State.ON) info = "IP ${m["ip"] ?: "?"} · ${m["loc"] ?: "?"}$warp"
        }
    }

    private fun resetConnections() {
        try {
            Http.client.connectionPool.evictAll()
        } catch (e: Exception) {
            // Sin importancia.
        }
    }

    /** Añade "IncludedApplications = <esta app>" para que el resto del dispositivo no use la VPN. */
    private fun onlyThisApp(conf: String, pkg: String): String {
        val out = StringBuilder()
        var inserted = false
        for (raw in conf.replace("\r", "").lines()) {
            val t = raw.trim()
            if (t.startsWith("IncludedApplications", true) || t.startsWith("ExcludedApplications", true)) continue
            out.append(raw).append('\n')
            if (!inserted && t.equals("[Interface]", true)) {
                out.append("IncludedApplications = ").append(pkg).append('\n')
                inserted = true
            }
        }
        return out.toString()
    }

    private fun explain(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        if (e is com.wireguard.android.backend.BackendException) {
            return when (e.reason.name) {
                "VPN_NOT_AUTHORIZED" -> "Android no ha dado permiso para la VPN."
                "DNS_RESOLUTION_FAILURE" -> "No se encuentra el servidor de la VPN. Revisa tu conexión."
                "UNABLE_TO_START_VPN" -> "Android no ha podido iniciar la VPN. ¿Hay otra VPN activa?"
                else -> "No se pudo conectar la VPN (${e.reason.name})."
            }
        }
        return when (e) {
            is com.wireguard.config.BadConfigException -> "El archivo de configuración no es válido ($msg)."
            else -> msg
        }
    }

    /** Nombre corto de un perfil WireGuard: el servidor al que conecta. */
    fun profileName(conf: String): String {
        val ep = conf.lines().firstOrNull { it.trim().startsWith("Endpoint", true) }
            ?.substringAfter('=')?.trim()?.substringBeforeLast(':')
        return ep ?: "perfil WireGuard"
    }

    /** Comprueba que un texto es una configuración WireGuard válida. Devuelve el error o null. */
    fun validate(conf: String): String? = try {
        Config.parse(conf.byteInputStream())
        null
    } catch (e: Exception) {
        "No es un archivo WireGuard válido: ${e.message ?: e.javaClass.simpleName}"
    }
}
