package com.suso.miiptv.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Enviar ajustes a otros aparatos de casa por la wifi, sin cuentas ni servidores.
 *
 * Cada aparato con la app a la vista se anuncia en la red («_pepino._tcp») y escucha. Otro aparato le envía lo elegido
 * (orden de categorías, ocultas, listas guardadas, favoritos y ajustes) cifrado con una clave acordada en el momento (ECDH).
 * La primera vez los dos enseñan un código de 6 números y hay que aceptar en el que recibe: así un intruso en la wifi
 * no puede colarse en medio ni enviar nada sin permiso. Después quedan emparejados y ya no se pregunta.
 */
object Sync {
    private const val TYPE = "_pepino._tcp."
    private const val TIMEOUT_MS = 20_000

    enum class Part(val label: String) {
        ORDER("Orden de las categorías"),
        HIDDEN("Categorías ocultas"),
        LISTS("Mis listas guardadas"),
        FAVS("Favoritos (de todos los perfiles)"),
        SETTINGS("Otros ajustes (audio, subtítulos, guía…)"),
    }

    /** Ajustes que tiene sentido llevar a otro aparato (los propios de cada uno, como la VPN o el modo ligero, no). */
    private val SETTING_KEYS = setOf(
        "ask_profile", "audioLang", "autoNext", "buffer", "epg_mode", "epg_offset", "epg_on", "epg_url",
        "hqLogos", "seekStep", "subBg", "subColor", "subLang", "subSize", "resize", "adelay",
    )

    private fun isFavKey(k: String) = k == "favs" || k.endsWith("_favs")
    private fun isSettingKey(k: String) = k in SETTING_KEYS || SETTING_KEYS.any { k.matches(Regex("p\\d+_$it")) }

    private fun keysOf(part: Part, k: String): Boolean = when (part) {
        Part.ORDER -> k.startsWith("catOrder_")
        Part.HIDDEN -> k == "hidden" || k == "seenCats"
        Part.LISTS -> k == "savedLists"
        Part.FAVS -> k == "profiles" || isFavKey(k)
        Part.SETTINGS -> isSettingKey(k)
    }

    // ---------- Identidad del aparato y emparejamientos (en su propio archivo: no viajan en copias ni envíos) ----------

    private fun sp(ctx: Context) = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun deviceId(ctx: Context): String {
        val s = sp(ctx)
        return s.getString("id", null) ?: UUID.randomUUID().toString().also { s.edit().putString("id", it).apply() }
    }

    fun deviceName(ctx: Context): String {
        val n = runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()
        return n?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    }

    private fun pairKey(ctx: Context, peer: String): ByteArray? =
        Secure.open(sp(ctx).getString("peer_$peer", null))?.let { runCatching { b64d(it) }.getOrNull() }

    private fun savePair(ctx: Context, peer: String, key: ByteArray) {
        sp(ctx).edit().putString("peer_$peer", Secure.seal(b64(key))).apply()
    }

    fun isPaired(ctx: Context, peer: String) = pairKey(ctx, peer) != null

    // ---------- Qué se envía y cómo se aplica ----------

    fun collect(ctx: Context, parts: Set<Part>): JSONObject {
        val all = ctx.getSharedPreferences("miiptv", Context.MODE_PRIVATE).all
        val out = JSONObject()
        for ((k, v) in all) {
            if (parts.none { keysOf(it, k) }) continue
            val t = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                // Listas y favoritos van cifrados con la llave de este aparato: viajan en claro dentro del cifrado del envío.
                is String -> JSONObject().put("t", "s").put("v", if (Secure.isSealed(v)) Secure.open(v) ?: continue else v)
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            out.put(k, t)
        }
        return JSONObject().put("parts", JSONArray(parts.map { it.name })).put("data", out)
    }

    /** Aplica lo recibido. Devuelve lo que se ha cambiado, para el aviso. */
    fun apply(ctx: Context, msg: JSONObject): List<Part> {
        val parts = msg.optJSONArray("parts")?.let { a -> (0 until a.length()).mapNotNull { runCatching { Part.valueOf(a.getString(it)) }.getOrNull() } }
            .orEmpty().toSet()
        val d = msg.getJSONObject("data")
        val sp = ctx.getSharedPreferences("miiptv", Context.MODE_PRIVATE)
        val e = sp.edit()
        // Lo que se sustituye entero: se quita lo que había de ese tipo (p. ej. el orden de categorías que ya no está).
        for (k in sp.all.keys) {
            if ((Part.ORDER in parts && keysOf(Part.ORDER, k)) || (Part.FAVS in parts && keysOf(Part.FAVS, k))) e.remove(k)
        }
        var lists: String? = null
        for (k in d.keys()) {
            val part = parts.firstOrNull { keysOf(it, k) } ?: continue
            val o = d.getJSONObject(k)
            when {
                k == "savedLists" -> lists = o.optString("v")
                k == "seenCats" -> {
                    val a = o.getJSONArray("v")
                    val mine = sp.getStringSet("seenCats", emptySet()).orEmpty()
                    e.putStringSet(k, mine + (0 until a.length()).map { a.getString(it) })
                }
                else -> when (o.optString("t")) {
                    "b" -> e.putBoolean(k, o.getBoolean("v"))
                    "i" -> e.putInt(k, o.getInt("v"))
                    "l" -> e.putLong(k, o.getLong("v"))
                    "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                    "s" -> e.putString(k, if (part == Part.FAVS && isFavKey(k)) Secure.seal(o.getString("v")) else o.getString("v"))
                    "set" -> {
                        val a = o.getJSONArray("v")
                        e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                    }
                }
            }
        }
        e.commit()
        Handler(Looper.getMainLooper()).post {
            if (Repo.isReady()) {
                lists?.let { Repo.prefs.mergeLists(it) }
                Repo.prefs.reloadAfterSync()
            }
        }
        return parts.toList()
    }

    // ---------- Cifrado ----------

    private val rnd = SecureRandom()
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64d(s: String) = Base64.decode(s, Base64.NO_WRAP)
    private fun sha(vararg parts: ByteArray) = MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()
    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach { update(it) } }.doFinal()

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun pub(b: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b))
    private fun ecdh(kp: KeyPair, other: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(kp.private); doPhase(other, true) }.generateSecret()

    /** Código de 6 números que enseñan los dos aparatos la primera vez: si coincide, no hay nadie en medio. */
    private fun sasCode(sPub: ByteArray, rPub: ByteArray, nonce: ByteArray): String {
        val h = sha("pepino-sas".toByteArray(), sPub, rPub, nonce)
        val n = ((h[0].toLong() and 0xff) shl 24 or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)) % 1_000_000
        return n.toString().padStart(6, '0')
    }

    private fun sessionKey(base: ByteArray, z: ByteArray, sPub: ByteArray, rPub: ByteArray) =
        hmac(base, "pepino-ses".toByteArray(), z, sPub, rPub)

    private fun seal(key: ByteArray, plain: String): JSONObject {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
        return JSONObject().put("iv", b64(iv)).put("ct", b64(c.doFinal(plain.toByteArray())))
    }

    private fun open(key: ByteArray, o: JSONObject): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64d(o.getString("iv")))) }
        return String(c.doFinal(b64d(o.getString("ct"))))
    }

    private class Conn(s: Socket) {
        val inp = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val out = PrintWriter(s.getOutputStream(), true)
        fun send(o: JSONObject) = out.println(o.toString())
        fun read(): JSONObject = JSONObject(inp.readLine() ?: throw java.io.IOException("Se ha cortado la conexión"))
    }

    // ---------- Recibir (este aparato) ----------

    /** Petición de emparejamiento a la vista en este aparato: quién, el código y la respuesta (aceptar o no). */
    class Ask(val from: String, val code: String, val answer: CompletableFuture<Boolean>)

    var ask by mutableStateOf<Ask?>(null)
        private set

    fun answer(ok: Boolean) {
        ask?.answer?.complete(ok)
        ask = null
    }

    private var server: ServerSocket? = null
    private var reg: NsdManager.RegistrationListener? = null

    /** Empieza a escuchar y a anunciarse (con la app a la vista). */
    fun start(ctx: Context) {
        if (server != null) return
        val app = ctx.applicationContext
        val ss = runCatching { ServerSocket(0) }.getOrNull() ?: return
        server = ss
        Thread({
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching { s.use { handle(app, it) } }.onFailure {
                    if (it !is java.net.SocketException) ErrorLog.add("Recibir ajustes: ${it.message}")
                }
            }
        }, "sync-server").apply { isDaemon = true }.start()
        val info = NsdServiceInfo().apply {
            serviceName = deviceName(app)
            serviceType = TYPE
            port = ss.localPort
            setAttribute("id", deviceId(app))
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        reg = l
        runCatching { (app.getSystemService(Context.NSD_SERVICE) as NsdManager).registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stop(ctx: Context) {
        reg?.let { l -> runCatching { (ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) } }
        reg = null
        runCatching { server?.close() }
        server = null
        answer(false)
    }

    private fun handle(ctx: Context, s: Socket) {
        s.soTimeout = 150_000
        val c = Conn(s)
        val h = c.read()
        if (h.optString("t") != "hello") return
        val peer = h.getString("id")
        val peerName = h.optString("name", "Otro aparato")
        val commit = b64d(h.getString("commit"))
        val pk = pairKey(ctx, peer)
        val paired = pk != null && h.optBoolean("paired")
        val kp = keyPair()
        val rPub = kp.public.encoded
        c.send(JSONObject().put("t", "hello").put("id", deviceId(ctx)).put("name", deviceName(ctx)).put("pub", b64(rPub)).put("paired", paired))
        val r = c.read()
        val sPub = b64d(r.getString("pub"))
        val nonce = b64d(r.getString("nonce"))
        if (!MessageDigest.isEqual(commit, sha(sPub, nonce))) throw SecurityException("respuesta no válida")
        val z = ecdh(kp, pub(sPub))
        val key = if (paired) sessionKey(pk!!, z, sPub, rPub) else {
            // Primera vez: los dos aparatos enseñan el mismo código y aquí hay que aceptar.
            val a = Ask(peerName, sasCode(sPub, rPub, nonce), CompletableFuture())
            Handler(Looper.getMainLooper()).post { ask = a }
            val ok = runCatching { a.answer.get(120, TimeUnit.SECONDS) }.getOrDefault(false)
            Handler(Looper.getMainLooper()).post { if (ask === a) ask = null }
            if (!ok) {
                c.send(JSONObject().put("t", "error").put("msg", "No se ha aceptado en ${deviceName(ctx)}"))
                return
            }
            sessionKey(sha("pepino-new".toByteArray(), z), z, sPub, rPub)
        }
        c.send(JSONObject().put("t", "ready").put("mac", b64(hmac(key, "R".toByteArray()))))
        val d = c.read()
        if (d.optString("t") != "data") return
        val msg = try {
            JSONObject(open(key, d.getJSONObject("box")))
        } catch (e: Exception) {
            c.send(JSONObject().put("t", "error").put("msg", "No se han podido leer los ajustes"))
            return
        }
        if (!paired) savePair(ctx, peer, hmac(key, "pair".toByteArray()))
        val got = apply(ctx, msg)
        c.send(JSONObject().put("t", "ok"))
        Handler(Looper.getMainLooper()).post {
            com.suso.miiptv.ui.AppToast.show("Ajustes recibidos de $peerName: ${got.joinToString(", ") { it.label.lowercase() }}", longer = true)
        }
    }

    // ---------- Enviar (a otro aparato) ----------

    data class Peer(val id: String, val name: String, val host: java.net.InetAddress, val port: Int)

    /** Busca aparatos con la app abierta en la wifi. [onFound] se llama por cada uno. Devuelve cómo pararlo. */
    fun discover(ctx: Context, onFound: (Peer) -> Unit): () -> Unit {
        val app = ctx.applicationContext
        val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
        val me = deviceId(app)
        val main = Handler(Looper.getMainLooper())
        // Android antiguo solo resuelve uno a la vez: cola.
        val queue = ArrayDeque<NsdServiceInfo>()
        var busy = false
        fun next() {
            if (busy) return
            val i = queue.removeFirstOrNull() ?: return
            busy = true
            @Suppress("DEPRECATION")
            nsd.resolveService(i, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                    main.post { busy = false; next() }
                }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val id = i.attributes["id"]?.let { String(it) }.orEmpty()
                    @Suppress("DEPRECATION")
                    val host = i.host
                    main.post {
                        if (id.isNotEmpty() && id != me && host != null) onFound(Peer(id, i.serviceName, host, i.port))
                        busy = false
                        next()
                    }
                }
            })
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {}
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                main.post { queue.addLast(i); next() }
            }
        }
        runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
        return { runCatching { nsd.stopServiceDiscovery(l) } }
    }

    /**
     * Envía [parts] a [p]. Si aún no están emparejados, [onCode] recibe el código que hay que ver en los dos aparatos
     * (y aceptar en el otro). Lanza una excepción con un mensaje legible si algo falla.
     */
    suspend fun send(ctx: Context, p: Peer, parts: Set<Part>, onCode: (String) -> Unit) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        Socket().use { s ->
            try {
                s.connect(InetSocketAddress(p.host, p.port), TIMEOUT_MS)
            } catch (e: Exception) {
                throw java.io.IOException("No se puede conectar con ${p.name}. ¿Tiene Pepino IPTV abierto?")
            }
            s.soTimeout = 150_000
            val c = Conn(s)
            val kp = keyPair()
            val sPub = kp.public.encoded
            val nonce = ByteArray(16).also { rnd.nextBytes(it) }
            val pk = pairKey(app, p.id)
            c.send(
                JSONObject().put("t", "hello").put("id", deviceId(app)).put("name", deviceName(app))
                    .put("commit", b64(sha(sPub, nonce))).put("paired", pk != null),
            )
            val h = c.read()
            val rPub = b64d(h.getString("pub"))
            val paired = pk != null && h.optBoolean("paired")
            c.send(JSONObject().put("t", "keys").put("pub", b64(sPub)).put("nonce", b64(nonce)))
            val z = ecdh(kp, pub(rPub))
            val key = if (paired) sessionKey(pk!!, z, sPub, rPub) else {
                withContext(Dispatchers.Main) { onCode(sasCode(sPub, rPub, nonce)) }
                sessionKey(sha("pepino-new".toByteArray(), z), z, sPub, rPub)
            }
            val r = c.read()
            if (r.optString("t") == "error") throw java.io.IOException(r.optString("msg", "${p.name} no ha aceptado"))
            if (!MessageDigest.isEqual(b64d(r.optString("mac")), hmac(key, "R".toByteArray()))) {
                throw SecurityException("${p.name} no ha respondido como debía. Vuelve a intentarlo.")
            }
            c.send(JSONObject().put("t", "data").put("box", seal(key, collect(app, parts).toString())))
            val ok = c.read()
            if (ok.optString("t") != "ok") throw java.io.IOException(ok.optString("msg", "${p.name} no ha podido guardar los ajustes"))
            if (!paired) savePair(app, p.id, hmac(key, "pair".toByteArray()))
        }
    }
}
