package com.webmaletin.gtaviewer.fit

import com.webmaletin.gtaviewer.classify.VehicleTypes
import com.webmaletin.gtaviewer.library.LibraryStore
import com.webmaletin.gtaviewer.library.PieceMeta
import com.webmaletin.gtaviewer.mesh.FlatMesh
import com.webmaletin.gtaviewer.mesh.MeshAnalysis
import com.webmaletin.gtaviewer.model.PartCategory
import com.webmaletin.gtaviewer.project.CarProject
import java.util.Random

class GenRequest(
    val vehicleType: String? = null,
    /** "Clásico" o "Moderno" (null = indistinto). */
    val style: String? = null,
    /** 0 = original sin modificar … 3 = muy modificado. */
    val modLevel: Int = 2,
    val useOriginalParts: Boolean = true,
    val useCustomParts: Boolean = true,
    val paintColor: Int? = null,
    val allowedCategories: Set<PartCategory> = setOf(PartCategory.BUMPER, PartCategory.SPOILER, PartCategory.SKIRT, PartCategory.HOOD, PartCategory.ROOF, PartCategory.EXHAUST, PartCategory.GRILLE, PartCategory.FENDER),
    val includeWheels: Boolean = true,
    val includeInterior: Boolean = true,
    val keepEmblems: Boolean = true,
    /** Piezas que el usuario quiere conservar al regenerar: categoría (+ lado/longitud) → id de pieza. */
    val locked: Map<String, String> = emptyMap(),
    val baseVehicleId: String? = null,
    val seed: Long = 1L,
)

class GenPick(val slot: String, val category: PartCategory, val pieceId: String, val pieceName: String, val result: CompatResult, val fromSameVehicle: Boolean)

class GenResult(
    val baseId: String, val baseName: String, val picks: List<GenPick>, val missing: List<String>, val notes: List<String>, val seed: Long, val request: GenRequest,
) {
    /** Evaluado solo por dimensiones/posición; la comprobación de mallas se hace al montarlo ([CarGenerator.measure]). */
    var meshChecked = false
    fun summary() = picks.joinToString { "${it.slot}: ${it.pieceName} (${it.result.level.label})" }
}

object CarGenerator {
    /** Hueco que ocupa una pieza: paragolpes = delantero/trasero; faldón = lado; guardabarros = lado+eje; resto = un único hueco. */
    private fun slotKey(c: PartCategory, p: PieceMeta) = when (c) {
        PartCategory.BUMPER -> "${c.name}:${p.longitudinal ?: "ANY"}"
        PartCategory.SKIRT -> "${c.name}:${p.side ?: "ANY"}"
        PartCategory.FENDER -> "${c.name}:${p.longitudinal ?: "ANY"}:${p.side ?: "ANY"}"
        else -> c.name
    }

    fun generate(store: LibraryStore, req: GenRequest): GenResult {
        val rnd = Random(req.seed)
        val vehicles = store.vehicles()
        val notes = ArrayList<String>(); val missing = ArrayList<String>()
        // --- base
        val base = if (req.baseVehicleId != null) store.get(req.baseVehicleId) else {
            val cands = vehicles.filter { v ->
                (req.vehicleType == null || v.types.any { it.label == req.vehicleType && it.confidence >= 0.4f }) &&
                    (req.style == null || v.types.any { it.label == req.style && it.confidence >= 0.4f })
            }
            if (cands.isEmpty()) { notes += "No hay vehículos en el catálogo con tipo=${req.vehicleType ?: "cualquiera"} y estilo=${req.style ?: "cualquiera"}." }
            // se prefiere el que tiene más piezas compatibles disponibles (más variedad)
            cands.sortedByDescending { c -> store.componentsOf(c.id).size }.take(5).let { top -> if (top.isEmpty()) null else top[rnd.nextInt(top.size)] }
        }
        if (base == null) return GenResult("", "", emptyList(), listOf("Falta un vehículo base que cumpla los filtros"), notes, req.seed, req)
        val info = FitPlanner.baseInfo(store, base.id) ?: return GenResult(base.id, base.name, emptyList(), listOf("La base no tiene datos de piezas (reimporte el vehículo)"), notes, req.seed, req)
        val learned = store.learnedFits()
        val components = store.components()

        // --- huecos a rellenar según el nivel de modificación
        val wanted = ArrayList<PartCategory>()
        val pool = req.allowedCategories.toMutableList().apply { shuffle(rnd) }
        val n = when (req.modLevel) { 0 -> 0; 1 -> 1 + rnd.nextInt(2); 2 -> 3 + rnd.nextInt(3); else -> 5 + rnd.nextInt(4) }
        wanted += pool.take(n)
        // las bloqueadas siempre cuentan
        val picks = ArrayList<GenPick>()
        val usedSlots = HashSet<String>()
        for ((k, id) in req.locked) {
            val piece = store.get(id) ?: continue
            val cat = PartCategory.fromName(piece.category) ?: continue
            val r = FitPlanner.evaluate(info, piece, piece.parentId?.let { store.get(it) }, learned)
            picks += GenPick(k, cat, id, piece.name, r, piece.parentId == base.id); usedSlots += k
        }

        fun candidatesFor(cat: PartCategory): List<Pair<PieceMeta, CompatResult>> =
            components.filter { it.category == cat.name && it.triangles >= 30 }.filter { p ->
                val same = p.parentId == base.id
                (same && req.useOriginalParts) || (!same && req.useCustomParts)
            }.map { p -> p to FitPlanner.evaluate(info, p, p.parentId?.let { store.get(it) }, learned) }
                .filter { it.second.level.rank >= CompatLevel.ADJUST.rank }

        for (cat in wanted) {
            val cands = candidatesFor(cat)
            if (cands.isEmpty()) { missing += "No hay ${cat.label.lowercase()} compatibles con ${base.name} (se evaluaron ${components.count { it.category == cat.name }} del catálogo)"; continue }
            // un hueco por (lado/longitud): se agrupa y se elige una pieza de cada grupo relevante
            val groups = cands.groupBy { slotKey(cat, it.first) }.filterKeys { it !in usedSlots }
            val keys = groups.keys.toList().shuffled(rnd)
            // paragolpes: delantero y trasero; faldones: izquierdo y derecho; resto: un único hueco
            val take = if (cat == PartCategory.BUMPER || cat == PartCategory.SKIRT) keys else keys.take(1)
            for (k in take) {
                val list = groups[k]!!.sortedByDescending { it.second.score }
                val top = list.take(4)
                val (piece, r) = top[rnd.nextInt(top.size)]
                picks += GenPick(k, cat, piece.id, piece.name, r, piece.parentId == base.id); usedSlots += k
            }
        }
        if (req.includeWheels) {
            val wheels = candidatesFor(PartCategory.WHEEL)
            val locked = req.locked.entries.firstOrNull { it.key.startsWith(PartCategory.WHEEL.name) }
            if (locked == null) {
                if (wheels.isEmpty()) missing += "No hay ruedas compatibles en el catálogo para ${base.name}"
                else if (req.modLevel > 0) { val top = wheels.sortedByDescending { it.second.score }.take(4); val (p, r) = top[rnd.nextInt(top.size)]; picks += GenPick(PartCategory.WHEEL.name, PartCategory.WHEEL, p.id, p.name, r, p.parentId == base.id) }
            }
        }
        if (!req.includeInterior) notes += "El interior se excluirá de la exportación del proyecto."
        if (!req.keepEmblems) notes += "Emblemas: revise y reemplace los logotipos en el editor de texturas (la detección no es automática)."
        notes += "Evaluado por dimensiones, proporciones y posición. NO se ha comprobado aún la intersección de mallas: móntelo y use 'Revisar'."
        return GenResult(base.id, base.name, picks, missing, notes, req.seed, req)
    }

    /** Genera varias alternativas distintas (semillas consecutivas). */
    fun alternatives(store: LibraryStore, req: GenRequest, count: Int): List<GenResult> {
        val out = ArrayList<GenResult>(); val seen = HashSet<String>()
        var s = req.seed
        var tries = 0
        while (out.size < count && tries < count * 6) {
            val r = generate(store, GenRequest(req.vehicleType, req.style, req.modLevel, req.useOriginalParts, req.useCustomParts, req.paintColor, req.allowedCategories, req.includeWheels, req.includeInterior, req.keepEmblems, req.locked, req.baseVehicleId, s))
            val key = r.baseId + r.picks.joinToString("|") { it.pieceId }
            if (seen.add(key) && r.baseId.isNotEmpty()) out += r
            s++; tries++
        }
        return out
    }

    /** Monta el resultado en un proyecto (la base queda bloqueada; las piezas sustituyen a las equivalentes sin destruirlas). */
    fun apply(project: CarProject, store: LibraryStore, r: GenResult): List<Int> {
        project.setBaseVehicle(r.baseId)
        val info = FitPlanner.baseInfo(store, r.baseId)
        val added = ArrayList<Int>()
        for (p in r.picks) {
            if (p.category == PartCategory.WHEEL) { added += project.addWheelSet(p.pieceId); continue }
            val repl = p.result.replaces?.let { name -> project.state.parts.firstOrNull { it.isBase && it.name == name && !it.deleted } }
            val id = if (repl != null) project.replace(repl.id, p.pieceId, p.result) else project.addPiece(p.pieceId, p.result)
            if (id != null) added += id
        }
        r.request.paintColor?.let { project.setPaint(it) }
        if (!r.request.includeInterior) project.setExport(project.state.parts.filter { it.category == PartCategory.INTERIOR.name || it.category == PartCategory.SEAT.name || it.category == PartCategory.STEERING.name }.map { it.id }, false)
        r.meshChecked = false
        return added
    }

    class Measure(val partName: String, val overlap: Float, val protrusion: Float, val verdict: String)

    /** Comprobación REAL sobre mallas: solape y salientes de cada pieza añadida respecto a la carrocería base. */
    fun measure(project: CarProject, partIds: List<Int>): List<Measure> {
        val model = project.materialize()
        val baseParts = model.parts.filter { p -> project.part(p.id)?.let { it.isBase && !it.deleted && it.visible } == true }
        if (baseParts.isEmpty()) return emptyList()
        val baseFlat = FlatMesh.combine(baseParts.filter { it.category != PartCategory.WHEEL }.map { FlatMesh.fromPart(it, it.transform) })
        return partIds.mapNotNull { id ->
            val p = model.parts.firstOrNull { it.id == id } ?: return@mapNotNull null
            val f = FlatMesh.fromPart(p, p.transform)
            val o = MeshAnalysis.overlap(baseFlat, f)
            val verdict = when {
                o.protrusion > 0.5f -> "sobresale mucho de la carrocería (${(o.protrusion * 100).toInt()} % de sus vértices fuera de la caja de la base)"
                o.ratioOfSmaller > 0.6f -> "solape muy alto con la base (${(o.ratioOfSmaller * 100).toInt()} %): probable intersección"
                else -> "sin solapes ni salientes graves"
            }
            Measure(p.name, o.ratioOfSmaller, o.protrusion, verdict)
        }
    }
}
