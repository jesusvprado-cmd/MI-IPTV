package com.suso.miiptv.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suso.miiptv.data.Kind
import com.suso.miiptv.data.Repo
import com.suso.miiptv.data.SearchResult
import kotlinx.coroutines.delay

@Composable
fun SearchScreen() {
    val kb = LocalSoftwareKeyboardController.current
    val dv = Repo.dataVersion
    var q by remember { mutableStateOf(UiState.searchQuery) }
    var res by remember { mutableStateOf<SearchResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(q, dv) {
        UiState.searchQuery = q
        if (q.trim().length < 2) {
            res = null
            loading = false
            return@LaunchedEffect
        }
        delay(450)
        loading = true
        res = try {
            Repo.search(q)
        } catch (e: Exception) {
            null
        }
        loading = false
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 22.dp, bottom = 40.dp)) {
        item {
          androidx.compose.foundation.layout.Row(
              Modifier.padding(end = 40.dp),
              verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
          ) {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                singleLine = true,
                placeholder = { Text("Buscar canales, películas o series") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { kb?.hide() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = C.gold,
                    unfocusedBorderColor = Color(0xFF555555),
                    cursorColor = C.gold,
                ),
                modifier = Modifier
                    .padding(start = 40.dp)
                    .weight(1f)
                    .memFocus("search_field"),
            )
          }
            Spacer(Modifier.height(20.dp))
        }
        val r = res
        when {
            loading -> item {
                Text(
                    "Buscando… (la primera búsqueda tarda más porque se carga todo el catálogo)",
                    color = C.text2, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 40.dp),
                )
            }
            r == null -> item {
                Text(
                    "Escribe al menos 2 letras.",
                    color = C.text2, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 40.dp),
                )
            }
            r.live.isEmpty() && r.movies.isEmpty() && r.series.isEmpty() -> item {
                Text("Sin resultados", color = C.text2, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 40.dp))
            }
            else -> {
                if (r.live.isNotEmpty()) item { HomeRowView(HomeRow("search_live", "Canales (${r.live.size})", Kind.LIVE, r.live)) }
                if (r.movies.isNotEmpty()) item { HomeRowView(HomeRow("search_movies", "Películas (${r.movies.size})", Kind.MOVIE, r.movies)) }
                if (r.series.isNotEmpty()) item { HomeRowView(HomeRow("search_series", "Series (${r.series.size})", Kind.SERIES, r.series)) }
            }
        }
    }
}
