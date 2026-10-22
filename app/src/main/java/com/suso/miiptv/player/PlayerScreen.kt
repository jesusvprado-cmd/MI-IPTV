package com.suso.miiptv.player

import android.app.Activity
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C as MC
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import coil.compose.AsyncImage
import com.suso.miiptv.data.Logos
import com.suso.miiptv.ui.ChannelLogoImage
import com.suso.miiptv.ui.Clock
import com.suso.miiptv.ui.LogoTone
import com.suso.miiptv.data.PlayItem
import com.suso.miiptv.data.Repo
import com.suso.miiptv.ui.ReminderPopup
import com.suso.miiptv.ui.C
import com.suso.miiptv.ui.fmtTime
import com.suso.miiptv.ui.hm
import com.suso.miiptv.ui.tvClick
import com.suso.miiptv.vpn.VpnManager
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(ctl: PlayerCtl, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val item = ctl.current
    var overlay by remember { mutableStateOf(true) }
    var overlayTick by remember { mutableIntStateOf(0) }
    var listOpen by remember { mutableStateOf(false) }
    var tracksOpen by remember { mutableStateOf(false) }
    // Teclado numérico en pantalla (OK mantenido en un directo) y número tecleado con el mando.
    var padOpen by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var okLong by remember { mutableStateOf(false) }
    var reminderOpen by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(Repo.prefs.resizeMode) }
    // Permiso de Android para la VPN (solo la primera vez).
    val vpnPerm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) VpnManager.connect()
        else Toast.makeText(ctx, "Sin permiso no se puede activar la VPN", Toast.LENGTH_SHORT).show()
    }
    fun toggleVpn() {
        val p = Repo.prefs
        if (VpnManager.state == VpnManager.State.ON || VpnManager.state == VpnManager.State.CONNECTING) {
            VpnManager.disconnect()
            return
        }
        if (p.vpnMode == "off") p.vpnMode = if (p.wgConf != null) "wg" else "warp"
        val intent = VpnManager.permissionIntent(ctx)
        if (intent == null) {
            VpnManager.connect()
            return
        }
        try {
            vpnPerm.launch(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(ctx, "Este dispositivo no permite que las apps usen VPN", Toast.LENGTH_SHORT).show()
        }
    }
    // Al conectar o desconectar la VPN se vuelve a abrir el canal por la nueva ruta.
    val vpnChanges = VpnManager.changes
    var lastVpnChanges by remember { mutableIntStateOf(vpnChanges) }
    LaunchedEffect(vpnChanges) {
        if (vpnChanges != lastVpnChanges) {
            lastVpnChanges = vpnChanges
            ctl.retry()
        }
    }
    val rootFr = remember { FocusRequester() }
    val pv = Repo.prefs.version
    val fav = remember(pv, ctl.index) { ctl.isFavorite() }

    fun show() {
        overlay = true
        overlayTick++
    }

    fun toggleFav() {
        val added = ctl.toggleFavorite() ?: return
        Toast.makeText(ctx, if (added) "Añadido a favoritos" else "Quitado de favoritos", Toast.LENGTH_SHORT).show()
        show()
    }

    LaunchedEffect(ctl.index) { show() }
    LaunchedEffect(overlayTick, overlay, ctl.isPlaying, ctl.error) {
        if (overlay && ctl.error == null && (item.live || ctl.isPlaying)) {
            delay(5000)
            overlay = false
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            ctl.tick()
            delay(500)
        }
    }
    // Número tecleado: al dejar de pulsar un momento, se salta a ese canal.
    LaunchedEffect(typed) {
        if (typed.isEmpty() || padOpen) return@LaunchedEffect
        delay(1600)
        val n = typed.toIntOrNull()
        typed = ""
        if (n != null && !ctl.zapToNumber(n)) Toast.makeText(ctx, "No hay canal $n", Toast.LENGTH_SHORT).show()
    }
    // Avisos breves (reconexión, VPN...).
    val notice = ctl.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3500)
            ctl.clearNotice()
        }
    }
    LaunchedEffect(listOpen, tracksOpen, padOpen, reminderOpen) {
        if (!listOpen && !tracksOpen && !padOpen && !reminderOpen) {
            delay(40)
            runCatching { rootFr.requestFocus() }
        }
    }

    BackHandler {
        when {
            padOpen -> {
                padOpen = false
                typed = ""
            }
            ctl.scrubPos != null -> ctl.cancelScrub()
            tracksOpen -> tracksOpen = false
            listOpen -> listOpen = false
            ctl.backToLive() -> {}
            else -> onExit()
        }
    }

    fun step(repeat: Int): Long = ctl.seekStepMs(repeat)

    fun shortOk() {
        when {
            ctl.commitScrub() -> show()
            ctl.error != null -> ctl.retry()
            item.live -> if (overlay) toggleFav() else show()
            else -> {
                ctl.togglePlay()
                show()
            }
        }
    }

    val onKey: (KeyEvent) -> Boolean = handler@{ e ->
        if (listOpen || tracksOpen || padOpen || reminderOpen) return@handler false
        val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        // En directo, OK se decide al soltar: pulsación corta = lo de siempre; mantenido = teclado de números.
        if (isOk && item.live) {
            if (e.type == KeyEventType.KeyUp) {
                if (!okLong) shortOk()
                okLong = false
            } else if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 1 && ctl.error == null) {
                okLong = true
                typed = ""
                padOpen = true
            }
            return@handler true
        }
        if (e.type != KeyEventType.KeyDown) return@handler false
        val rep = e.nativeKeyEvent.repeatCount
        // Números del mando (teles con teclado numérico).
        val digit = when (e.nativeKeyEvent.keyCode) {
            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> e.nativeKeyEvent.keyCode - android.view.KeyEvent.KEYCODE_0
            in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> e.nativeKeyEvent.keyCode - android.view.KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0 && item.live) {
            if (rep == 0 && typed.length < 4) typed += digit
            return@handler true
        }
        if (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_LAST_CHANNEL) {
            if (!ctl.zapBack()) show()
            return@handler true
        }
        when (e.key) {
            Key.DirectionUp -> {
                if (item.live) ctl.zap(-1) else show()
                true
            }
            Key.DirectionDown -> {
                if (item.live) ctl.zap(1) else tracksOpen = true
                true
            }
            Key.Menu -> {
                tracksOpen = true
                true
            }
            Key.ChannelUp -> {
                ctl.zap(1)
                true
            }
            Key.ChannelDown -> {
                ctl.zap(-1)
                true
            }
            Key.DirectionLeft -> {
                if (item.live) {
                    if (ctl.items.size > 1) listOpen = true else show()
                } else {
                    ctl.scrubBy(-step(rep))
                    show()
                }
                true
            }
            Key.DirectionRight -> {
                if (item.live) {
                    tracksOpen = true
                } else {
                    ctl.scrubBy(step(rep))
                    show()
                }
                true
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (rep == 0) shortOk()
                true
            }
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.Spacebar -> {
                ctl.togglePlay()
                show()
                true
            }
            Key.MediaFastForward -> {
                ctl.scrubBy(30_000)
                show()
                true
            }
            Key.MediaRewind -> {
                ctl.scrubBy(-30_000)
                show()
                true
            }
            else -> false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent(onKey)
            .focusRequester(rootFr)
            .focusable()
    ) {
        val ratio = when (aspect) {
            "16:9" -> 16f / 9f
            "4:3" -> 4f / 3f
            else -> null
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        player = ctl.player
                        isFocusable = false
                        isFocusableInTouchMode = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        keepScreenOn = true
                        styleSubtitles(subtitleView)
                        // Se escucha después que el propio PlayerView, así que nuestros subtítulos (abajo) son los que quedan.
                        val sv = subtitleView
                        val l = object : androidx.media3.common.Player.Listener {
                            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                                sv?.setCues(bottomCues(cueGroup.cues))
                            }
                        }
                        ctl.player.addListener(l)
                        tag = l
                    }
                },
                onRelease = { v ->
                    (v.tag as? androidx.media3.common.Player.Listener)?.let { ctl.player.removeListener(it) }
                    v.player = null
                },
                update = { v ->
                    v.resizeMode = when (aspect) {
                        "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        "fill", "16:9", "4:3" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize(),
            )
        }

        // Capa táctil (móvil y tablet).
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { if (overlay) overlay = false else show() })
                }
        )

        val err = ctl.error
        if (err != null) {
            Column(
                Modifier.align(Alignment.Center).padding(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xCC000000)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(item.title, color = C.gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(err, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
                Text(
                    "Pulsa OK para reintentar" + if (item.live) " · ▲▼ para cambiar de canal" else "",
                    color = C.text2, fontSize = 14.sp,
                    modifier = Modifier.focusProperties { canFocus = false }.clickable { ctl.retry() },
                )
            }
        }

        if (overlay && err == null) {
            if (item.live) LiveOverlay(ctl, item, fav, onFav = { toggleFav() })
            else VodOverlay(ctl, item)
        }

        if (listOpen) {
            ChannelListPanel(ctl, onPick = { i ->
                listOpen = false
                if (i != ctl.index) ctl.play(i, 0)
            }, onClose = { listOpen = false })
        }

        // Número que se está tecleando con el mando.
        if (typed.isNotEmpty() && !padOpen) {
            Text(
                typed, color = C.gold, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 50.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xCC000000)).padding(horizontal = 22.dp, vertical = 6.dp),
            )
        }

        // Aviso breve arriba (reconexión automática, VPN...).
        if (notice != null) {
            Text(
                notice, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Color(0xE6102414))
                    .border(1.dp, C.gold.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }

        if (padOpen) {
            NumberPad(
                typed = typed,
                hasPrevious = true,
                onDigit = { d -> if (typed.length < 4) typed += d },
                onDelete = { typed = typed.dropLast(1) },
                onPrevious = {
                    padOpen = false
                    typed = ""
                    if (!ctl.zapBack()) Toast.makeText(ctx, "Aún no has cambiado de canal", Toast.LENGTH_SHORT).show()
                },
                onGo = {
                    val n = typed.toIntOrNull()
                    padOpen = false
                    typed = ""
                    if (n != null && !ctl.zapToNumber(n)) Toast.makeText(ctx, "No hay canal $n", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Aviso de un programa con recordatorio que empieza ahora.
        ReminderPopup(onVisible = { reminderOpen = it }) { r ->
            val i = ctl.items.indexOfFirst { it.channel?.key == r.channel.key }
            if (i >= 0) ctl.play(i, 0) else ctl.playCatchup(Repo.playItem(r.channel))
        }

        if (tracksOpen) {
            OptionsPanel(
                ctl,
                Modifier.align(Alignment.TopEnd),
                aspect = aspect,
                onAspect = { aspect = nextAspect(aspect) },
                onVpn = { toggleVpn() },
                onStartOver = { ch, e ->
                    tracksOpen = false
                    val url = Repo.catchupUrl(ch, e)
                    if (url == null) {
                        Toast.makeText(ctx, "Este canal no permite verlo desde el principio", Toast.LENGTH_SHORT).show()
                    } else {
                        ctl.playCatchup(
                            com.suso.miiptv.data.PlayItem("CU|${ch.key}|${e.start}", e.title, ch.name + " · desde el principio", ch.image, url, false)
                        )
                    }
                },
                onExternal = {
                    tracksOpen = false
                    if (PlayQueue.openExternal(ctx, ctl.current)) ctl.player.pause()
                },
            ) { tracksOpen = false }
        }
    }
}

/** Barra inferior del directo: canal, programa actual con su progreso y el siguiente. */
@Composable
private fun LiveOverlay(ctl: PlayerCtl, item: PlayItem, fav: Boolean, onFav: () -> Unit) {
    val now = System.currentTimeMillis()
    val cur = ctl.epg.firstOrNull { it.start <= now && it.end > now }
    val next = ctl.epg.firstOrNull { it.start >= (cur?.end ?: now) }
    val number = item.channel?.num?.takeIf { it > 0 } ?: (ctl.index + 1)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(280.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0x99000000), 1f to Color(0xF2000000)))
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 40.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(120.dp).height(76.dp).clip(RoundedCornerShape(8.dp))
                    .background(
                        Logos.url(item.title, item.image).let { u ->
                            when {
                                LogoTone.wantsWhite(u) -> Color.White
                                LogoTone.isDark(u) -> LogoTone.light
                                else -> Color(0x33FFFFFF)
                            }
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val logo = remember(item.key, Logos.version) { Logos.url(item.title, item.image) }
                if (logo != null) {
                    ChannelLogoImage(logo, Modifier.fillMaxSize().padding(8.dp), fallback = item.image)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$number", color = C.gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Text(item.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    StreamBadges(ctl.streamBadges)
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorito",
                        tint = if (fav) C.fav else C.text2,
                        modifier = Modifier.size(24.dp).focusProperties { canFocus = false }.clickable { onFav() },
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (cur != null) {
                    Text("${hm(cur.start)} - ${hm(cur.end)}   ${cur.title}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    val frac = ((now - cur.start).toFloat() / (cur.end - cur.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                    val left = ((cur.end - now) / 60_000).coerceAtLeast(0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.fillMaxWidth(0.55f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
                            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(C.gold))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(if (left > 0) "Quedan $left min" else "Terminando", color = C.text2, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text("Sin guía de programación", color = C.text2, fontSize = 16.sp)
                }
                if (next != null) {
                    Text("Después  ${hm(next.start)}   ${next.title}", color = C.text2, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("▲ ▼  Cambiar canal", color = C.text2, fontSize = 13.sp)
                Text("◀  Lista de canales", color = C.text2, fontSize = 13.sp)
                Text("▶  Opciones", color = C.text2, fontSize = 13.sp)
                Text("OK  Favorito", color = C.text2, fontSize = 13.sp)
            }
        }
    }
}

/** Barra inferior de películas y series, como en la captura: carátula, título dorado y barra dorada. */
@Composable
private fun VodOverlay(ctl: PlayerCtl, item: PlayItem) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(320.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0x99000000), 1f to Color(0xF2000000)))
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 40.dp, vertical = 30.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (item.image != null) {
                AsyncImage(
                    model = item.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(96.dp).height(140.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, color = C.gold, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    StreamBadges(ctl.streamBadges)
                }
                item.subtitle?.let {
                    Text(it, color = Color(0xFFDDDDDD), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (ctl.duration > 0) {
                    val rest = ((ctl.duration - ctl.position).coerceAtLeast(0) / ctl.speed.coerceAtLeast(0.25f)).toLong()
                    Text("Termina a las ${hm(System.currentTimeMillis() + rest)}", color = C.text2, fontSize = 14.sp)
                }
                Text("▼  Opciones     ◀ ▶  Retroceder / avanzar     OK  Pausa", color = C.text2, fontSize = 13.sp, maxLines = 1)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(C.gold)
                            .focusProperties { canFocus = false }
                            .clickable { ctl.togglePlay() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (ctl.isPlaying) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.width(6.dp).height(20.dp).background(Color.Black))
                                Box(Modifier.width(6.dp).height(20.dp).background(Color.Black))
                            }
                        } else {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Reproducir", tint = Color.Black, modifier = Modifier.size(32.dp))
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    val shownPos = ctl.scrubPos ?: ctl.position
                    Text(fmtTime(shownPos), color = if (ctl.scrubPos != null) C.gold else Color.White, fontSize = 15.sp)
                    Spacer(Modifier.width(14.dp))
                    val frac = if (ctl.duration > 0) (shownPos.toFloat() / ctl.duration).coerceIn(0f, 1f) else 0f
                    SeekBar(frac, Modifier.weight(1f), preview = ctl.scrubPos) { f -> ctl.seekToFraction(f) }
                    Spacer(Modifier.width(14.dp))
                    Text(fmtTime(ctl.duration), color = Color.White, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun SeekBar(fraction: Float, modifier: Modifier, preview: Long? = null, onSeek: (Float) -> Unit) {
    BoxWithConstraints(
        modifier
            .height(20.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> if (size.width > 0) onSeek(o.x / size.width) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val w = maxWidth
        // Minuto al que vas, sobre la barra, mientras avanzas o retrocedes.
        if (preview != null) {
            val tw = 90.dp
            val x = ((w * fraction) - tw / 2).coerceIn(0.dp, (w - tw).coerceAtLeast(0.dp))
            Box(
                Modifier
                    .offset(x = x)
                    .layout { measurable, constraints ->
                        val pl = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                        layout(pl.width, 0) { pl.place(0, -pl.height - 10.dp.roundToPx()) }
                    }
                    .width(tw),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    fmtTime(preview), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xCC111111)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0x66FFFFFF)))
        Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(C.gold))
        Box(
            Modifier
                .offset(x = (w * fraction) - 7.dp)
                .size(14.dp)
                .clip(CircleShape)
                .background(C.gold)
        )
    }
}

/** Lista lateral de canales (flecha izquierda en directo). */
@Composable
private fun ChannelListPanel(ctl: PlayerCtl, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (ctl.index - 4).coerceAtLeast(0))
    Box(
        Modifier
            .fillMaxHeight()
            .width(400.dp)
            .background(Color(0xF00A0A0A))
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                    onClose()
                    true
                } else {
                    false
                }
            }
    ) {
        LazyColumn(state = state, contentPadding = PaddingValues(vertical = 20.dp)) {
            itemsIndexed(ctl.items, key = { i, _ -> i }) { i, p ->
                var focused by remember { mutableStateOf(false) }
                val fr = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    if (i == ctl.index) {
                        delay(60)
                        runCatching { fr.requestFocus() }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .focusRequester(fr)
                        .onFocusChanged { focused = it.isFocused }
                        .tvClick { onPick(i) }
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when {
                                focused -> Color.White
                                i == ctl.index -> C.sel
                                else -> Color.Transparent
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val n = p.channel?.num?.takeIf { it > 0 } ?: (i + 1)
                    Text("$n", color = if (focused) Color.Black else C.gold, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
                    val logo = remember(p.key, Logos.version) { Logos.url(p.title, p.image) }
                    Box(
                        Modifier.width(44.dp).height(28.dp).clip(RoundedCornerShape(4.dp))
                            .background(
                                when {
                                    LogoTone.wantsWhite(logo) -> Color.White
                                    LogoTone.isDark(logo) -> LogoTone.light
                                    else -> Color.Transparent
                                }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (logo != null) {
                            ChannelLogoImage(logo, Modifier.fillMaxSize().padding(2.dp), fallback = p.image)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        p.title,
                        color = if (focused) Color.Black else Color.White,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val ASPECTS = listOf("fit", "zoom", "fill", "16:9", "4:3")

fun nextAspect(a: String): String = ASPECTS[(ASPECTS.indexOf(a) + 1) % ASPECTS.size]

fun aspectLabel(a: String): String = when (a) {
    "zoom" -> "Rellenar (zoom)"
    "fill" -> "Estirar"
    "16:9" -> "16:9"
    "4:3" -> "4:3"
    else -> "Ajustar"
}

/** Aplica el tamaño, color y fondo de subtítulos elegidos en Ajustes. */
@OptIn(UnstableApi::class)
fun styleSubtitles(v: SubtitleView?) {
    v ?: return
    val p = Repo.prefs
    val fg = if (p.subColor == "yellow") android.graphics.Color.rgb(255, 230, 80) else android.graphics.Color.WHITE
    // Letra limpia y sombra suave, como en Netflix.
    val font = runCatching { androidx.core.content.res.ResourcesCompat.getFont(v.context, com.suso.miiptv.R.font.manrope_semibold) }.getOrNull()
    val style = if (p.subBg == "band") {
        CaptionStyleCompat(fg, android.graphics.Color.argb(170, 0, 0, 0), android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.BLACK, font)
    } else {
        CaptionStyleCompat(fg, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, android.graphics.Color.argb(230, 0, 0, 0), font)
    }
    val scale = when (p.subSize) {
        "s" -> 0.85f
        "l" -> 1.25f
        "xl" -> 1.5f
        else -> 1f
    }
    v.setApplyEmbeddedStyles(false)
    v.setApplyEmbeddedFontSizes(false)
    v.setStyle(style)
    v.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
    // Lo más abajo posible (Netflix deja un margen pequeño).
    v.setBottomPaddingFraction(0.045f)
}

/**
 * Quita la posición que traen algunos subtítulos (que los subía a media pantalla) para que salgan siempre abajo.
 * Los subtítulos de imagen (PGS) se dejan como vienen.
 */
fun bottomCues(cues: List<androidx.media3.common.text.Cue>): List<androidx.media3.common.text.Cue> = cues.map { c ->
    if (c.bitmap != null || c.text == null) c
    else c.buildUpon()
        .setLine(androidx.media3.common.text.Cue.DIMEN_UNSET, androidx.media3.common.text.Cue.TYPE_UNSET)
        .setLineAnchor(androidx.media3.common.text.Cue.TYPE_UNSET)
        .setPosition(androidx.media3.common.text.Cue.DIMEN_UNSET)
        .setPositionAnchor(androidx.media3.common.text.Cue.TYPE_UNSET)
        .setSize(androidx.media3.common.text.Cue.DIMEN_UNSET)
        .setTextAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
        .setVerticalType(androidx.media3.common.text.Cue.TYPE_UNSET)
        .build()
}

/** Panel lateral derecho: audio, subtítulos, imagen, velocidad, VPN y reproductor externo. */
@Composable
private fun OptionsPanel(
    ctl: PlayerCtl,
    modifier: Modifier,
    aspect: String,
    onAspect: () -> Unit,
    onVpn: () -> Unit,
    onStartOver: (com.suso.miiptv.data.Item, com.suso.miiptv.data.EpgEntry) -> Unit,
    onExternal: () -> Unit,
    onClose: () -> Unit,
) {
    val v = ctl.tracksVersion
    val audio = remember(v, ctl.index) { ctl.trackOptions(MC.TRACK_TYPE_AUDIO) }
    val subs = remember(v, ctl.index) { ctl.trackOptions(MC.TRACK_TYPE_TEXT) }
    val subsOff = remember(v, ctl.index) { ctl.subtitlesOff() }
    val focusIdx = audio.indexOfFirst { it.selected }.coerceAtLeast(0)
    Column(
        modifier
            .fillMaxHeight()
            .width(380.dp)
            .background(Color(0xF00A0A0A))
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                    onClose()
                    true
                } else {
                    false
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 22.dp),
    ) {
        Text("Audio", color = C.gold, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
        if (audio.isEmpty()) {
            Text("No hay pistas de audio", color = C.text2, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
        }
        audio.forEachIndexed { i, o ->
            TrackRow(o.label, o.selected, requestFocus = i == focusIdx) {
                ctl.selectTrack(MC.TRACK_TYPE_AUDIO, o)
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("Subtítulos", color = C.gold, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
        TrackRow("Desactivados", subsOff, requestFocus = audio.isEmpty()) {
            ctl.selectTrack(MC.TRACK_TYPE_TEXT, null)
        }
        if (subs.isEmpty()) {
            Text("Este contenido no trae subtítulos", color = C.text2, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp, top = 6.dp))
        }
        subs.forEach { o ->
            TrackRow(o.label, o.selected && !subsOff, requestFocus = false) {
                ctl.selectTrack(MC.TRACK_TYPE_TEXT, o)
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("Imagen y reproducción", color = C.gold, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
        TrackRow("Formato de imagen: ${aspectLabel(aspect)}", false, requestFocus = false) { onAspect() }
        if (!ctl.current.live) {
            TrackRow("Velocidad: ${speedLabel(ctl.speed)}", false, requestFocus = false) { ctl.nextSpeed() }
        }
        // Canal que guarda lo emitido: empezar el programa actual desde el principio (p. ej. un partido ya empezado).
        val ch = ctl.current.channel
        val nowMs = System.currentTimeMillis()
        val cur = ctl.epg.firstOrNull { it.start <= nowMs && it.end > nowMs }
        if (ctl.current.live && ch != null && ch.archive > 0 && cur != null) {
            TrackRow("Ver desde el principio: ${cur.title}", false, requestFocus = false) { onStartOver(ch, cur) }
        }
        val vs = VpnManager.state
        val vpnText = when (vs) {
            VpnManager.State.ON -> "VPN: activada" + (VpnManager.info?.let { " · $it" } ?: "")
            VpnManager.State.CONNECTING -> "VPN: conectando…"
            VpnManager.State.ERROR -> "VPN: error · " + (VpnManager.message ?: "")
            VpnManager.State.OFF -> "VPN: desactivada"
        }
        TrackRow(vpnText, vs == VpnManager.State.ON, requestFocus = false) { onVpn() }
        TrackRow("Abrir en otro reproductor", false, requestFocus = false) { onExternal() }
        Spacer(Modifier.height(18.dp))
        Text("◀ para cerrar", color = C.text2, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

private fun speedLabel(f: Float): String = when (f) {
    0.5f -> "0,5x"
    0.75f -> "0,75x"
    1.25f -> "1,25x"
    1.5f -> "1,5x"
    2f -> "2x"
    else -> "normal"
}

@Composable
private fun TrackRow(label: String, selected: Boolean, requestFocus: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (requestFocus) {
            delay(60)
            runCatching { fr.requestFocus() }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .focusRequester(fr)
            .onFocusChanged { focused = it.isFocused }
            .tvClick(onClick = onClick)
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected) "✓" else "",
            color = if (focused) Color.Black else C.gold,
            fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(26.dp),
        )
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Etiquetas pequeñas junto al título (FHD, 5.1, DOLBY...): borde fino y texto blanco suave, legibles sobre cualquier imagen. */
@Composable
private fun StreamBadges(badges: List<String>) {
    if (badges.isEmpty()) return
    Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        badges.forEach { b ->
            val sound = b == "DOLBY" || b == "DD+" || b == "ATMOS" || b == "DTS"
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    b, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp,
                    fontWeight = if (sound) FontWeight.ExtraBold else FontWeight.Bold,
                    letterSpacing = if (sound) 1.sp else 0.5.sp,
                )
            }
        }
    }
}


/** Teclado numérico en pantalla para ir a un canal por su número (mandos sin números, como el de la Mi Box). */
@Composable
private fun NumberPad(
    typed: String,
    hasPrevious: Boolean,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    onPrevious: () -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF00B140D))
            .border(1.dp, C.gold.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Ir al canal", color = C.text2, fontSize = 14.sp)
        Text(
            typed.ifEmpty { "—" }, color = C.gold, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("↩", "0", "⌫"))
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                row.forEachIndexed { c, k ->
                    PadKey(
                        k, "pad_$k",
                        enabled = k != "↩" || hasPrevious,
                        modifier = if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier,
                    ) {
                        when (k) {
                            "↩" -> onPrevious()
                            "⌫" -> onDelete()
                            else -> onDigit(k.toInt())
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PadKey("Ir", "pad_go", wide = true, enabled = typed.isNotEmpty()) { onGo() }
        Spacer(Modifier.height(8.dp))
        Text("↩ canal anterior · ⌫ borrar · Atrás cerrar", color = Color(0xFF7A7A7A), fontSize = 12.sp)
    }
}

@Composable
private fun PadKey(label: String, key: String, modifier: Modifier = Modifier, wide: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // Solo cuenta una pulsación que empieza en esta tecla (así no se «pulsa» al soltar el OK mantenido que abrió el teclado).
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .width(if (wide) 222.dp else 64.dp)
            .height(54.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) pressed = false
            }
            .focusProperties { canFocus = enabled }
            .focusable()
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                when {
                    !ok -> false
                    e.type == KeyEventType.KeyDown -> {
                        if (e.nativeKeyEvent.repeatCount == 0) pressed = true
                        true
                    }
                    e.type == KeyEventType.KeyUp -> {
                        if (pressed) onClick()
                        pressed = false
                        true
                    }
                    else -> true
                }
            }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) C.gold else Color(0xFF1C2A1F)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            color = when {
                focused -> Color.Black
                enabled -> Color.White
                else -> Color(0xFF555555)
            },
        )
    }
}
