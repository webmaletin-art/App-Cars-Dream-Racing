package com.webmaletin.gtaviewer.model

/** Clasificación de piezas a partir de los nombres de frame de GTA SA (chassis, wheel_lf_dummy, door_rf_dummy, ...). */
object PartClassifier {
    class Result(val category: PartCategory, val slot: WheelSlot?, val recognized: Boolean, val variant: String?)

    const val DAMAGE_NOTE = "variante dañada"

    private val suffixes = listOf("_ok", "_dam", "_dmg", "_vlo", "_lod", "_dummy", "_shiny")

    fun variantOf(name: String): String? {
        val n = name.lowercase()
        return when {
            n.endsWith("_dam") -> DAMAGE_NOTE
            n.endsWith("_vlo") || n.endsWith("_lod") -> "variante LOD (baja)"
            else -> null
        }
    }

    fun base(name: String): String {
        var n = name.lowercase()
        var changed = true
        while (changed) { changed = false; for (s in suffixes) if (n.endsWith(s)) { n = n.removeSuffix(s); changed = true } }
        return n
    }

    fun wheelSlot(n: String): WheelSlot? = when {
        Regex("(^|_)lf($|_)").containsMatchIn(n) -> WheelSlot.FL
        Regex("(^|_)rf($|_)").containsMatchIn(n) -> WheelSlot.FR
        Regex("(^|_)(lb|lr)($|_)").containsMatchIn(n) -> WheelSlot.RL
        Regex("(^|_)(rb|rr)($|_)").containsMatchIn(n) -> WheelSlot.RR
        Regex("(^|_)lm($|_)").containsMatchIn(n) -> WheelSlot.ML
        Regex("(^|_)rm($|_)").containsMatchIn(n) -> WheelSlot.MR
        else -> null
    }

    /** Categoría deducida de un nombre (frame o archivo). Convenciones de SA: fbmp/rbmp, exh, spl, rf, wg_l/r, bnt… */
    fun categoryOfName(raw: String): PartCategory? {
        val n = base(raw)
        val tk = tokens(n)
        fun has(vararg k: String) = tk.any { it in k }
        return when {
            n.startsWith("chassis") -> PartCategory.BODY
            n.startsWith("wheel") || tk.any { it in WHEEL_TOKENS } -> PartCategory.WHEEL
            "door" in n -> PartCategory.DOOR
            "bonnet" in n || "hood" in n || has("bnt", "bnts", "bonnet") -> PartCategory.HOOD
            "boot" in n || "trunk" in n || "tailgate" in n -> PartCategory.TRUNK
            "bump" in n || has("fbmp", "rbmp", "bumper", "bbmp") -> PartCategory.BUMPER
            "wing" in n && "spoiler" !in n || "fender" in n || has("fndr") -> PartCategory.FENDER
            "windscreen" in n || "windshield" in n || "window" in n || "glass" in n -> PartCategory.WINDOW
            "exhaust" in n || has("exh", "muffler") -> PartCategory.EXHAUST
            "engine" in n || "motor" in n -> PartCategory.ENGINE
            "spoiler" in n || has("spl") -> PartCategory.SPOILER
            "mirror" in n -> PartCategory.MIRROR
            "grille" in n || "grill" in n -> PartCategory.GRILLE
            "intake" in n || "scoop" in n || has("nto") -> PartCategory.INTAKE
            "skirt" in n || has("sskirt", "sill") || (tk.firstOrNull() == "wg" && tk.getOrNull(1) in listOf("l", "r")) -> PartCategory.SKIRT
            "roof" in n || tk.firstOrNull() == "rf" -> PartCategory.ROOF
            "seat" in n -> PartCategory.SEAT
            "steer" in n -> PartCategory.STEERING
            "interior" in n || "dash" in n -> PartCategory.INTERIOR
            "light" in n || "lamp" in n || "indicator" in n -> PartCategory.LIGHT
            "brake" in n || "caliper" in n || has("disc", "discs") -> PartCategory.BRAKES
            "suspension" in n || "spring" in n || "axle" in n || "shock" in n -> PartCategory.SUSPENSION
            else -> null
        }
    }

    fun classify(frameName: String?): Result {
        if (frameName.isNullOrBlank()) return Result(PartCategory.OTHER, null, false, null)
        val n = base(frameName)
        val variant = variantOf(frameName)
        val cat = categoryOfName(frameName)
        val slot = if (cat == PartCategory.WHEEL) wheelSlot(n) else null
        return Result(cat ?: PartCategory.OTHER, slot, true, variant)
    }

    private val WHEEL_TOKENS = setOf("wheel", "wheels", "tire", "tires", "tyre", "tyres", "rim", "rims")

    private fun tokens(n: String) = n.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /**
     * Propuesta por nombres de textura cuando el frame no dice nada: solo si TODAS las texturas del mesh apuntan a la
     * misma categoría. Es una sugerencia (el usuario puede reclasificar).
     */
    fun classifyByTextures(textureNames: List<String>): PartCategory? {
        val names = textureNames.map { it.lowercase() }.filter { it.isNotBlank() }
        if (names.isEmpty()) return null
        val cats = names.map { t ->
            val tk = tokens(t)
            when {
                tk.any { it in WHEEL_TOKENS } -> PartCategory.WHEEL
                tk.any { it in setOf("glass", "window", "windscreen", "windshield") } -> PartCategory.WINDOW
                tk.any { it.contains("light") || it.contains("lamp") } -> PartCategory.LIGHT
                tk.any { it in setOf("exhaust", "muffler") } -> PartCategory.EXHAUST
                tk.any { it in setOf("engine", "motor") } -> PartCategory.ENGINE
                tk.any { it in setOf("seat", "seats") } -> PartCategory.SEAT
                tk.any { it in setOf("interior", "dash", "dashboard") } -> PartCategory.INTERIOR
                tk.any { it in setOf("brake", "brakes", "disc", "caliper") } -> PartCategory.BRAKES
                else -> null
            }
        }
        return cats.firstOrNull()?.takeIf { c -> cats.all { it == c } }
    }
}
