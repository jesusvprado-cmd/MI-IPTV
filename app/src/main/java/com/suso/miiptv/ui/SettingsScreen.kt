package com.suso.miiptv.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.suso.miiptv.data.AccountInfo
import com.suso.miiptv.data.Http
import com.suso.miiptv.data.Repo
import com.suso.miiptv.data.io
import com.suso.miiptv.vpn.VpnManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private enum class Sec(val label: String, val desc: String) {
    ACCOUNT("Cuenta", "Tu lista IPTV y el estado de la suscripción."),
    VPN("VPN", "Se conecta cuando tú quieras. En algunas teles la VPN se aplica a todo el equipo mientras está conectada."),
    VIDEO("Vídeo", "Imagen, carga y forma de reproducir."),
    AUDIO("Audio", "Sonido de canales, películas y series."),
    SUBS("Subtítulos", "Idioma y aspecto de los subtítulos."),
    CONTENT("Contenido", "Qué se muestra en la app."),
    EPG("Guía", "Guía de programación de los canales (EPG)."),
    ABOUT("Acerca de", ""),
}

/** Una pregunta abierta en la ventana de elección. */
private class Choice<T>(val title: String, val options: List<Pair<String, T>>, val selected: T, val onPick: (T) -> Unit)

/** Última sección abierta (para volver a ella al regresar de «Categorías ocultas»). */
private var lastSec: Sec = Sec.ACCOUNT

private fun <T> label(options: List<Pair<String, T>>, v: T): String = options.firstOrNull { it.second == v }?.first ?: ""

private val RESIZE = listOf("Ajustar" to "fit", "Rellenar (zoom)" to "zoom", "Estirar" to "fill", "16:9" to "16:9", "4:3" to "4:3")
private val BUFFER = listOf("Bajo · arranca antes" to "low", "Normal" to "normal", "Alto · menos cortes" to "high")
private val SEEK = listOf("10 segundos" to 10, "15 segundos" to 15, "30 segundos" to 30)
private val AUDIO_OUT = listOf("Automática" to false, "Decodificar siempre" to true)
private val BOOST = listOf("Sin aumento" to 0, "+3 dB" to 3, "+6 dB" to 6, "+10 dB" to 10)
private val AUDIO_LANG = listOf(
    "Español" to "es", "Inglés" to "en", "Gallego" to "gl", "Catalán" to "ca",
    "Francés" to "fr", "Portugués" to "pt", "Versión original" to "orig",
)
private val SUB_LANG = listOf(
    "Desactivados" to "", "Español" to "es", "Inglés" to "en", "Gallego" to "gl",
    "Catalán" to "ca", "Francés" to "fr", "Portugués" to "pt",
)
private val SUB_SIZE = listOf("Pequeño" to "s", "Normal" to "m", "Grande" to "l", "Muy grande" to "xl")
private val SUB_COLOR = listOf("Blanco" to "white", "Amarillo" to "yellow")
private val SUB_BG = listOf("Sin fondo (contorno)" to "none", "Franja oscura" to "band")
private val VPN_MODE = listOf("Desactivada" to "off", "WARP de Cloudflare" to "warp", "Mi WireGuard (.conf)" to "wg")
private val LIVE_FMT = listOf("TS (recomendado)" to "ts", "HLS (m3u8)" to "m3u8")

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(onChangeList: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = Repo.prefs
    @Suppress("UNUSED_VARIABLE")
    val sv = p.settingsVersion // se vuelve a dibujar al cambiar cualquier ajuste

    var sec by remember { mutableStateOf(lastSec) }
    var pending by remember { mutableStateOf<Sec?>(null) }
    LaunchedEffect(pending) {
        val s = pending ?: return@LaunchedEffect
        delay(180)
        sec = s
        lastSec = s
    }
    val sideFrs = remember { Sec.values().associateWith { FocusRequester() } }
    val restoring = remember { FocusMemory.restore != null }
    LaunchedEffect(Unit) {
        if (!restoring) {
            delay(60)
            runCatching { sideFrs.getValue(sec).requestFocus() }
        }
    }
    var inPane by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<Choice<*>?>(null) }

    // Fila desde la que se abrió la ventana de elección: al cerrarla, el foco vuelve a ella.
    var askedFrom by remember { mutableStateOf<String?>(null) }
    fun <T> ask(title: String, options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
        askedFrom = FocusMemory.last
        choice = Choice(title, options, selected, onPick)
    }
    // Justo al cerrar la ventana, Android puede poner el foco un instante en «Cuenta»: eso no cambia de sección.
    var ignoreSideUntil by remember { mutableStateOf(0L) }
    fun closeChoice() {
        ignoreSideUntil = System.currentTimeMillis() + 500
        val k = askedFrom
        FocusMemory.focus(k)
        choice = null
        scope.launch {
            delay(40)
            if (!FocusMemory.focus(k)) runCatching { sideFrs.getValue(sec).requestFocus() }
        }
    }

    // ---- VPN: permiso de Android y carga de archivos .conf ----
    val vpnPerm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) VpnManager.connect()
        else Actions.toast(ctx, "Sin ese permiso no se puede usar la VPN")
    }
    fun connectVpn() {
        val intent = VpnManager.permissionIntent(ctx)
        if (intent == null) {
            VpnManager.connect()
            return
        }
        try {
            vpnPerm.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Actions.toast(ctx, "Este dispositivo no permite que las apps usen VPN")
        }
    }
    fun setVpnMode(m: String) {
        p.vpnMode = m
        if (m == "off") VpnManager.disconnect()
        else if (m == "wg" && p.wgConf == null) Actions.toast(ctx, "Carga primero tu archivo WireGuard (.conf)")
        else connectVpn()
    }
    fun useConf(text: String, from: String) {
        val err = VpnManager.validate(text)
        if (err != null) {
            Actions.toast(ctx, err)
            return
        }
        p.wgConf = text
        p.wgName = from
        Actions.toast(ctx, "Perfil cargado: ${VpnManager.profileName(text)}")
        if (p.vpnMode == "wg" || p.vpnMode == "off") {
            p.vpnMode = "wg"
            connectVpn()
        }
    }
    var downloadsList by remember { mutableStateOf<List<File>?>(null) }
    var urlDialog by remember { mutableStateOf(false) }
    var epgUrlDialog by remember { mutableStateOf(false) }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val t = try {
                    io { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
                } catch (e: Exception) {
                    null
                }
                if (t == null) Actions.toast(ctx, "No se pudo leer el archivo") else useConf(t, uri.lastPathSegment ?: "archivo")
            }
        }
    }
    fun scanDownloads() {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val l = (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".conf", true) }
        if (l.isEmpty()) Actions.toast(ctx, "No hay archivos .conf en la carpeta Download") else downloadsList = l
    }
    val storagePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanDownloads() else Actions.toast(ctx, "Sin permiso no se puede leer la carpeta Download")
    }
    fun pickFile() {
        try {
            openDoc.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            // Muchas teles no tienen selector de archivos: buscamos en la carpeta Download.
            if (Build.VERSION.SDK_INT <= 32 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                storagePerm.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else {
                scanDownloads()
            }
        }
    }

    val dl = downloadsList
    if (dl != null) {
        FilePickList(dl, onCancel = { downloadsList = null }) { f ->
            downloadsList = null
            scope.launch {
                val t = try {
                    io { f.readText() }
                } catch (e: Exception) {
                    null
                }
                if (t == null) Actions.toast(ctx, "No se pudo leer ${f.name}") else useConf(t, f.name)
            }
        }
        return
    }
    if (epgUrlDialog) {
        UrlEntry(title = "Dirección de la guía XMLTV", button = "Guardar", initial = p.epgUrl, onCancel = { epgUrlDialog = false }) { url ->
            epgUrlDialog = false
            p.epgUrl = url
            if (p.epgMode == "provider") p.epgMode = "both"
            Repo.refreshEpg()
            Actions.toast(ctx, "Guía guardada")
        }
        return
    }
    if (urlDialog) {
        UrlEntry(onCancel = { urlDialog = false }) { url ->
            urlDialog = false
            scope.launch {
                val t = try {
                    io { Http.text(url) }
                } catch (e: Exception) {
                    Actions.toast(ctx, e.message ?: "No se pudo descargar")
                    null
                }
                if (t != null) useConf(t, Uri.parse(url).lastPathSegment ?: url)
            }
        }
        return
    }

    // Atrás desde las opciones vuelve primero al menú de secciones.
    BackHandler(enabled = inPane && choice == null) {
        runCatching { sideFrs.getValue(sec).requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Row(Modifier.fillMaxSize()) {
            // ---------------- Menú lateral (fijo) ----------------
            Column(
                Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .background(S.panel)
                    .padding(horizontal = 16.dp, vertical = 28.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 22.dp)) {
                    Logo(32.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Ajustes", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Sec.values().forEach { s ->
                    SideItem(
                        label = s.label,
                        icon = when (s) {
                            Sec.ACCOUNT -> SIcons.account
                            Sec.VPN -> SIcons.shield
                            Sec.VIDEO -> SIcons.video
                            Sec.AUDIO -> SIcons.audio
                            Sec.SUBS -> SIcons.subs
                            Sec.CONTENT -> SIcons.content
                            Sec.EPG -> SIcons.guide
                            Sec.ABOUT -> SIcons.info
                        },
                        selected = sec == s,
                        focusKey = "set_side_${s.name}",
                        requester = sideFrs.getValue(s),
                        onFocused = { if (System.currentTimeMillis() > ignoreSideUntil) pending = s },
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("◀ ▶ moverse  ·  OK elegir", color = Color(0xFF666666), fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }

            // ---------------- Opciones de la sección ----------------
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged { inPane = it.hasFocus }
                    // ◀ desde las opciones vuelve a la sección en la que estabas, no a la que quede a la misma altura.
                    .focusProperties { exit = { d -> if (d == FocusDirection.Left) sideFrs.getValue(sec) else FocusRequester.Default } }
                    .focusGroup(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().fadeInOnChange(sec),
                    contentPadding = PaddingValues(start = 40.dp, end = 48.dp, top = 30.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "title_${sec.name}") {
                        Column(Modifier.padding(bottom = 8.dp)) {
                            Text(sec.label, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            if (sec.desc.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(sec.desc, color = C.text2, fontSize = 14.sp)
                            }
                        }
                    }
                    when (sec) {
                        Sec.ACCOUNT -> item(key = "acc") { AccountSection(onChangeList) { t, o, s, f -> ask(t, o, s, f) } }
                        Sec.VPN -> item(key = "vpn") {
                            VpnSection(
                                onMode = { ask("Conexión VPN", VPN_MODE, p.vpnMode) { setVpnMode(it) } },
                                onConnect = { connectVpn() },
                                onFile = { pickFile() },
                                onUrl = { urlDialog = true },
                            )
                        }
                        Sec.VIDEO -> item(key = "video") { VideoSection { t, o, s, f -> ask(t, o, s, f) } }
                        Sec.AUDIO -> item(key = "audio") { AudioSection { t, o, s, f -> ask(t, o, s, f) } }
                        Sec.SUBS -> item(key = "subs") { SubsSection { t, o, s, f -> ask(t, o, s, f) } }
                        Sec.CONTENT -> item(key = "content") { ContentSection() }
                        Sec.EPG -> item(key = "epg") {
                            EpgSection(ask = { t, o, sel, f -> ask(t, o, sel, f) }, onUrl = { epgUrlDialog = true })
                        }
                        Sec.ABOUT -> item(key = "about") { Column { AboutSection() } }
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        val c = choice as Choice<Any?>?
        if (c != null) {
            ChoiceDialog(c.title, c.options, c.selected, onPick = { v ->
                closeChoice()
                c.onPick(v)
            }, onClose = { closeChoice() })
        }
    }
}

/** Forma común de abrir la ventana de elección desde cada sección. */
private typealias Ask = (String, List<Pair<String, Any?>>, Any?, (Any?) -> Unit) -> Unit

@Suppress("UNCHECKED_CAST")
private fun <T> Ask.of(title: String, options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) =
    this(title, options as List<Pair<String, Any?>>, selected) { v -> onPick(v as T) }

// ---------------- Secciones ----------------

@Composable
private fun AccountSection(onChangeList: () -> Unit, ask: Ask) {
    val ctx = LocalContext.current
    val cfg = Repo.config
    var acc by remember { mutableStateOf<AccountInfo?>(null) }
    var accState by remember { mutableStateOf("Comprobando…") }
    LaunchedEffect(Repo.dataVersion) {
        try {
            acc = Repo.source?.check()
            accState = if (acc == null) "" else ""
        } catch (e: Exception) {
            accState = e.message ?: "Error"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val rows = mutableListOf<Pair<String, String>>()
        if (cfg != null) {
            if (cfg.type == "xtream") {
                rows += "Tipo" to "Xtream Codes"
                rows += "Servidor" to cfg.server
                rows += "Usuario" to cfg.user
            } else {
                rows += "Tipo" to "Lista M3U"
                rows += "Servidor" to (Uri.parse(cfg.m3uUrl).host ?: cfg.m3uUrl)
                if (cfg.epgUrl.isNotBlank()) rows += "Guía EPG" to (Uri.parse(cfg.epgUrl).host ?: cfg.epgUrl)
            }
        }
        val a = acc
        val status = when {
            a != null && a.status.equals("Active", true) -> "Suscripción activa" to S.ok
            a != null -> "Estado: ${a.status ?: "desconocido"}" to S.warn
            accState == "Comprobando…" -> accState to S.warn
            accState.isNotEmpty() -> accState to S.bad
            else -> "Lista cargada" to S.ok
        }
        if (a != null) {
            rows += "Caduca" to (a.expiry?.let { dateStr(it) } ?: "Sin caducidad")
            rows += "Conexiones" to "${a.activeConnections ?: "0"} en uso de ${a.maxConnections ?: "?"}"
        }
        InfoCard(rows, status)
        GroupLabel("Lista")
        SettingRow("Recargar lista", "set_reload", subtitle = "Vuelve a descargar canales, películas y series") {
            Repo.reload()
            Actions.toast(ctx, "Lista recargada")
        }
        SettingRow("Cambiar lista o cuenta", "set_change", subtitle = "Otro servidor, usuario o lista M3U") { onChangeList() }
        if (cfg != null && cfg.type == "xtream") {
            GroupLabel("Directo")
            SettingRow(
                "Formato de los canales", "set_fmt",
                subtitle = "Si algún canal no carga, prueba el otro",
                value = label(LIVE_FMT, cfg.liveFormat),
            ) {
                ask.of("Formato de los canales", LIVE_FMT, cfg.liveFormat) { v ->
                    if (v != cfg.liveFormat) Repo.save(cfg.copy(liveFormat = v))
                }
            }
        }
    }
}

@Composable
private fun VpnSection(onMode: () -> Unit, onConnect: () -> Unit, onFile: () -> Unit, onUrl: () -> Unit) {
    val p = Repo.prefs
    val vs = VpnManager.state
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val status = when (vs) {
            VpnManager.State.ON -> "Conectada" to S.ok
            VpnManager.State.CONNECTING -> (VpnManager.message ?: "Conectando…") to S.warn
            VpnManager.State.ERROR -> "Error" to S.bad
            VpnManager.State.OFF -> "Desconectada" to Color(0xFF777777)
        }
        val rows = mutableListOf("Tipo" to label(VPN_MODE, p.vpnMode))
        VpnManager.info?.let { rows += "Salida" to it }
        if (VpnManager.wholeDevice && vs == VpnManager.State.ON) rows += "Alcance" to "Todo el equipo (no permite limitarla a la app)"
        if (vs == VpnManager.State.ERROR) VpnManager.message?.let { rows += "Detalle" to it }
        if (p.vpnMode == "wg") rows += "Perfil" to (p.wgConf?.let { VpnManager.profileName(it) } ?: "ninguno cargado")
        InfoCard(rows, status)

        GroupLabel("Conexión")
        SettingRow("Tipo de VPN", "set_vpn_mode", value = label(VPN_MODE, p.vpnMode)) { onMode() }
        ToggleRow(
            "Conectar al abrir la app", "set_vpn_auto", p.vpnAuto,
            subtitle = "Desactivado: la VPN solo se conecta cuando tú lo pidas",
        ) { p.vpnAuto = it }
        if (p.vpnMode != "off") {
            if (vs == VpnManager.State.ON || vs == VpnManager.State.CONNECTING) {
                SettingRow("Desconectar", "set_vpn_toggle", chevron = false) { VpnManager.disconnect() }
            } else {
                SettingRow("Conectar", "set_vpn_toggle", chevron = false) { onConnect() }
            }
        }
        if (vs == VpnManager.State.ON) {
            SettingRow("Comprobar IP de salida", "set_vpn_ip", chevron = false) { VpnManager.refreshInfo() }
        }
        if (p.vpnMode == "warp") {
            SettingRow("Nueva cuenta WARP", "set_vpn_renew", subtitle = "Si WARP deja de conectar", chevron = false) { VpnManager.renewWarp() }
        }
        GroupLabel("Perfil WireGuard propio")
        SettingRow("Cargar archivo .conf", "set_vpn_file", subtitle = "Envíalo a la tele con Send Files to TV") { onFile() }
        SettingRow("Cargar desde una dirección web", "set_vpn_url") { onUrl() }
        Text(
            "Sirve el archivo de Proton VPN (gratis) o de cualquier VPN que ofrezca WireGuard.",
            color = Color(0xFF777777), fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
}

private val EPG_MODE = listOf(
    "La de mi lista" to "provider",
    "La de mi lista y, si falta, la externa" to "both",
    "Solo la guía externa" to "external",
)
private val EPG_OFFSET = listOf(
    "−3 horas" to -180, "−2 horas" to -120, "−1 hora" to -60, "−30 minutos" to -30, "Sin ajuste" to 0,
    "+30 minutos" to 30, "+1 hora" to 60, "+2 horas" to 120, "+3 horas" to 180,
)
/** Guías XMLTV públicas para elegir sin escribir la dirección. */
private val EPG_PRESETS = listOf(
    "Guía de España (dobleM)" to "https://raw.githubusercontent.com/davidmuma/EPG_dobleM/master/guiatv.xml.gz",
    "Guía de España para IPTV (dobleM)" to "https://raw.githubusercontent.com/davidmuma/EPG_dobleM/master/guiaiptv.xml",
    "Escribir otra dirección…" to "__custom",
    "Quitar la guía externa" to "",
)

@Composable
private fun EpgSection(ask: Ask, onUrl: () -> Unit) {
    val p = Repo.prefs
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleRow(
            "Mostrar la guía", "set_epg_on", p.epgEnabled,
            subtitle = "Programa actual y siguiente en la lista de canales y en el reproductor",
        ) { p.epgEnabled = it; Repo.refreshEpg() }
        if (p.epgEnabled) {
            GroupLabel("Origen")
            SettingRow("Guía que se usa", "set_epg_mode", value = label(EPG_MODE, p.epgMode)) {
                ask.of("Guía que se usa", EPG_MODE, p.epgMode) { v ->
                    p.epgMode = v
                    Repo.refreshEpg()
                }
            }
            val ext = p.epgUrl
            val extName = EPG_PRESETS.firstOrNull { it.second == ext && ext.isNotEmpty() }?.first
                ?: if (ext.isEmpty()) "Ninguna" else Uri.parse(ext).host ?: ext
            SettingRow("Guía externa (XMLTV)", "set_epg_url", subtitle = "Sirve para canales sin guía o con la guía mal", value = extName) {
                ask.of("Guía externa", EPG_PRESETS, ext) { v ->
                    when (v) {
                        "__custom" -> onUrl()
                        else -> {
                            p.epgUrl = v
                            if (v.isNotEmpty() && p.epgMode == "provider") p.epgMode = "both"
                            if (v.isEmpty() && p.epgMode == "external") p.epgMode = "provider"
                            Repo.refreshEpg()
                        }
                    }
                }
            }
            GroupLabel("Ajustes")
            SettingRow("Ajuste de hora", "set_epg_off", subtitle = "Si la guía va adelantada o atrasada", value = label(EPG_OFFSET, p.epgOffset)) {
                ask.of("Ajuste de hora", EPG_OFFSET, p.epgOffset) { v ->
                    p.epgOffset = v
                }
            }
            SettingRow("Actualizar la guía ahora", "set_epg_refresh", chevron = false) {
                Repo.refreshEpg()
                Actions.toast(ctx, "La guía se volverá a descargar")
            }
        }
    }
}

@Composable
private fun VideoSection(ask: Ask) {
    val p = Repo.prefs
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupLabel("Imagen")
        SettingRow("Formato de imagen", "set_res", subtitle = "También se cambia desde el reproductor", value = label(RESIZE, p.resizeMode)) {
            ask.of("Formato de imagen", RESIZE, p.resizeMode) { p.resizeMode = it }
        }
        ToggleRow(
            "Ajustar la frecuencia de la pantalla", "set_afr", p.afr,
            subtitle = "Cambia a 24, 25, 50 o 60 Hz según el vídeo, si tu tele lo permite",
        ) { p.afr = it }
        GroupLabel("Reproducción")
        SettingRow("Búfer", "set_buf", subtitle = "Más alto: menos cortes, pero tarda más en arrancar", value = label(BUFFER, p.bufferMode)) {
            ask.of("Búfer", BUFFER, p.bufferMode) { p.bufferMode = it }
        }
        SettingRow("Salto al avanzar o retroceder", "set_seek", subtitle = "En películas y series", value = label(SEEK, p.seekStep)) {
            ask.of("Salto al avanzar o retroceder", SEEK, p.seekStep) { p.seekStep = it }
        }
        ToggleRow(
            "Usar otro reproductor", "set_ext", p.externalPlayer,
            subtitle = "Abrir los vídeos en VLC, MX Player u otro instalado",
        ) { p.externalPlayer = it }
    }
}

@Composable
private fun AudioSection(ask: Ask) {
    val p = Repo.prefs
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow(
            "Salida de audio", "set_dec",
            subtitle = if (p.audioDecode) "La app decodifica Dolby y DTS. Úsalo si algún canal no se oye."
            else "Dolby y DTS van directos a tu tele o barra de sonido si los admiten",
            value = label(AUDIO_OUT, p.audioDecode),
        ) {
            ask.of("Salida de audio", AUDIO_OUT, p.audioDecode) { p.audioDecode = it }
        }
        SettingRow("Aumentar volumen", "set_boost", subtitle = "Para canales que suenan bajos", value = label(BOOST, p.volumeBoost)) {
            ask.of("Aumentar volumen", BOOST, p.volumeBoost) { p.volumeBoost = it }
        }
        SettingRow("Idioma preferido", "set_alang", value = label(AUDIO_LANG, p.audioLang ?: "es")) {
            ask.of("Idioma de audio preferido", AUDIO_LANG, p.audioLang ?: "es") { p.audioLang = it }
        }
    }
}

@Composable
private fun SubsSection(ask: Ask) {
    val p = Repo.prefs
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SubtitlePreview()
        SettingRow("Idioma preferido", "set_slang", value = label(SUB_LANG, p.subLang ?: "")) {
            ask.of("Subtítulos", SUB_LANG, p.subLang ?: "") { p.subLang = it }
        }
        GroupLabel("Aspecto")
        SettingRow("Tamaño", "set_ssize", value = label(SUB_SIZE, p.subSize)) {
            ask.of("Tamaño", SUB_SIZE, p.subSize) { p.subSize = it }
        }
        SettingRow("Color", "set_scolor", value = label(SUB_COLOR, p.subColor)) {
            ask.of("Color", SUB_COLOR, p.subColor) { p.subColor = it }
        }
        SettingRow("Fondo", "set_sbg", value = label(SUB_BG, p.subBg)) {
            ask.of("Fondo", SUB_BG, p.subBg) { p.subBg = it }
        }
    }
}

/** Muestra cómo se verán los subtítulos con los ajustes actuales. */
@Composable
private fun SubtitlePreview() {
    val p = Repo.prefs
    val size = when (p.subSize) {
        "s" -> 15.sp
        "l" -> 22.sp
        "xl" -> 26.sp
        else -> 18.sp
    }
    val fg = if (p.subColor == "yellow") Color(0xFFFFE650) else Color.White
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF2B3A4A), Color(0xFF4A3A2B)))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val textMod = if (p.subBg == "band") {
            Modifier.padding(bottom = 14.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xAA000000)).padding(horizontal = 10.dp, vertical = 4.dp)
        } else {
            Modifier.padding(bottom = 14.dp)
        }
        Text(
            "Así se verán los subtítulos",
            color = fg,
            fontSize = size,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            style = if (p.subBg == "band") androidx.compose.ui.text.TextStyle.Default
            else androidx.compose.ui.text.TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(1.5f, 1.5f), 3f),
            ),
            modifier = textMod,
        )
    }
}

@Composable
private fun ContentSection() {
    val ctx = LocalContext.current
    val p = Repo.prefs
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow(
            "Categorías ocultas", "set_hidden",
            subtitle = "Las de adultos se ocultan solas. Puedes proteger esta opción con un PIN.",
            value = if (p.pin.isNotEmpty()) "Con PIN" else null,
        ) { UiState.push(Screen.HiddenCats) }
        ToggleRow(
            "Logos de canales en alta calidad", "set_logos", p.hqLogos,
            subtitle = "Usa logos de bancos abiertos en lugar de los de tu lista. El nombre del canal no cambia.",
        ) { v ->
            p.hqLogos = v
            if (v) com.suso.miiptv.data.Logos.ensureLoaded()
        }
        if (p.hqLogos) {
            SettingRow(
                "Canales sin logo de calidad", "set_nologo",
                subtitle = "Lista de los canales para los que no se ha encontrado logo",
            ) { UiState.push(Screen.MissingLogos) }
        }
        SettingRow("Borrar «Continuar viendo»", "set_clear", subtitle = "Quita el historial de películas y series empezadas", chevron = false) {
            p.clearProgress()
            Actions.toast(ctx, "Historial borrado")
        }
    }
}

@Composable
private fun AboutSection() {
    val ctx = LocalContext.current
    val version = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(S.panel)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logo(64.dp)
        Spacer(Modifier.height(14.dp))
        Text("Mi IPTV", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Versión $version", color = C.gold, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "Reproductor para usar con tu propia lista IPTV.\nNo incluye canales ni contenidos.",
            color = C.text2, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
        )
    }
    Spacer(Modifier.height(10.dp))
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    SettingRow(
        "Buscar actualizaciones", "set_update",
        subtitle = if (checking) "Comprobando…" else "Descarga e instala la última versión desde la propia app",
        chevron = false,
    ) {
        if (checking) return@SettingRow
        checking = true
        scope.launch {
            val has = try {
                com.suso.miiptv.data.Updater.check(ctx, force = true)
            } catch (e: Exception) {
                Actions.toast(ctx, "No se pudo comprobar: ${e.message ?: "sin conexión"}")
                checking = false
                return@launch
            }
            checking = false
            if (has) com.suso.miiptv.data.Updater.dismissed = false
            else Actions.toast(ctx, "Ya tienes la última versión")
        }
    }
}

/** Lista de archivos .conf encontrados en la carpeta Download. */
@Composable
private fun FilePickList(files: List<File>, onCancel: () -> Unit, onPick: (File) -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { fr.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState()).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Elige el archivo WireGuard", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Archivos .conf de la carpeta Download", color = C.text2, fontSize = 14.sp)
        files.forEachIndexed { i, f ->
            TvButton(f.name, "conf_$i", focusRequester = if (i == 0) fr else null) { onPick(f) }
        }
        Spacer(Modifier.height(8.dp))
        TvButton("Cancelar", "conf_cancel") { onCancel() }
    }
}

/** Pantalla para escribir la dirección web de un archivo .conf. */
@Composable
private fun UrlEntry(
    title: String = "Dirección web del archivo .conf",
    button: String = "Cargar",
    initial: String = "",
    onCancel: () -> Unit,
    onDone: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { fr.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = 48.dp, start = 32.dp, end = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.trim() },
                singleLine = true,
                label = { Text("https://…") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onDone(value) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = C.gold,
                    unfocusedBorderColor = Color(0xFF555555),
                    focusedLabelColor = C.gold,
                    cursorColor = C.gold,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(fr),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton(button, "url_ok", filled = true) { if (value.isNotBlank()) onDone(value) }
                TvButton("Cancelar", "url_cancel") { onCancel() }
            }
        }
    }
}
