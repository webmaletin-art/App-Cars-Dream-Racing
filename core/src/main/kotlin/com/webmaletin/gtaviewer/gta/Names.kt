package com.webmaletin.gtaviewer.gta

/** Utilidades de normalización/formateo de nombres de vehículos. */
object Names {
    /** Minúsculas y solo alfanuméricos: "Audi_A3" == "audi-a3" == "AudiA3". */
    fun normalize(s: String): String = buildString { for (c in s) if (c.isLetterOrDigit()) append(c.lowercaseChar()) }

    private val splitRegex = Regex("[\\s_\\-.+]+")

    /** "audi_a3", "audi-a3", "AudiA3" -> "Audi A3". Los nombres de una sola palabra solo se capitalizan. */
    fun pretty(raw: String): String {
        val withSpaces = buildString {
            val s = raw.trim()
            for (i in s.indices) {
                val c = s[i]
                if (i > 0 && c.isUpperCase() && s[i - 1].isLowerCase()) append(' ')
                append(c)
            }
        }
        var tokens = withSpaces.split(splitRegex).filter { it.isNotEmpty() }
        if (tokens.size > 1) {
            // IDs numéricos innecesarios: prefijos de 3+ dígitos (p. ej. "401_audi") y sufijos de 5+ dígitos.
            tokens = tokens.filterIndexed { i, t ->
                val numeric = t.all { it.isDigit() }
                !(numeric && ((i == 0 && t.length >= 3) || (i == tokens.lastIndex && t.length >= 5)))
            }.ifEmpty { tokens }
        }
        return tokens.joinToString(" ") { token ->
            when {
                token.any { it.isDigit() } && token.length <= 6 -> token.uppercase()
                token.length in 4..Int.MAX_VALUE && token.all { it.isUpperCase() || !it.isLetter() } && token.any { it.isLetter() } ->
                    token.lowercase().replaceFirstChar { it.uppercase() }
                token.length <= 3 && token.all { it.isUpperCase() } -> token
                else -> token.replaceFirstChar { it.uppercase() }
            }
        }
    }

    val GENERIC_FOLDERS = setOf(
        "tunable", "tuning", "tune", "cars", "car", "vehicles", "vehicle", "models", "model", "dff", "txd", "data",
        "files", "mod", "mods", "gta", "gtasa", "sa", "stock", "original", "default", "new", "old", "backup", "bak",
        "parts", "textures", "texture", "lod", "low", "high", "main", "source", "sources", "src", "resources",
        "assets", "content", "extras", "extra", "modloader", "cleo", "image", "img", "gta3", "gta_sa",
    )

    fun isGenericFolder(folder: String): Boolean {
        val n = normalize(folder)
        return n.isEmpty() || n in GENERIC_FOLDERS || (n.endsWith("cars") && n.length > 4) || n.endsWith("vehicles")
    }

    val GENERIC_FILE_NAMES = setOf("model", "vehicle", "car", "dff", "default", "untitled", "new", "mesh", "object", "test")

    val PART_KEYWORDS_LONG = listOf(
        "wheels", "spoiler", "bumper", "engine", "interior", "exhaust", "suspension", "steering", "caliper",
        "bonnet", "tuning", "trunk", "mirror", "brake", "headlight", "taillight",
    )
    val PART_KEYWORDS_SHORT = setOf(
        "parts", "part", "tune", "wheel", "rim", "rims", "door", "doors", "hood", "boot", "seat", "seats",
        "spring", "springs", "disc", "discs", "tire", "tires", "tyre", "tyres", "lights", "bump", "wing", "wings",
    )

    /** Tokens "palabra" de un nombre: separa por no alfanumérico y por camelCase. */
    fun tokens(raw: String): List<String> {
        val spaced = buildString {
            for (i in raw.indices) {
                if (i > 0 && raw[i].isUpperCase() && raw[i - 1].isLowerCase()) append(' ')
                append(raw[i])
            }
        }
        return spaced.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.map { it.lowercase() }
    }

    /** Devuelve la palabra clave de pieza que contiene el nombre, o null. */
    fun partKeyword(raw: String): String? {
        val toks = tokens(raw)
        toks.firstOrNull { it in PART_KEYWORDS_SHORT }?.let { return it }
        for (t in toks) for (k in PART_KEYWORDS_LONG) if (t.contains(k)) return k
        return null
    }
}

/** Distancia entre carpetas (árbol): saltos hacia arriba + hacia abajo. */
object Paths {
    fun segments(dir: String): List<String> = if (dir.isEmpty()) emptyList() else dir.lowercase().split('/')
    fun distance(a: String, b: String): Int {
        val sa = segments(a); val sb = segments(b)
        var c = 0
        while (c < sa.size && c < sb.size && sa[c] == sb[c]) c++
        return (sa.size - c) + (sb.size - c)
    }
    fun isUnder(child: String, parent: String): Boolean =
        parent.isEmpty() || child.equals(parent, true) || child.lowercase().startsWith(parent.lowercase() + "/")
}
