package com.suso.miiptv.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suso.miiptv.data.AccountInfo
import com.suso.miiptv.data.Repo
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(onChangeList: () -> Unit) {
    val ctx = LocalContext.current
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
        Spacer(Modifier.height(10.dp))

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
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton("Recargar lista", "set_reload", icon = Icons.Filled.Refresh, focusRequester = firstFr) {
                Repo.reload()
                Actions.toast(ctx, "Lista recargada")
            }
            TvButton("Cambiar lista o cuenta", "set_change") { onChangeList() }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("Reproducción")
        Text(
            "Formato de los canales en directo (solo Xtream). Si un canal no carga, prueba el otro.",
            color = C.text2, fontSize = 14.sp,
        )
        if (cfg != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TabChip("TS (recomendado)", cfg.liveFormat != "m3u8", "set_ts") {
                    if (cfg.liveFormat == "m3u8") Repo.save(cfg.copy(liveFormat = "ts"))
                }
                TabChip("HLS (m3u8)", cfg.liveFormat == "m3u8", "set_hls") {
                    if (cfg.liveFormat != "m3u8") Repo.save(cfg.copy(liveFormat = "m3u8"))
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("Historial")
        TvButton("Borrar «Continuar viendo»", "set_clear") {
            Repo.prefs.clearProgress()
            Actions.toast(ctx, "Historial borrado")
        }

        Spacer(Modifier.height(24.dp))
        Text("Mi IPTV 0.1 · Para usar con tu propia lista", color = C.text2, fontSize = 13.sp)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, color = C.text2, fontSize = 15.sp, modifier = Modifier.width(130.dp))
        Text(value, color = Color.White, fontSize = 15.sp)
    }
}
