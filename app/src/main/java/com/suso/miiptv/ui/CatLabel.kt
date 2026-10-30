package com.suso.miiptv.ui

/**
 * Nombre de categoría para mostrar: las del proveedor suelen venir en mayúsculas («ESTRENOS 2026»);
 * se pasan a mayúscula inicial («Estrenos 2026») para que queden igual que las de la app («Últimos añadidos»).
 * Las siglas y marcas (4K, HD, DAZN, F1, TV, LaLiga...) se respetan. Si el nombre ya viene en minúsculas
 * y mayúsculas, se deja tal cual.
 */
fun catLabel(name: String, kind: com.suso.miiptv.data.Kind? = null): String {
    val label = caseLabel(name)
    // Dentro de Series sobra «Series» delante, y dentro de Películas «Cine» y «Saga» («Series Top» → «Top»).
    val drop = when (kind) {
        com.suso.miiptv.data.Kind.SERIES -> setOf("series", "serie")
        com.suso.miiptv.data.Kind.MOVIE -> setOf("cine", "saga", "sagas")
        else -> emptySet()
    }
    if (drop.isEmpty()) return label
    val words = label.split(' ')
    val firstWord = words.first().trim { !it.isLetterOrDigit() }.lowercase()
    if (firstWord !in drop || words.size < 2) return label
    val rest = words.drop(1).joinToString(" ").trimStart(' ', '-', '·', ':', '|')
    if (rest.isBlank()) return label
    return rest.replaceFirstChar { it.uppercase() }
}

private fun caseLabel(name: String): String {
    val letters = name.filter { it.isLetter() }
    if (letters.isEmpty()) return name
    val upper = letters.count { it.isUpperCase() }
    if (upper < letters.length * 0.7) return name
    var first = true
    return name.split(' ').joinToString(" ") { w ->
        val r = word(w, first)
        if (w.any { it.isLetterOrDigit() }) first = false
        r
    }
}

private val SPECIAL = mapOf(
    "LALIGA" to "LaLiga", "MOTOGP" to "MotoGP", "MOVISTAR+" to "Movistar+", "M+" to "M+", "ESPN" to "ESPN",
    "EA" to "EA", "BARTV" to "BarTV", "PLUS+" to "Plus+", "IPTV" to "IPTV", "VIP" to "VIP", "PPV" to "PPV",
)
private val ACRONYMS = setOf(
    "TV", "HD", "FHD", "UHD", "SD", "4K", "8K", "HDR", "DAZN", "F1", "F2", "NBA", "NFL", "NHL", "MLB", "UFC", "WWE", "RFEF",
    "BBC", "HBO", "AXN", "TCM", "AMC", "MTV", "USA", "UK", "EEUU", "VOD", "VO", "VOS", "VOSE", "3D", "24H", "DTS", "TNT",
    "SAT", "ATP", "WTA", "PGA", "LPGA", "UEFA", "FIFA", "ACB", "LEB", "ASO", "NCAA", "MMA", "BEIN", "TVE", "TV3", "ETB",
    "CRTVG", "TVG", "TPA", "IB3", "CMM", "RTVE", "XXX", "ES", "LATAM",
)
private val SMALL = setOf("de", "del", "la", "las", "el", "los", "y", "e", "o", "u", "en", "a", "al", "por", "con", "para", "sin", "vs")

private fun word(w: String, first: Boolean): String {
    if (w.isEmpty()) return w
    // Signos alrededor de la palabra («(ES)», «M.»): se conservan.
    val start = w.indexOfFirst { it.isLetterOrDigit() }
    if (start < 0) return w
    val end = w.indexOfLast { it.isLetterOrDigit() }
    val core = w.substring(start, end + 1)
    val pre = w.substring(0, start)
    val post = w.substring(end + 1)
    val up = core.uppercase()
    val out = when {
        SPECIAL.containsKey(up + post) -> return pre + SPECIAL.getValue(up + post)
        SPECIAL.containsKey(up) -> SPECIAL.getValue(up)
        up in ACRONYMS -> up
        core.any { it.isDigit() } -> up // 4K, 2026, F1, T2...
        core.length == 1 && post.startsWith(".") -> up // «M.»
        !first && core.lowercase() in SMALL -> core.lowercase()
        else -> core.lowercase().replaceFirstChar { it.uppercase() }
    }
    return pre + out + post
}
