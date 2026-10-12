package com.webmaletin.gtaviewer.classify

import com.webmaletin.gtaviewer.gta.Names
import com.webmaletin.gtaviewer.library.Correction
import com.webmaletin.gtaviewer.library.TypeLabel
import com.webmaletin.gtaviewer.model.Mat4
import com.webmaletin.gtaviewer.model.PartCategory
import com.webmaletin.gtaviewer.model.VehicleModel
import kotlin.math.abs
import kotlin.math.sqrt

/** Etiquetas de tipo de vehículo (múltiples por vehículo: un deportivo puede ser moderno y coupé a la vez). */
object VehicleTypes {
    const val CLASSIC = "Clásico"; const val MODERN = "Moderno"; const val SPORTS = "Deportivo"; const val SUPERCAR = "Superdeportivo"
    const val COUPE = "Coupé"; const val SEDAN = "Sedán"; const val HATCHBACK = "Hatchback"; const val MUSCLE = "Muscle car"
    const val COMPACT = "Compacto"; const val OFFROAD = "Todoterreno"; const val PICKUP = "Camioneta pickup"; const val VAN = "Furgoneta"
    const val SUV = "SUV"; const val TRUCK = "Camión"; const val COMPETITION = "Competición"
    val ALL = listOf(CLASSIC, MODERN, SPORTS, SUPERCAR, COUPE, SEDAN, HATCHBACK, MUSCLE, COMPACT, OFFROAD, PICKUP, VAN, SUV, TRUCK, COMPETITION)
}

/** Medidas objetivas del vehículo (m). [shell] = carrocería sin ruedas ni espejos para no inflar el ancho. */
class VehicleFeatures(
    val length: Float, val width: Float, val height: Float,
    val wheelCount: Int, val axles: Int, val wheelbase: Float, val track: Float, val wheelRadius: Float,
    val doors: Int, val triangles: Int,
    val nameTokens: Set<String>,
) {
    /** Vector normalizado para comparar con correcciones aprendidas. */
    fun vector(): FloatArray = floatArrayOf(length / 10f, width / 4f, height / 5f, wheelCount / 12f, doors / 6f, wheelbase / 6f)
    val nameKey: String get() = nameTokens.sorted().joinToString("")
}

object FeatureExtractor {
    private val SHELL = setOf(PartCategory.BODY, PartCategory.DOOR, PartCategory.HOOD, PartCategory.TRUNK, PartCategory.FENDER, PartCategory.BUMPER, PartCategory.ROOF, PartCategory.SKIRT)

    fun extract(model: VehicleModel, extraNames: List<String> = emptyList()): VehicleFeatures {
        val vis = model.parts.filter { it.defaultVisible }
        val wheels = vis.filter { it.category == PartCategory.WHEEL }
        val shell = vis.filter { it.category in SHELL }.ifEmpty { vis.filter { it.category != PartCategory.WHEEL && it.category != PartCategory.MIRROR } }
        val sb = com.webmaletin.gtaviewer.model.ModelAssembler.robustBounds(shell) ?: model.fullBounds()
        val s = sb?.size ?: floatArrayOf(0f, 0f, 0f)
        // Ruedas: centros en espacio de visor (x = lateral, z = longitudinal)
        val centers = wheels.map { w -> val b = model.partBounds(w); b.center to (b.size[1] / 2) }
        val zs = centers.map { it.first[2] }.sorted()
        val axleZ = ArrayList<Float>()
        for (z in zs) if (axleZ.isEmpty() || abs(z - axleZ.last()) > 0.45f) axleZ += z
        val wheelbase = if (axleZ.size >= 2) axleZ.last() - axleZ.first() else 0f
        val frontAxle = axleZ.lastOrNull()
        val track = centers.filter { frontAxle != null && abs(it.first[2] - frontAxle) < 0.45f }.map { it.first[0] }.let { if (it.size >= 2) it.max() - it.min() else 0f }
        val radius = centers.map { it.second }.average().let { if (it.isNaN()) 0f else it.toFloat() }
        // Alto total = del fondo de las ruedas al techo
        val all = com.webmaletin.gtaviewer.model.ModelAssembler.robustBounds(shell + wheels)
        val h = all?.size?.get(1) ?: s[1]
        // Puertas: lados distintos (lf, rf, lr, rr) deducidos de los nombres; las sub-piezas (cristal de puerta) no cuentan.
        val doorParts = vis.filter { it.category == PartCategory.DOOR }
        val sides = doorParts.mapNotNull { p -> Regex("(lf|rf|lr|rr|lb|rb)").find(p.frameName?.lowercase() ?: "")?.value }.toSet()
        val doorFrames = if (sides.isNotEmpty()) sides.size else doorParts.size.coerceAtMost(4)
        val tokens = (listOf(model.name) + extraNames).flatMap { Names.tokens(it) }.toSet()
        return VehicleFeatures(s[2], s[0], h, wheels.size, axleZ.size, wheelbase, track, radius, doorFrames, model.totalTriangles, tokens)
    }
}

object VehicleClassifier {
    private val SUPERCAR_NAMES = setOf("lambo", "gallardo", "murcielago", "aventador", "zonda", "pagani", "ferrari", "enzo", "f40", "f50", "infernus", "veyron", "chiron", "mclaren", "slr", "koenigsegg", "bugatti", "cheetah", "nsx", "huracan", "carrera", "gt2", "cinque", "zc")
    private val SPORT_NAMES = setOf("supra", "jester", "elegy", "flash", "stratum", "sultan", "uranus", "turismo", "comet", "bullet", "banshee", "buffalo", "gtr", "skyline", "rx7", "rx8", "z4", "nsx", "evo", "lancer", "wrx", "impreza", "viper", "cayman", "911", "turbo", "tt", "m3", "m4", "rs6", "r8", "gt", "sti", "slk", "elise", "lotus")
    private val MUSCLE_NAMES = setOf("mustang", "camaro", "gto", "monaro", "challenger", "charger", "stallion", "buccaneer", "tampa", "dominator", "hotring", "muscle", "gt500", "cobra", "corvette", "svt")
    private val TRUCK_NAMES = setOf("truck", "artic", "artict", "artict1", "artict2", "artict3", "linerun", "petrotr", "petro", "cement", "rdtrain", "dft30", "flatbed", "tanker", "trailer", "roadtrain", "packer", "mule", "benson", "baggage", "tugstair", "bagboxa", "bagboxb", "dumper", "dozer", "tractor", "cementtr")
    private val VAN_NAMES = setOf("van", "furgon", "transit", "burrito", "pony", "rumpo", "moonbeam", "speedo", "boxville", "ambulance", "minibus")
    private val PICKUP_NAMES = setOf("pickup", "bobcat", "yosemite", "sadler", "walton", "hilux", "ranger", "f150", "silverado", "dft", "dodge", "ram")
    private val SUV_NAMES = setOf("suv", "jeep", "patriot", "landstalker", "huntley", "rancher", "4x4", "offroad", "rover", "cayenne", "x5", "q7", "mesa", "hummer")
    private val CLASSIC_NAMES = setOf("classic", "vintage", "retro", "oldschool", "beetle", "tornado", "hotknife", "blistac", "voodoo", "remington", "savanna", "broadway", "slamvan", "stafford", "glendale", "emperor", "buccaneer", "stallion", "greenwood")
    private val COMP_NAMES = setOf("rally", "race", "racing", "drift", "nascar", "gt3", "cup", "wrx", "sti", "evo", "supergt", "super", "competition", "cop")

    private fun add(m: MutableMap<String, Float>, label: String, v: Float, reason: MutableList<String>, why: String) {
        if (v <= 0f) return
        m[label] = minOf(0.95f, (m[label] ?: 0f) + v); reason += "$label +%.2f: $why".format(v)
    }

    class Result(val labels: List<TypeLabel>, val reasons: List<String>)

    fun classify(f: VehicleFeatures, corrections: List<Correction> = emptyList()): Result {
        val m = LinkedHashMap<String, Float>(); val why = ArrayList<String>()
        val tk = f.nameTokens
        fun named(set: Set<String>) = tk.any { it in set }
        val len = f.length; val h = f.height
        val ratioHL = if (len > 0) h / len else 0f

        // --- Camión / remolque
        if (len > 7.0f) add(m, VehicleTypes.TRUCK, 0.7f, why, "longitud %.1f m".format(len))
        if ((f.wheelCount >= 6 || f.axles >= 3) && len > 5.5f) add(m, VehicleTypes.TRUCK, 0.4f, why, "${f.wheelCount} ruedas / ${f.axles} ejes")
        if (h > 3.0f) add(m, VehicleTypes.TRUCK, 0.3f, why, "altura %.1f m".format(h))
        if (named(TRUCK_NAMES)) add(m, VehicleTypes.TRUCK, 0.3f, why, "nombre")
        val bigTruck = (m[VehicleTypes.TRUCK] ?: 0f) >= 0.7f

        if (!bigTruck) {
            // --- Furgoneta
            if (h > 1.85f && len in 4.2f..6.8f) add(m, VehicleTypes.VAN, 0.5f, why, "alta (%.2f m) y larga %.1f m".format(h, len))
            if (named(VAN_NAMES)) add(m, VehicleTypes.VAN, 0.35f, why, "nombre")
            // --- Pickup
            if (named(PICKUP_NAMES)) add(m, VehicleTypes.PICKUP, 0.5f, why, "nombre")
            if (h in 1.6f..2.1f && len in 5.0f..6.6f && f.doors <= 4) add(m, VehicleTypes.PICKUP, 0.2f, why, "proporciones de camioneta")
            // --- SUV / todoterreno
            if (named(SUV_NAMES)) add(m, VehicleTypes.SUV, 0.4f, why, "nombre")
            if (h in 1.65f..2.1f && len in 4.0f..5.5f && f.wheelRadius > 0.36f) add(m, VehicleTypes.SUV, 0.45f, why, "alto %.2f m, ruedas grandes (r=%.2f)".format(h, f.wheelRadius))
            if (named(setOf("offroad", "4x4", "jeep", "patriot", "landstalker", "rancher", "huntley"))) add(m, VehicleTypes.OFFROAD, 0.5f, why, "nombre")
            if (h > 1.7f && f.wheelRadius > 0.42f) add(m, VehicleTypes.OFFROAD, 0.4f, why, "ruedas muy grandes")
            // --- Superdeportivo / deportivo
            if (h < 1.3f && len > 4.0f) add(m, VehicleTypes.SUPERCAR, 0.5f, why, "muy baja (%.2f m)".format(h))
            if (named(SUPERCAR_NAMES)) add(m, VehicleTypes.SUPERCAR, 0.4f, why, "nombre")
            if (h < 1.5f && len > 3.8f) add(m, VehicleTypes.SPORTS, 0.4f, why, "baja (%.2f m)".format(h))
            if (named(SPORT_NAMES) || named(SUPERCAR_NAMES)) add(m, VehicleTypes.SPORTS, 0.35f, why, "nombre")
            // --- Muscle
            if (named(MUSCLE_NAMES)) add(m, VehicleTypes.MUSCLE, 0.55f, why, "nombre")
            if (f.doors in 1..2 && len > 4.9f && h in 1.2f..1.55f) add(m, VehicleTypes.MUSCLE, 0.25f, why, "coupé largo y bajo")
            // --- Carrocería
            if (f.doors >= 4 && len in 4.2f..6.2f && h in 1.3f..1.75f) add(m, VehicleTypes.SEDAN, 0.55f, why, "${f.doors} puertas, %.1f m".format(len))
            if (f.doors in 1..3 && h < 1.7f && len > 3.4f) add(m, VehicleTypes.COUPE, 0.5f, why, "${f.doors} puertas")
            if (len < 4.5f && h > 1.3f && f.doors in 1..5 && ratioHL > 0.31f) add(m, VehicleTypes.HATCHBACK, 0.45f, why, "corto (%.1f m) y alto".format(len))
            if (named(setOf("golf", "punto", "clio", "hatch", "civic", "polo", "fiesta", "corsa", "astra", "focus", "mini", "swift"))) add(m, VehicleTypes.HATCHBACK, 0.4f, why, "nombre")
            if (len in 2.5f..4.1f) add(m, VehicleTypes.COMPACT, 0.5f, why, "longitud %.1f m".format(len))
            if (named(COMP_NAMES)) add(m, VehicleTypes.COMPETITION, 0.35f, why, "nombre")
            // Un deportivo bajo no es "compacto" aunque sea corto (los vehículos de SA suelen estar a menor escala)
            if (((m[VehicleTypes.SPORTS] ?: 0f) >= 0.5f || (m[VehicleTypes.SUPERCAR] ?: 0f) >= 0.5f)) m[VehicleTypes.COMPACT] = (m[VehicleTypes.COMPACT] ?: 0f) - 0.3f
        }
        // --- Época
        if (named(CLASSIC_NAMES)) add(m, VehicleTypes.CLASSIC, 0.5f, why, "nombre clásico")
        val year = tk.mapNotNull { it.toIntOrNull() }.firstOrNull { it in 1930..2030 }
        if (year != null) add(m, if (year < 1990) VehicleTypes.CLASSIC else VehicleTypes.MODERN, 0.5f, why, "año $year")
        if (f.triangles > 25000 && year == null && VehicleTypes.CLASSIC !in m) add(m, VehicleTypes.MODERN, 0.4f, why, "modelo detallado (${f.triangles} triángulos)")

        // --- Correcciones aprendidas del usuario (vecino más cercano)
        val v = f.vector()
        val near = corrections.minByOrNull { c -> dist(v, c.features.toFloatArray()) }
        val sameName = corrections.firstOrNull { it.name.isNotEmpty() && it.name == f.nameKey }
        val learned = sameName ?: near?.takeIf { dist(v, it.features.toFloatArray()) < 0.06f }
        val labels = ArrayList<TypeLabel>()
        if (learned != null) {
            val src = if (sameName != null) "corrección del usuario (mismo modelo)" else "aprendido de una corrección similar"
            learned.labels.forEach { labels += TypeLabel(it, if (sameName != null) 0.95f else 0.75f, src); why += "$it: $src" }
        }
        m.entries.filter { it.value >= 0.4f && labels.none { l -> l.label == it.key } }.sortedByDescending { it.value }
            .forEach { labels += TypeLabel(it.key, it.value) }
        return Result(labels, why)
    }

    private fun dist(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) { val d = a[i] - b.getOrElse(i) { 0f }; s += d * d }; return sqrt(s) }
}
