package com.webmaletin.gtaviewer.model

/** Nombres legibles PROPUESTOS a partir de los nombres de frame de GTA SA. El nombre interno siempre se conserva aparte. */
object PartNaming {
    private fun side(n: String): String? = when {
        Regex("(^|_)lf($|_)").containsMatchIn(n) -> "delantera izquierda"
        Regex("(^|_)rf($|_)").containsMatchIn(n) -> "delantera derecha"
        Regex("(^|_)(lb|lr)($|_)").containsMatchIn(n) -> "trasera izquierda"
        Regex("(^|_)(rb|rr)($|_)").containsMatchIn(n) -> "trasera derecha"
        else -> null
    }

    fun friendly(part: MeshPart): String {
        val raw = part.name
        val frame = part.frameName ?: return raw
        val n = PartClassifier.base(frame)
        val v = PartClassifier.variantOf(frame)
        val suffix = v?.let { " ($it)" } ?: ""
        val base: String? = when {
            part.wheelSlot != null -> "Rueda ${part.wheelSlot.short}"
            n == "wheel" -> "Rueda"
            n.startsWith("chassis") -> "Carrocería"
            "door" in n -> "Puerta " + (side(n) ?: "")
            "bonnet" in n || "hood" in n -> "Capó"
            "boot" in n || "trunk" in n -> "Baúl"
            "bump_front" in n || "bumper_front" in n -> "Paragolpes delantero"
            "bump_rear" in n || "bumper_rear" in n -> "Paragolpes trasero"
            "bump" in n -> "Paragolpes"
            "wing" in n || "fender" in n -> "Guardabarros " + (side(n) ?: "")
            "windscreen" in n || "windshield" in n -> "Parabrisas"
            "exhaust" in n -> "Escape"
            "spoiler" in n -> "Spoiler"
            else -> null
        }
        // Si el nombre ya contiene un sufijo de la extra "Rueda FL" (nombre único generado), se respeta.
        return if (base == null) raw else base.trim() + suffix
    }
}
