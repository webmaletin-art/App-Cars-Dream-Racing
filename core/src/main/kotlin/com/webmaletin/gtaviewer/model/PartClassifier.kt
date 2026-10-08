package com.webmaletin.gtaviewer.model

/** Clasificación de piezas a partir de los nombres de frame de GTA SA (chassis, wheel_lf_dummy, door_rf_dummy, ...). */
object PartClassifier {
    class Result(val category: PartCategory, val slot: WheelSlot?, val recognized: Boolean, val variant: String?)

    private val suffixes = listOf("_ok", "_dam", "_vlo", "_lod", "_dummy")

    fun variantOf(name: String): String? {
        val n = name.lowercase()
        return when {
            n.endsWith("_dam") -> "variante dañada"
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

    fun classify(frameName: String?): Result {
        if (frameName.isNullOrBlank()) return Result(PartCategory.OTHER, null, false, null)
        val n = base(frameName)
        val variant = variantOf(frameName)
        fun r(c: PartCategory, slot: WheelSlot? = null) = Result(c, slot, true, variant)
        return when {
            n.startsWith("chassis") -> r(PartCategory.BODY)
            n.startsWith("wheel") || tokens(n).any { it in WHEEL_TOKENS } -> r(PartCategory.WHEEL, wheelSlot(n))
            "door" in n -> r(PartCategory.DOOR)
            "bonnet" in n || "hood" in n -> r(PartCategory.HOOD)
            "boot" in n || "trunk" in n -> r(PartCategory.TRUNK)
            "bump" in n -> r(PartCategory.BUMPER)
            "wing" in n || "fender" in n -> r(PartCategory.FENDER)
            "windscreen" in n || "windshield" in n || "window" in n || "glass" in n -> r(PartCategory.WINDOW)
            "exhaust" in n -> r(PartCategory.EXHAUST)
            "engine" in n || "motor" in n -> r(PartCategory.ENGINE)
            "spoiler" in n -> r(PartCategory.SPOILER)
            "mirror" in n -> r(PartCategory.MIRROR)
            "interior" in n || "seat" in n || "steer" in n || "dash" in n -> r(PartCategory.INTERIOR)
            "light" in n || "lamp" in n || "indicator" in n -> r(PartCategory.LIGHT)
            "brake" in n || "caliper" in n || tokens(n).any { it == "disc" || it == "discs" } -> r(PartCategory.BRAKES)
            "suspension" in n || "spring" in n || "axle" in n || "shock" in n -> r(PartCategory.SUSPENSION)
            else -> Result(PartCategory.OTHER, null, true, variant)
        }
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
                tk.any { it in setOf("interior", "seat", "dash", "dashboard") } -> PartCategory.INTERIOR
                tk.any { it in setOf("brake", "disc", "caliper") } -> PartCategory.BRAKES
                else -> null
            }
        }
        return cats.firstOrNull()?.takeIf { c -> cats.all { it == c } }
    }
}
