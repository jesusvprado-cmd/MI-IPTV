package com.suso.miiptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suso.miiptv.data.Device
import com.suso.miiptv.data.Sync
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ajustes → Enviar a otros aparatos: elegir aparatos de casa y qué enviarles. */
@Composable
fun SyncScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val touch = Device.touch
    val peers = remember { mutableStateListOf<Sync.Peer>() }
    val chosen = remember { mutableStateMapOf<String, Boolean>() }
    val parts = remember { mutableStateMapOf<Sync.Part, Boolean>().apply { Sync.Part.entries.forEach { put(it, true) } } }
    // Estado por aparato mientras se envía: texto y si fue bien.
    val status = remember { mutableStateMapOf<String, Pair<String, Boolean?>>() }
    var sending by remember { mutableStateOf(false) }
    // Código de emparejamiento que se está enseñando (aparato, código).
    var code by remember { mutableStateOf<Pair<String, String>?>(null) }

    DisposableEffect(Unit) {
        val stop = Sync.discover(ctx) { p ->
            if (peers.none { it.id == p.id }) {
                peers.add(p)
                chosen.putIfAbsent(p.id, true)
            }
        }
        onDispose { stop() }
    }
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { firstFr.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())
            .padding(horizontal = if (touch) 16.dp else 48.dp, vertical = 24.dp),
    ) {
        Text("Enviar a otros aparatos", color = Color.White, fontSize = Sz.title, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Los aparatos tienen que estar en la misma wifi y con Pepino IPTV abierto. " +
                "La primera vez sale un código en los dos: comprueba que es el mismo y acepta en el otro aparato.",
            color = C.text2, fontSize = Sz.body,
        )
        Spacer(Modifier.height(16.dp))
        GroupLabel("Aparatos encontrados")
        if (peers.isEmpty()) {
            Text("Buscando en la wifi…", color = C.textFaint, fontSize = Sz.body, modifier = Modifier.padding(vertical = 10.dp))
        }
        peers.forEachIndexed { i, p ->
            val st = status[p.id]
            CheckLine(
                text = p.name,
                sub = st?.first ?: if (Sync.isPaired(ctx, p.id)) "Emparejado" else "Primera vez: pedirá un código",
                subColor = when (st?.second) { true -> C.gold; false -> C.error; null -> C.text2 },
                checked = chosen[p.id] == true,
                focusKey = "sy_dev_${p.id}",
                focusRequester = if (i == 0) firstFr else null,
            ) { chosen[p.id] = !(chosen[p.id] ?: false) }
        }
        Spacer(Modifier.height(16.dp))
        GroupLabel("Qué enviar")
        Sync.Part.entries.forEach { part ->
            CheckLine(
                text = part.label,
                sub = when (part) {
                    Sync.Part.ORDER -> "Sustituye el orden del otro aparato"
                    Sync.Part.HIDDEN -> "Sustituye las ocultas del otro aparato"
                    Sync.Part.LISTS -> "Se añaden a las suyas; no cambia la lista que usa"
                    Sync.Part.FAVS -> "Sustituye los favoritos y los perfiles del otro aparato"
                    Sync.Part.SETTINGS -> "Idiomas, subtítulos, guía, pasos al avanzar y retraso del sonido"
                },
                subColor = C.text2,
                checked = parts[part] == true,
                focusKey = "sy_part_${part.name}",
            ) { parts[part] = !(parts[part] ?: false) }
        }
        Spacer(Modifier.height(18.dp))
        val targets = peers.filter { chosen[it.id] == true }
        val what = parts.filterValues { it }.keys
        TvButton(
            if (sending) "Enviando…" else "Enviar", "sy_send", filled = true,
        ) {
            if (sending) return@TvButton
            if (targets.isEmpty()) return@TvButton Actions.toast(ctx, "Elige al menos un aparato")
            if (what.isEmpty()) return@TvButton Actions.toast(ctx, "Elige qué quieres enviar")
            sending = true
            scope.launch {
                for (p in targets) {
                    status[p.id] = "Enviando…" to null
                    try {
                        Sync.send(ctx, p, what) { c -> code = p.name to c }
                        status[p.id] = "Enviado" to true
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        status[p.id] = (e.message ?: "No se ha podido enviar") to false
                    } finally {
                        code = null
                    }
                }
                sending = false
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    code?.let { (name, c) ->
        CodeBox(
            "Comprueba que en $name sale este mismo código y pulsa «Aceptar» allí",
            c,
        )
    }
}

@Composable
private fun CheckLine(
    text: String,
    sub: String?,
    subColor: Color,
    checked: Boolean,
    focusKey: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .memFocus(focusKey) { focused = it }
            .tvClick(onClick = onClick)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(5.dp))
                .background(if (checked) C.gold else Color.Transparent)
                .border(2.dp, if (checked) C.gold else if (focused) Color.Black else C.text2, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Text("✓", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text, color = if (focused) Color.Black else Color.White, fontSize = Sz.body)
            if (sub != null) Text(sub, color = if (focused) Color(0xFF333333) else subColor, fontSize = Sz.small)
        }
    }
}

/** Recuadro grande con el código de emparejamiento. */
@Composable
private fun CodeBox(text: String, code: String, buttons: (@Composable () -> Unit)? = null) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth(0.88f).clip(RoundedCornerShape(16.dp)).background(C.card)
                .border(1.dp, C.gold.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(text, color = Color.White, fontSize = Sz.body, textAlign = TextAlign.Center)
            Text(
                code.chunked(3).joinToString(" "), color = C.gold, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
            )
            buttons?.invoke()
        }
    }
}

/** En el aparato que recibe: otro aparato quiere enviar ajustes por primera vez. */
@Composable
fun SyncAskHost() {
    val a = Sync.ask ?: return
    val fr = remember(a) { FocusRequester() }
    LaunchedEffect(a) {
        delay(120)
        runCatching { fr.requestFocus() }
    }
    androidx.activity.compose.BackHandler { Sync.answer(false) }
    CodeBox("${a.from} quiere enviarte ajustes. Acepta solo si en ${a.from} sale este mismo código.", a.code) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton("Aceptar", "sy_yes", filled = true, focusRequester = fr) { Sync.answer(true) }
            TvButton("Rechazar", "sy_no") { Sync.answer(false) }
        }
    }
}
