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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Categoría abierta en la guía (se recuerda al volver). */
private object GuideState {
    var sel: String? = null
}

private const val WIN_MIN = 150L // minutos visibles a la vez
private const val HALF_HOUR = 30 * 60_000L

private fun floorHalfHour(t: Long): Long = t - (t % HALF_HOUR)

private val HM = SimpleDateFormat("HH:mm", Locale("es", "ES"))
private val DAY = SimpleDateFormat("EEE d", Locale("es", "ES"))

/** Programa con el cursor, para el panel de arriba. */
private data class Focused(val ch: Item, val e: EpgEntry?)

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
    val compact = touch && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600

    Row(Modifier.fillMaxSize()) {
        // Categorías (en el móvil, como píldoras arriba)
        if (!compact) LazyColumn(
            modifier = Modifier.width(250.dp).fillMaxHeight().onFocusChanged { catsFocused = it.hasFocus },
            contentPadding = PaddingValues(start = 22.dp, end = 6.dp, top = 14.dp, bottom = 24.dp),
        ) {
            itemsIndexed(cats, key = { i, c -> "$i:${c.id}" }) { _, c ->
                CategoryRow(
                    name = c.name,
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
                val chW = if (compact) 116.dp else 200.dp
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
                                        onFocus = { e -> focused = Focused(ch, e) },
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
                                        onVertical = { d -> jump(i + d) },
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
            Text("Guía de programación", color = Color.White, fontSize = if (touch) 20.sp else 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (touch) "Toca un programa para ver su información y vuelve a tocarlo para verlo, poner un recordatorio o verlo desde el principio (si el canal lo guarda)."
                else "OK sobre lo que se emite ahora: ver el canal · sobre un programa futuro: recordatorio · sobre uno pasado: verlo (si el canal lo guarda)",
                color = C.text2, fontSize = 13.sp, maxLines = 2,
            )
            return@Column
        }
        val e = f.e
        Text(e?.title ?: f.ch.name, color = C.gold, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        val whenTxt = if (e == null) "Sin guía" else {
            val day = if (DAY.format(Date(e.start)) != DAY.format(Date(now))) DAY.format(Date(e.start)) + " · " else ""
            val state = when {
                e.start <= now && e.end > now -> "  ·  EN EMISIÓN"
                e.end <= now && f.ch.archive > 0 -> "  ·  ya emitido · OK para verlo"
                e.end <= now -> "  ·  ya emitido"
                Repo.prefs.hasReminder("${f.ch.key}@${e.start}") -> "  ·  con recordatorio"
                else -> ""
            }
            "$day${HM.format(Date(e.start))} – ${HM.format(Date(e.end))}  ·  ${f.ch.name}$state"
        }
        Text(whenTxt, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        e?.desc?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = C.text2, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TimeHeader(winStart: Long, chW: Dp, perMin: Dp, now: Long) {
    Box(Modifier.fillMaxWidth().height(30.dp)) {
        Text(
            DAY.format(Date(winStart)).replaceFirstChar { it.uppercase() }, color = C.text2, fontSize = 13.sp,
            modifier = Modifier.padding(start = 14.dp, top = 6.dp),
        )
        for (k in 0 until (WIN_MIN / 30).toInt()) {
            val t = winStart + k * HALF_HOUR
            Text(
                HM.format(Date(t)), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
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
    onFocus: (EpgEntry?) -> Unit,
    onShift: (Long) -> Boolean,
    onPick: (EpgEntry?) -> Unit,
    liveFr: androidx.compose.ui.focus.FocusRequester,
    onVertical: (Int) -> Boolean,
) {
    var epg by remember(ch.key) { mutableStateOf<List<EpgEntry>?>(null) }
    LaunchedEffect(ch.key) { epg = Repo.guideEpg(ch) }
    val logo = remember(ch.key, Logos.version) { Logos.url(ch.name, ch.image) }
    Row(Modifier.fillMaxWidth().height(58.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        // Canal: logo y nombre
        Row(Modifier.width(chW).fillMaxHeight().padding(start = if (compact) 8.dp else 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Igual que en la lista de canales: blanco para los marcados y para los logos oscuros; si no, el fondo de tarjeta.
            val white = LogoTone.wantsWhite(logo) || LogoTone.isDark(logo) || com.suso.miiptv.data.Device.touch
            Box(
                Modifier.width(if (compact) 48.dp else 62.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                    .background(if (white) Color.White else C.card),
                contentAlignment = Alignment.Center,
            ) {
                if (logo != null) ChannelLogoImage(
                    logo, Modifier.fillMaxSize().padding(5.dp), fallback = ch.image,
                    onWhite = com.suso.miiptv.data.Device.touch && !LogoTone.wantsWhite(logo) && !LogoTone.isDark(logo),
                )
            }
            Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
            Text(ch.name, color = Color(0xFFCFCFCF), fontSize = if (compact) 10.sp else 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
        }
        // Programas
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp))) {
            val l = epg
            val visible = l?.filter { it.end > winStart && it.start < winEnd }.orEmpty()
            if (l != null && visible.isEmpty()) {
                // Sin guía en esta franja: una sola casilla para poder ver el canal.
                ProgramCell(
                    title = if (l.isEmpty()) "Sin guía" else "Sin programación en esta franja",
                    x = 0.dp, w = perMin * WIN_MIN.toFloat(), live = true, past = false, reminder = false, archived = false,
                    onFocus = { onFocus(null) },
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
                        archived = past && ch.archive > 0,
                        onFocus = { onFocus(e) },
                        // En el primero/último visible, ◀ ▶ mueven la franja horaria en lugar de salir de la fila.
                        onLeft = { if (idx == 0) onShift(-60 * 60_000L) else false },
                        onRight = { if (idx == visible.lastIndex) onShift(60 * 60_000L) || true else false },
                        onClick = { onPick(e) },
                        liveFr = if (e.start <= now && e.end > now) liveFr else null,
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
    onVertical: (Int) -> Boolean = { false },
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
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown -> onVertical(1)
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp -> onVertical(-1)
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
            if (reminder) Text("⏰ ", fontSize = 12.sp, color = if (focused) Color.Black else C.gold)
            if (archived) Text("↺ ", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (focused) Color.Black else C.gold)
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
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
            Text("⏰  Empieza ahora", color = C.gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(r.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(r.channel.name, color = C.text2, fontSize = 14.sp)
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
        text, color = Color(0xFFDDDDDD), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1F2A21))
            .tvClick(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
