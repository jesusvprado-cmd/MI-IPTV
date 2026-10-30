package com.suso.miiptv.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suso.miiptv.data.Category
import com.suso.miiptv.data.EpgEntry
import com.suso.miiptv.data.Item
import com.suso.miiptv.data.Kind
import com.suso.miiptv.data.Logos
import com.suso.miiptv.data.PlayItem
import com.suso.miiptv.data.Prefs
import com.suso.miiptv.data.Repo
import com.suso.miiptv.player.PlayQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Categoría abierta en la guía (se recuerda al volver). */
internal object GuideState {
    var sel: String? = null
}

private const val WIN_MIN = 150L // minutos visibles a la vez
private const val HALF_HOUR = 30 * 60_000L

private fun floorHalfHour(t: Long): Long = t - (t % HALF_HOUR)

private val HM = SimpleDateFormat("HH:mm", Locale("es", "ES"))
private val DAY = SimpleDateFormat("EEE d", Locale("es", "ES"))

/** Programa con el cursor, para el panel de arriba. */
private data class Focused(val ch: Item, val e: EpgEntry?, val loading: Boolean = false)

/** Canales de la categoría cuya guía se carga por adelantado (los demás, al aparecer en pantalla). */
private const val PREFETCH_MAX = 150

/**
 * Guía en parrilla: canales en filas y programas a lo largo del tiempo.
 * OK sobre lo que se emite ahora = ver el canal; sobre algo futuro = recordatorio; sobre algo pasado = verlo (si el canal lo guarda).
 */
@Composable
fun GuideScreen() {
    val ctx = LocalContext.current
    var cats by remember { mutableStateOf<List<Category>>(emptyList()) }
    LaunchedEffect(Unit) {
        cats = listOf(Category(FAV, "Favoritos")) + runCatching { Repo.categories(Kind.LIVE) }.getOrDefault(emptyList())
        if (GuideState.sel == null) GuideState.sel = cats.getOrNull(1)?.id ?: FAV
    }
    var sel by remember { mutableStateOf(GuideState.sel) }
    LaunchedEffect(cats) { if (sel == null) sel = GuideState.sel }
    var channels by remember { mutableStateOf<List<Item>?>(null) }
    LaunchedEffect(sel) {
        val s = sel ?: return@LaunchedEffect
        GuideState.sel = s
        channels = null
        channels = runCatching { if (s == FAV) Repo.favorites(Kind.LIVE) else Repo.items(Kind.LIVE, s) }.getOrDefault(emptyList())
    }
    // La guía de todos los canales de la categoría se pide por adelantado, de 3 en 3 y de arriba abajo:
    // al bajar rápido las filas ya están listas, en vez de pedirse una a una al aparecer en pantalla.
    LaunchedEffect(channels) {
        val l = channels ?: return@LaunchedEffect
        val permits = kotlinx.coroutines.sync.Semaphore(2)
        kotlinx.coroutines.coroutineScope {
            l.take(PREFETCH_MAX).forEach { ch ->
                launch {
                    permits.acquire()
                    try {
                        // Si el servidor ha pedido esperar (HTTP 429), no se adelanta nada más.
                        if (!com.suso.miiptv.data.Http.busy()) Repo.guideEpg(ch)
                    } finally {
                        permits.release()
                    }
                }
            }
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    var winStart by remember { mutableLongStateOf(floorHalfHour(System.currentTimeMillis())) }
    var focused by remember { mutableStateOf<Focused?>(null) }
    var catsFocused by remember { mutableStateOf(false) }
    // Como en TV en directo: la categoría se abre al ponerse encima (con una pausa corta para poder pasar de largo).
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        delay(350)
        sel = p
    }
    val pv = Repo.prefs.version
    val touch = com.suso.miiptv.data.Device.touch
    // Móvil, o tablet en vertical: categorías como píldoras arriba y la parrilla a todo el ancho.
    val gcfg = androidx.compose.ui.platform.LocalConfiguration.current
    val phoneW = touch && gcfg.screenWidthDp < 600
    val compact = touch && (gcfg.screenWidthDp < 600 || gcfg.screenHeightDp > gcfg.screenWidthDp)

    Row(Modifier.fillMaxSize()) {
        // Categorías (en el móvil, como píldoras arriba)
        if (!compact) LazyColumn(
            modifier = Modifier.width(250.dp).fillMaxHeight().onFocusChanged { catsFocused = it.hasFocus },
            contentPadding = PaddingValues(start = 22.dp, end = 6.dp, top = 14.dp, bottom = 24.dp),
        ) {
            itemsIndexed(cats, key = { i, c -> "$i:${c.id}" }) { _, c ->
                CategoryRow(
                    name = catLabel(c.name),
                    selected = c.id == sel,
                    focusKey = "guide_cat_${c.id}",
                    onFocused = { pending = c.id },
                    cursorInMenu = catsFocused,
                ) { sel = c.id }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight().padding(end = if (compact) 6.dp else 24.dp)) {
            if (compact) CategoryChips(cats, sel) { id -> sel = id }
            InfoPanel(focused, now, touch)
            if (touch) {
                // Sin mando: botones para mover la franja horaria.
                fun shift(d: Long) {
                    val arch = channels.orEmpty().maxOfOrNull { it.archive } ?: 0
                    val base = floorHalfHour(now)
                    val min = if (arch > 0) base - minOf(arch, 1) * 24 * 3600_000L else base
                    winStart = (winStart + d).coerceIn(min, base + 24 * 3600_000L)
                }
                Row(
                    Modifier.padding(start = 12.dp, bottom = 6.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    GuidePill("◀  1 h") { shift(-60 * 60_000L) }
                    GuidePill("Ahora") { winStart = floorHalfHour(now) }
                    GuidePill("1 h  ▶") { shift(60 * 60_000L) }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val chW = if (phoneW) 116.dp else if (compact) 170.dp else 200.dp
                val areaW = maxWidth - chW
                val perMin: Dp = areaW / WIN_MIN.toFloat()
                val winEnd = winStart + WIN_MIN * 60_000L
                Column(Modifier.fillMaxSize()) {
                    TimeHeader(winStart, chW, perMin, now)
                    val list = channels
                    when {
                        list == null -> SkeletonGrid(Kind.LIVE)
                        list.isEmpty() -> EmptyText("No hay canales en esta categoría.")
                        else -> {
                            val state = rememberLazyListState()
                            // Casilla «en emisión» de cada fila: al subir o bajar, el cursor va siempre a lo que se emite ahora.
                            val liveFrs = remember(list) { HashMap<Int, androidx.compose.ui.focus.FocusRequester>() }
                            fun jump(to: Int): Boolean {
                                val fr = liveFrs[to] ?: return false
                                return runCatching { fr.requestFocus() }.isSuccess
                            }
                            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(list, key = { _, ch -> ch.key }) { i, ch ->
                                    GuideRow(
                                        ch = ch,
                                        chW = chW,
                                        compact = compact,
                                        perMin = perMin,
                                        winStart = winStart,
                                        winEnd = winEnd,
                                        now = now,
                                        pv = pv,
                                        onFocus = { e, loading -> focused = Focused(ch, e, loading) },
                                        onShift = { delta ->
                                            val min = minWindow(ch, now)
                                            val max = floorHalfHour(now) + 24 * 3600_000L
                                            val n = (winStart + delta).coerceIn(min, max)
                                            if (n != winStart) {
                                                winStart = n
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                        onPick = { e -> pick(ctx, list, i, ch, e, now) },
                                        liveFr = remember(ch.key) { androidx.compose.ui.focus.FocusRequester() }.also { liveFrs[i] = it },
                                        // Mantener ▲ en la primera fila no se sale al menú de arriba (una pulsación suelta, sí).
                                        onVertical = { d, repeat -> jump(i + d) || (repeat && i + d < 0) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Hasta dónde se puede ir hacia atrás: lo que guarde el canal (máx. 1 día) o, si no guarda nada, hasta ahora. */
private fun minWindow(ch: Item, now: Long): Long {
    val base = floorHalfHour(now)
    return if (ch.archive > 0) base - minOf(ch.archive, 1) * 24 * 3600_000L else base
}

private fun pick(ctx: Context, list: List<Item>, i: Int, ch: Item, e: EpgEntry?, now: Long) {
    when {
        e == null || (e.start <= now && e.end > now) -> Actions.playLive(ctx, list, i)
        e.start > now -> {
            val on = Repo.prefs.toggleReminder(Prefs.Reminder(ch, e.title, e.start))
            Actions.toast(ctx, if (on) "Te avisaré cuando empiece «${e.title}»" else "Recordatorio quitado")
        }
        // El proveedor dice que ese programa no está grabado: no se intenta (daría error 404).
        e.archived == false -> Actions.toast(ctx, "Este programa no está grabado en el servidor")
        else -> {
            val url = Repo.catchupUrl(ch, e)
            if (url == null) {
                Actions.toast(ctx, "Este canal no guarda los programas ya emitidos")
            } else {
                PlayQueue.start(
                    ctx,
                    listOf(PlayItem("CU|${ch.key}|${e.start}", e.title, "${ch.name} · ${HM.format(Date(e.start))}", ch.image, url, false)),
                    0, 0,
                )
            }
        }
    }
}

@Composable
private fun InfoPanel(f: Focused?, now: Long, touch: Boolean = false) {
    Column(Modifier.fillMaxWidth().height(if (touch) 104.dp else 118.dp).padding(start = 14.dp, top = if (touch) 8.dp else 14.dp, end = 10.dp)) {
        if (f == null) {
            Text("Guía de programación", color = Color.White, fontSize = if (touch) Sz.sub else Sz.title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (touch) "Toca un programa para ver su información y vuelve a tocarlo para verlo, poner un recordatorio o verlo desde el principio (si el canal lo guarda)."
                else "OK sobre lo que se emite ahora: ver el canal · sobre un programa futuro: recordatorio · sobre uno pasado: verlo (si el canal lo guarda)",
                color = C.text2, fontSize = Sz.small, maxLines = 2,
            )
            return@Column
        }
        val e = f.e
        Text(e?.title ?: f.ch.name, color = C.gold, fontSize = Sz.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        val whenTxt = if (e == null) (if (f.loading) "Cargando la guía…" else "Sin guía") else {
            val day = if (DAY.format(Date(e.start)) != DAY.format(Date(now))) DAY.format(Date(e.start)) + " · " else ""
            val state = when {
                e.start <= now && e.end > now -> "  ·  EN EMISIÓN"
                e.end <= now && f.ch.archive > 0 && e.archived == false -> "  ·  ya emitido · no está grabado"
                e.end <= now && f.ch.archive > 0 -> "  ·  ya emitido · OK para verlo"
                e.end <= now -> "  ·  ya emitido"
                Repo.prefs.hasReminder("${f.ch.key}@${e.start}") -> "  ·  con recordatorio"
                else -> ""
            }
            "$day${HM.format(Date(e.start))} – ${HM.format(Date(e.end))}  ·  ${f.ch.name}$state"
        }
        Text(whenTxt, color = Color.White, fontSize = Sz.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        e?.desc?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = C.text2, fontSize = Sz.small, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TimeHeader(winStart: Long, chW: Dp, perMin: Dp, now: Long) {
    Box(Modifier.fillMaxWidth().height(30.dp)) {
        Text(
            DAY.format(Date(winStart)).replaceFirstChar { it.uppercase() }, color = C.text2, fontSize = Sz.small,
            modifier = Modifier.padding(start = 14.dp, top = 6.dp),
        )
        for (k in 0 until (WIN_MIN / 30).toInt()) {
            val t = winStart + k * HALF_HOUR
            Text(
                HM.format(Date(t)), color = Color.White, fontSize = Sz.small, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.offset(x = chW + perMin * (k * 30f) + 6.dp, y = 6.dp),
            )
        }
        val nx = (now - winStart) / 60_000f
        if (nx in 0f..WIN_MIN.toFloat()) {
            Box(Modifier.offset(x = chW + perMin * nx - 1.dp, y = 22.dp).width(2.dp).height(8.dp).background(C.gold))
        }
    }
}

@Composable
private fun GuideRow(
    ch: Item,
    chW: Dp,
    compact: Boolean,
    perMin: Dp,
    winStart: Long,
    winEnd: Long,
    now: Long,
    pv: Int,
    onFocus: (EpgEntry?, Boolean) -> Unit,
    onShift: (Long) -> Boolean,
    onPick: (EpgEntry?) -> Unit,
    liveFr: androidx.compose.ui.focus.FocusRequester,
    onVertical: (Int, Boolean) -> Boolean,
) {
    var epg by remember(ch.key) { mutableStateOf<List<EpgEntry>?>(null) }
    // ¿Está el cursor en esta fila? Si estaba en la casilla «Cargando…» cuando llega la guía, pasa a lo que se emite ahora.
    var rowFocused by remember { mutableStateOf(false) }
    LaunchedEffect(ch.key) {
        val l = Repo.guideEpg(ch)
        val had = rowFocused
        epg = l
        if (had) {
            delay(60)
            runCatching { liveFr.requestFocus() }
        }
    }
    val logo = remember(ch.key, Logos.version) { Logos.url(ch.name, ch.image) }
    Row(Modifier.fillMaxWidth().height(58.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        // Canal: logo y nombre
        Row(Modifier.width(chW).fillMaxHeight().padding(start = if (compact) 8.dp else 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Mismo fondo que en la lista de canales: blanco para los marcados, claro para los logos oscuros
            // y, si no, el de las tarjetas (antes era un gris translúcido para todos los demás).
            var shown by remember(logo) { mutableStateOf(logo) }
            val white = LogoTone.wantsWhite(logo) && shown?.substringBefore('#') == logo?.substringBefore('#')
            val bg = if (logo != null) Color.White else C.card
            Box(
                Modifier.width(if (compact) 48.dp else 62.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                if (logo != null) ChannelLogoImage(logo, Modifier.fillMaxSize().padding(5.dp), fallback = ch.image) { shown = it }
            }
            Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
            Text(ch.name, color = Color(0xFFCFCFCF), fontSize = if (compact) Sz.label else Sz.small, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
        }
        // Programas
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).onFocusChanged { rowFocused = it.hasFocus }) {
            val l = epg
            val visible = l?.filter { it.end > winStart && it.start < winEnd }.orEmpty()
            // Casilla a la que va el cursor al subir o bajar: lo que se emite ahora o, si no está en la franja, la primera.
            val target = visible.firstOrNull { it.start <= now && it.end > now } ?: visible.firstOrNull()
            if (l == null || visible.isEmpty()) {
                // Cargando, o sin guía en esta franja: una sola casilla, para que el cursor pueda pasar por la fila
                // (antes, mientras cargaba, la fila no tenía dónde ponerse y el cursor se iba lejos o al menú).
                ProgramCell(
                    title = when {
                        l == null -> "Cargando la guía…"
                        l.isEmpty() -> "Sin guía"
                        else -> "Sin programación en esta franja"
                    },
                    x = 0.dp, w = perMin * WIN_MIN.toFloat(), live = l != null, past = false, reminder = false, archived = false,
                    onFocus = { onFocus(null, l == null) },
                    onLeft = { onShift(-60 * 60_000L) },
                    onRight = { onShift(60 * 60_000L) },
                    onClick = { onPick(null) },
                    liveFr = liveFr,
                    onVertical = onVertical,
                )
            }
            visible.forEachIndexed { idx, e ->
                key(e.start) {
                    val s = maxOf(e.start, winStart)
                    val en = minOf(e.end, winEnd)
                    val x = perMin * ((s - winStart) / 60_000f)
                    val w = perMin * ((en - s) / 60_000f)
                    val past = e.end <= now
                    ProgramCell(
                        title = e.title,
                        x = x, w = w,
                        live = e.start <= now && e.end > now,
                        past = past,
                        reminder = remember(pv, e.start) { Repo.prefs.hasReminder("${ch.key}@${e.start}") },
                        // ↺ solo si el programa está grabado (o si el proveedor no lo indica y el canal guarda lo emitido).
                        archived = past && ch.archive > 0 && e.archived != false,
                        onFocus = { onFocus(e, false) },
                        // En el primero/último visible, ◀ ▶ mueven la franja horaria en lugar de salir de la fila.
                        onLeft = { if (idx == 0) onShift(-60 * 60_000L) else false },
                        onRight = { if (idx == visible.lastIndex) onShift(60 * 60_000L) || true else false },
                        onClick = { onPick(e) },
                        liveFr = if (e === target) liveFr else null,
                        onVertical = onVertical,
                    )
                }
            }
            // Línea de «ahora»
            val nx = (now - winStart) / 60_000f
            if (nx in 0f..WIN_MIN.toFloat()) {
                Box(Modifier.offset(x = perMin * nx - 1.dp).width(2.dp).fillMaxHeight().background(C.gold.copy(alpha = 0.8f)))
            }
        }
    }
}

@Composable
private fun ProgramCell(
    title: String,
    x: Dp,
    w: Dp,
    live: Boolean,
    past: Boolean,
    reminder: Boolean,
    archived: Boolean,
    onFocus: () -> Unit,
    onLeft: () -> Boolean,
    onRight: () -> Boolean,
    onClick: () -> Unit,
    liveFr: androidx.compose.ui.focus.FocusRequester? = null,
    onVertical: (Int, Boolean) -> Boolean = { _, _ -> false },
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    val click by androidx.compose.runtime.rememberUpdatedState(onClick)
    val bg = when {
        focused -> C.gold
        live -> Color(0xFF1E3A22)
        past -> Color(0xFF111813)
        else -> Color(0xFF18221A)
    }
    Box(
        Modifier
            .offset(x = x)
            .width((w - 3.dp).coerceAtLeast(6.dp))
            .fillMaxHeight()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus() else pressed = false
            }
            .onPreviewKeyEvent { e ->
                when {
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft -> onLeft()
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight -> onRight()
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown -> onVertical(1, e.nativeKeyEvent.repeatCount > 0)
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp -> onVertical(-1, e.nativeKeyEvent.repeatCount > 0)
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) pressed = true
                        if (e.type == KeyEventType.KeyUp && pressed) {
                            pressed = false
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(fr)
            .then(if (liveFr != null) Modifier.focusRequester(liveFr) else Modifier)
            .focusable()
            // Táctil: el primer toque lo selecciona (se ve su información), el segundo hace lo mismo que OK.
            .pointerInput(Unit) {
                detectTapGestures { if (focused) click() else runCatching { fr.requestFocus() } }
            }
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .then(if (live && !focused) Modifier.border(1.dp, C.gold.copy(alpha = 0.45f), RoundedCornerShape(6.dp)) else Modifier)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (reminder) Text("⏰ ", fontSize = Sz.small, color = if (focused) Color.Black else C.gold)
            if (archived) Text("↺ ", fontSize = Sz.small, fontWeight = FontWeight.Bold, color = if (focused) Color.Black else C.gold)
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = Sz.small,
                fontWeight = if (focused || live) FontWeight.SemiBold else FontWeight.Medium,
                color = when {
                    focused -> Color.Black
                    past -> Color(0xFF8A8A8A)
                    else -> Color.White
                },
            )
        }
    }
}

/** Aviso de recordatorio: cuando está a punto de empezar un programa marcado, permite verlo con OK. */
@Composable
fun ReminderPopup(onVisible: (Boolean) -> Unit = {}, onWatch: (Prefs.Reminder) -> Unit) {
    var current by remember { mutableStateOf<Prefs.Reminder?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (current == null) {
                val now = System.currentTimeMillis()
                current = Repo.prefs.reminders().firstOrNull { it.start - now <= 60_000L && now - it.start < 10 * 60_000L }
                if (current != null) onVisible(true)
            }
            delay(15_000)
        }
    }
    val r = current ?: return
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(r.id) {
        delay(80)
        runCatching { fr.requestFocus() }
        delay(45_000)
        if (current?.id == r.id) {
            Repo.prefs.removeReminder(r.id)
            current = null
            onVisible(false)
        }
    }
    Box(Modifier.fillMaxSize().padding(30.dp), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xF20B140D))
                .border(1.dp, C.gold.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(20.dp),
        ) {
            Text("⏰  Empieza ahora", color = C.gold, fontSize = Sz.body, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(r.title, color = Color.White, fontSize = Sz.sub, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(r.channel.name, color = C.text2, fontSize = Sz.body)
            Spacer(Modifier.height(14.dp))
            Row {
                TvButton("Ver ahora", "rem_watch", filled = true, focusRequester = fr) {
                    Repo.prefs.removeReminder(r.id)
                    current = null
                    onVisible(false)
                    onWatch(r)
                }
                Spacer(Modifier.width(10.dp))
                TvButton("Cerrar", "rem_close") {
                    Repo.prefs.removeReminder(r.id)
                    current = null
                    onVisible(false)
                }
            }
        }
    }
}


@Composable
private fun GuidePill(text: String, onClick: () -> Unit) {
    Text(
        text, color = Color(0xFFDDDDDD), fontSize = Sz.small, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1F2A21))
            .tvClick(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
