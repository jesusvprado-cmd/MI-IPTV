package com.suso.miiptv.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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

@Composable
fun SettingsScreen(onChangeList: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = Repo.prefs
    @Suppress("UNUSED_VARIABLE")
    val sv = p.settingsVersion // se vuelve a dibujar al cambiar cualquier ajuste
    val cfg = Repo.config
    var acc by remember { mutableStateOf<AccountInfo?>(null) }
    var accState by remember { mutableStateOf("Comprobando…") }
    LaunchedEffect(Repo.dataVersion) {
        try {
            acc = Repo.source?.check()
            accState = if (acc == null) "—" else ""
        } catch (e: Exception) {
            accState = e.message ?: "Error"
        }
    }
    val firstFr = remember { FocusRequester() }
    val restoring = remember { FocusMemory.restore != null }
    LaunchedEffect(Unit) {
        if (!restoring) {
            delay(60)
            runCatching { firstFr.requestFocus() }
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

    Column(
        Modifier
            .fillMaxSize()
            .background(C.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(36.dp)
            Spacer(Modifier.width(14.dp))
            Text("Ajustes", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))

        // ---------------- Cuenta ----------------
        SectionTitle("Cuenta")
        if (cfg != null) {
            if (cfg.type == "xtream") {
                InfoLine("Tipo", "Xtream Codes")
                InfoLine("Servidor", cfg.server)
                InfoLine("Usuario", cfg.user)
            } else {
                InfoLine("Tipo", "Lista M3U")
                InfoLine("Servidor", Uri.parse(cfg.m3uUrl).host ?: cfg.m3uUrl)
                if (cfg.epgUrl.isNotBlank()) InfoLine("Guía EPG", Uri.parse(cfg.epgUrl).host ?: cfg.epgUrl)
            }
        }
        val a = acc
        if (a != null) {
            InfoLine("Estado", if (a.status.equals("Active", true)) "Activa" else (a.status ?: "—"))
            InfoLine("Caduca", a.expiry?.let { dateStr(it) } ?: "Sin caducidad")
            InfoLine("Conexiones", "${a.activeConnections ?: "0"} en uso de ${a.maxConnections ?: "?"}")
        } else if (accState.isNotEmpty()) {
            InfoLine("Estado", accState)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton("Recargar lista", "set_reload", icon = Icons.Filled.Refresh, focusRequester = firstFr) {
                Repo.reload()
                Actions.toast(ctx, "Lista recargada")
            }
            TvButton("Cambiar lista o cuenta", "set_change") { onChangeList() }
        }
        if (cfg != null && cfg.type == "xtream") {
            OptionRow(
                "Formato de los canales en directo (si alguno no carga, prueba el otro)",
                listOf("TS (recomendado)" to "ts", "HLS (m3u8)" to "m3u8"),
                cfg.liveFormat, "set_fmt",
            ) { v -> if (v != cfg.liveFormat) Repo.save(cfg.copy(liveFormat = v)) }
        }

        // ---------------- VPN ----------------
        Spacer(Modifier.height(14.dp))
        SectionTitle("VPN")
        Text(
            "Solo pasa por la VPN el tráfico de Mi IPTV; el resto del dispositivo sigue con tu conexión normal.",
            color = C.text2, fontSize = 14.sp,
        )
        OptionRow(
            "Conexión",
            listOf("Desactivada" to "off", "WARP (Cloudflare)" to "warp", "Mi WireGuard" to "wg"),
            p.vpnMode, "set_vpn",
        ) { setVpnMode(it) }
        val vs = VpnManager.state
        val status = when (vs) {
            VpnManager.State.ON -> "Conectada" + (VpnManager.info?.let { " · $it" } ?: "")
            VpnManager.State.CONNECTING -> VpnManager.message ?: "Conectando…"
            VpnManager.State.ERROR -> "Error: " + (VpnManager.message ?: "desconocido")
            VpnManager.State.OFF -> "Desconectada"
        }
        InfoLine("Estado", status)
        if (p.vpnMode == "wg") {
            InfoLine("Perfil", p.wgConf?.let { VpnManager.profileName(it) } ?: "ninguno cargado")
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (p.vpnMode != "off") {
                if (vs == VpnManager.State.ON || vs == VpnManager.State.CONNECTING) {
                    TvButton("Desconectar", "set_vpn_off") { VpnManager.disconnect() }
                } else {
                    TvButton("Conectar", "set_vpn_on", filled = true) { connectVpn() }
                }
            }
            if (vs == VpnManager.State.ON) TvButton("Comprobar IP", "set_vpn_ip") { VpnManager.refreshInfo() }
            if (p.vpnMode == "warp") TvButton("Nueva cuenta WARP", "set_vpn_renew") { VpnManager.renewWarp() }
            TvButton("Cargar archivo .conf", "set_vpn_file") { pickFile() }
            TvButton("Cargar desde dirección web", "set_vpn_url") { urlDialog = true }
        }
        Text(
            "Mi WireGuard: sirve el archivo .conf de Proton VPN (gratis) o de cualquier VPN con WireGuard. " +
                "Envíalo a la tele con Send Files to TV o súbelo a una dirección web.",
            color = C.text2, fontSize = 13.sp,
        )

        // ---------------- Vídeo ----------------
        Spacer(Modifier.height(14.dp))
        SectionTitle("Vídeo")
        OptionRow(
            "Formato de imagen (también se cambia desde el reproductor)",
            listOf("Ajustar" to "fit", "Rellenar (zoom)" to "zoom", "Estirar" to "fill", "16:9" to "16:9", "4:3" to "4:3"),
            p.resizeMode, "set_res",
        ) { p.resizeMode = it }
        OptionRow(
            "Búfer (más alto = menos cortes, pero tarda más en arrancar)",
            listOf("Bajo" to "low", "Normal" to "normal", "Alto" to "high"),
            p.bufferMode, "set_buf",
        ) { p.bufferMode = it }
        OptionRow(
            "Salto al avanzar o retroceder en películas y series",
            listOf("10 s" to 10, "15 s" to 15, "30 s" to 30),
            p.seekStep, "set_seek",
        ) { p.seekStep = it }
        OptionRow(
            "Ajustar la frecuencia de la pantalla al vídeo (24/25/50/60 Hz, si tu tele lo permite)",
            listOf("No" to false, "Sí" to true),
            p.afr, "set_afr",
        ) { p.afr = it }
        OptionRow(
            "Reproductor",
            listOf("El de la app" to false, "Otro instalado (VLC, MX Player…)" to true),
            p.externalPlayer, "set_ext",
        ) { p.externalPlayer = it }

        // ---------------- Audio ----------------
        Spacer(Modifier.height(14.dp))
        SectionTitle("Audio")
        OptionRow(
            "Salida de audio",
            listOf("Automática (Dolby/DTS directo si tu equipo lo admite)" to false, "Decodificar siempre (si algún canal no se oye)" to true),
            p.audioDecode, "set_dec",
        ) { p.audioDecode = it }
        OptionRow(
            "Aumentar volumen",
            listOf("No" to 0, "+3 dB" to 3, "+6 dB" to 6, "+10 dB" to 10),
            p.volumeBoost, "set_boost",
        ) { p.volumeBoost = it }
        OptionRow(
            "Idioma de audio preferido",
            listOf("Español" to "es", "Inglés" to "en", "Gallego" to "gl", "Catalán" to "ca", "Francés" to "fr", "Portugués" to "pt", "Original" to "orig"),
            p.audioLang ?: "es", "set_alang",
        ) { p.audioLang = it }

        // ---------------- Subtítulos ----------------
        Spacer(Modifier.height(14.dp))
        SectionTitle("Subtítulos")
        OptionRow(
            "Idioma preferido",
            listOf("Desactivados" to "", "Español" to "es", "Inglés" to "en", "Gallego" to "gl", "Catalán" to "ca", "Francés" to "fr", "Portugués" to "pt"),
            p.subLang ?: "", "set_slang",
        ) { p.subLang = it }
        OptionRow(
            "Tamaño",
            listOf("Pequeño" to "s", "Normal" to "m", "Grande" to "l", "Muy grande" to "xl"),
            p.subSize, "set_ssize",
        ) { p.subSize = it }
        OptionRow("Color", listOf("Blanco" to "white", "Amarillo" to "yellow"), p.subColor, "set_scolor") { p.subColor = it }
        OptionRow(
            "Fondo",
            listOf("Sin fondo (contorno negro)" to "none", "Franja oscura" to "band"),
            p.subBg, "set_sbg",
        ) { p.subBg = it }

        // ---------------- Contenido ----------------
        Spacer(Modifier.height(14.dp))
        SectionTitle("Contenido")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Categorías ocultas" + if (p.pin.isNotEmpty()) " 🔒" else "", "set_hidden") {
                UiState.push(Screen.HiddenCats)
            }
            TvButton("Borrar «Continuar viendo»", "set_clear") {
                p.clearProgress()
                Actions.toast(ctx, "Historial borrado")
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Mi IPTV 0.2 · Para usar con tu propia lista", color = C.text2, fontSize = 13.sp)
    }
}

/** Una opción con varias alternativas en fila (la elegida va marcada). */
@Composable
fun <T> OptionRow(label: String, options: List<Pair<String, T>>, selected: T, keyPrefix: String, onSelect: (T) -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(label, color = C.text3, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            options.forEachIndexed { i, (text, value) ->
                TabChip(text, value == selected, "${keyPrefix}_$i") { onSelect(value) }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, color = C.text2, fontSize = 15.sp, modifier = Modifier.width(130.dp))
        Text(value, color = Color.White, fontSize = 15.sp)
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
private fun UrlEntry(onCancel: () -> Unit, onDone: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
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
            Text("Dirección web del archivo .conf", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
                TvButton("Cargar", "url_ok", filled = true) { if (value.isNotBlank()) onDone(value) }
                TvButton("Cancelar", "url_cancel") { onCancel() }
            }
        }
    }
}
