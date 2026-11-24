package com.suso.miiptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suso.miiptv.data.Category
import com.suso.miiptv.data.Device
import com.suso.miiptv.data.Kind
import com.suso.miiptv.data.Repo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ajustes → Contenido → Ordenar categorías: poner las categorías de canales, películas y series en el orden que quieras.
 * Con mando: OK coge una categoría, ▲▼ la mueven y OK (o Atrás) la sueltan. Con el dedo: botones de subir, bajar y al principio.
 * Solo salen las visibles: las ocultas no se enseñan aquí (pueden estar protegidas con PIN).
 */
@Composable
fun CatOrderScreen() {
    val ctx = LocalContext.current
    val touch = Device.touch
    var kind by remember { mutableStateOf(Kind.LIVE) }
    var cats by remember(kind) { mutableStateOf<List<Category>?>(null) }
    LaunchedEffect(kind, Repo.dataVersion) {
        cats = runCatching { Repo.categories(kind) }.getOrDefault(emptyList())
    }
    // Categoría cogida con el mando (su id).
    var moving by remember(kind) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstFr.requestFocus() }
    }
    androidx.activity.compose.BackHandler(enabled = moving != null) { moving = null }

    fun save(l: List<Category>) {
        cats = l
        Repo.setCategoryOrder(kind, l.map { it.id })
    }

    fun move(id: String, to: (Int, Int) -> Int) {
        val l = cats?.toMutableList() ?: return
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = to(i, l.size).coerceIn(0, l.size - 1)
        if (j == i) return
        l.add(j, l.removeAt(i))
        save(l)
        // Que la categoría movida siga a la vista.
        scope.launch {
            val vis = listState.layoutInfo.visibleItemsInfo
            if (vis.none { it.index == j } || vis.first().index == j || vis.last().index == j) {
                listState.animateScrollToItem((j - 2).coerceAtLeast(0))
            }
        }
    }

    Column(Modifier.fillMaxSize().background(C.bg).padding(horizontal = if (touch) 16.dp else 48.dp, vertical = 24.dp)) {
        Text("Ordenar categorías", color = Color.White, fontSize = Sz.title, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (touch) "Usa las flechas de cada categoría para subirla o bajarla, o ⤒ para ponerla la primera."
            else "Pulsa OK en una categoría para cogerla, muévela con ▲ ▼ y pulsa OK otra vez para soltarla.",
            color = C.text2, fontSize = Sz.body,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TabChip("TV en directo", kind == Kind.LIVE, "co_live", focusRequester = firstFr) { kind = Kind.LIVE }
            TabChip("Películas", kind == Kind.MOVIE, "co_mov") { kind = Kind.MOVIE }
            TabChip("Series", kind == Kind.SERIES, "co_ser") { kind = Kind.SERIES }
        }
        Spacer(Modifier.height(12.dp))
        TvButton("Volver al orden de la lista", "co_reset") {
            Repo.setCategoryOrder(kind, emptyList())
            moving = null
            cats = null
            scope.launch { cats = runCatching { Repo.categories(kind) }.getOrDefault(emptyList()) }
            Actions.toast(ctx, "Orden de la lista recuperado")
        }
        Spacer(Modifier.height(12.dp))
        val l = cats
        when {
            l == null -> Loading()
            l.isEmpty() -> EmptyText("No hay categorías")
            else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
                itemsIndexed(l, key = { _, c -> c.id }) { i, c ->
                    OrderRow(
                        pos = i + 1,
                        name = catLabel(c.name, kind),
                        focusKey = "co_${kind.name}_${c.id}",
                        moving = moving == c.id,
                        touch = touch,
                        first = i == 0,
                        last = i == l.lastIndex,
                        onToggle = {
                            moving = if (moving == c.id) null else c.id
                        },
                        onUp = { move(c.id) { k, _ -> k - 1 } },
                        onDown = { move(c.id) { k, _ -> k + 1 } },
                        onTop = { move(c.id) { _, _ -> 0 } },
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderRow(
    pos: Int,
    name: String,
    focusKey: String,
    moving: Boolean,
    touch: Boolean,
    first: Boolean,
    last: Boolean,
    onToggle: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onTop: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(
                if (touch) Modifier
                else Modifier
                    .memFocus(focusKey) { focused = it }
                    // Con la categoría cogida, ▲▼ la mueven en vez de mover el cursor.
                    .onPreviewKeyEvent { e ->
                        if (!moving || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionUp -> { onUp(); true }
                            Key.DirectionDown -> { onDown(); true }
                            Key.DirectionLeft, Key.DirectionRight -> true
                            else -> false
                        }
                    }
                    .tvClick(onClick = onToggle),
            )
            .clip(shape)
            .background(
                when {
                    moving -> C.gold
                    focused -> Color.White
                    else -> Color.Transparent
                },
            )
            .then(if (touch) Modifier.border(1.dp, C.divider, shape) else Modifier)
            .padding(horizontal = 12.dp, vertical = if (touch) 4.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (moving || focused) Color.Black else Color.White
        Text("$pos", color = if (moving || focused) Color.Black else C.textFaint, fontSize = Sz.small, modifier = Modifier.width(34.dp))
        Text(name, color = ink, fontSize = Sz.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (moving) Text("▲ ▼  ·  OK para soltar", color = Color.Black, fontSize = Sz.small, fontWeight = FontWeight.SemiBold)
        if (touch) {
            OrderButton("⤒", "Poner la primera", enabled = !first, onTop)
            OrderButton("▲", "Subir", enabled = !first, onUp)
            OrderButton("▼", "Bajar", enabled = !last, onDown)
        }
    }
}

@Composable
private fun OrderButton(symbol: String, desc: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .semantics { contentDescription = desc }
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = if (enabled) C.gold else C.textFaint.copy(alpha = 0.4f), fontSize = 18.sp, textAlign = TextAlign.Center)
    }
}
