package com.suso.miiptv.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suso.miiptv.data.Config
import com.suso.miiptv.data.Repo
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(canCancel: Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val existing = Repo.config
    var mode by remember { mutableStateOf(existing?.type ?: "xtream") }
    var server by remember { mutableStateOf(existing?.server ?: "") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var pass by remember { mutableStateOf(existing?.pass ?: "") }
    var m3u by remember { mutableStateOf(existing?.m3uUrl ?: "") }
    var epg by remember { mutableStateOf(existing?.epgUrl ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (canCancel) BackHandler { onCancel() }

    fun connect() {
        if (busy) return
        var cfg = if (mode == "xtream") {
            Config("xtream", server = server.trim(), user = user.trim(), pass = pass.trim())
        } else {
            Config("m3u", m3uUrl = m3u.trim(), epgUrl = epg.trim())
        }
        // Si la URL M3U es de un panel Xtream (get.php?username=...&password=...), usamos Xtream:
        // así salen las carátulas, sinopsis y temporadas de películas y series.
        if (mode == "m3u") {
            val uri = Uri.parse(cfg.m3uUrl)
            val u = uri.getQueryParameter("username")
            val p = uri.getQueryParameter("password")
            if (uri.path?.endsWith("get.php") == true && !u.isNullOrBlank() && !p.isNullOrBlank() && uri.host != null) {
                cfg = Config("xtream", server = "${uri.scheme}://${uri.encodedAuthority}", user = u, pass = p, epgUrl = cfg.epgUrl)
                Actions.toast(ctx, "Lista Xtream detectada: se usará el modo Xtream Codes")
            }
        }
        if (cfg.type == "xtream" && (cfg.server.isBlank() || cfg.user.isBlank() || cfg.pass.isBlank())) {
            error = "Rellena servidor, usuario y contraseña."
            return
        }
        if (cfg.type == "m3u" && cfg.m3uUrl.isBlank()) {
            error = "Escribe la URL de la lista."
            return
        }
        cfg = cfg.copy(liveFormat = existing?.liveFormat ?: "ts", userAgent = existing?.userAgent ?: "")
        val finalCfg = cfg
        scope.launch {
            busy = true
            error = null
            try {
                val src = Repo.createSource(finalCfg)
                src.check()
                Repo.save(finalCfg, src)
                UiState.resetAll()
                onDone()
            } catch (e: Exception) {
                error = "No se pudo conectar: ${e.message ?: "error desconocido"}"
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(44.dp)
                Spacer(Modifier.width(14.dp))
                Text("Mi IPTV", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Introduce los datos de tu lista. Se guardan solo en este dispositivo.",
                color = C.text2, fontSize = 15.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TabChip("Xtream Codes", mode == "xtream", "setup_x", requestInitial = mode == "xtream") { mode = "xtream" }
                TabChip("Lista M3U", mode == "m3u", "setup_m", requestInitial = mode == "m3u") { mode = "m3u" }
            }
            if (mode == "xtream") {
                Field("Servidor (ej. http://servidor.com:8080)", server, KeyboardType.Uri) { server = it }
                Field("Usuario", user, KeyboardType.Text) { user = it }
                Field("Contraseña", pass, KeyboardType.Password, password = true) { pass = it }
            } else {
                Field("URL de la lista M3U", m3u, KeyboardType.Uri) { m3u = it }
                Field("URL de la guía EPG (opcional)", epg, KeyboardType.Uri) { epg = it }
            }
            error?.let { Text(it, color = C.error, fontSize = 15.sp) }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton(if (busy) "Conectando…" else "Conectar", "setup_go", filled = true) { connect() }
                if (canCancel) TvButton("Cancelar", "setup_cancel") { onCancel() }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.gold,
            unfocusedBorderColor = Color(0xFF555555),
            focusedLabelColor = C.gold,
            cursorColor = C.gold,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
