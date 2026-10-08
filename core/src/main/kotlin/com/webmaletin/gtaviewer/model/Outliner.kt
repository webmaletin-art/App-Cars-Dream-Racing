package com.webmaletin.gtaviewer.model

class OutlinerRow(val part: MeshPart, val category: PartCategory, val displayName: String)
class OutlinerGroup(val category: PartCategory, val rows: List<OutlinerRow>)

/**
 * Árbol del Outliner. Solo se agrupa cuando hay información real (partes con categoría reconocida/propuesta de al
 * menos dos tipos distintos); si no, lista plana. Nunca se inventan grupos: las partes sin categoría quedan sueltas.
 */
class OutlinerTree(val grouped: Boolean, val groups: List<OutlinerGroup>, val loose: List<OutlinerRow>) {
    val allRows: List<OutlinerRow> get() = groups.flatMap { it.rows } + loose
}

enum class TriState { ALL, NONE, SOME }

class Counters(val partsVisible: Int, val partsTotal: Int, val meshesVisible: Int, val meshesTotal: Int)

object Outliner {
    fun canGroup(model: VehicleModel, categoryOf: (MeshPart) -> PartCategory = { it.category }): Boolean {
        val cats = model.parts.map(categoryOf).filter { it != PartCategory.OTHER }
        return cats.size >= 2 && cats.distinct().size >= 2
    }

    fun build(model: VehicleModel, categoryOf: (MeshPart) -> PartCategory, wantGrouped: Boolean): OutlinerTree {
        fun row(p: MeshPart) = OutlinerRow(p, categoryOf(p), PartNaming.friendly(p))
        val rows = model.parts.map(::row)
        if (!wantGrouped || !canGroup(model, categoryOf)) return OutlinerTree(false, emptyList(), rows)
        val groups = rows.filter { it.category != PartCategory.OTHER }.groupBy { it.category }.toList()
            .sortedBy { it.first.order }
            .map { (c, l) -> OutlinerGroup(c, l.sortedWith(compareBy({ it.part.wheelSlot?.ordinal ?: 99 }, { it.displayName.lowercase() }))) }
        return OutlinerTree(true, groups, rows.filter { it.category == PartCategory.OTHER })
    }

    fun counters(model: VehicleModel, visible: Set<Int>): Counters {
        var pv = 0; var mv = 0
        for (p in model.parts) {
            val v = p.subMeshes.count { it.id in visible }
            mv += v; if (v > 0) pv++
        }
        return Counters(pv, model.parts.size, mv, model.subMeshes.size)
    }

    fun triState(ids: Collection<Int>, visible: Set<Int>): TriState {
        if (ids.isEmpty()) return TriState.NONE
        val n = ids.count { it in visible }
        return if (n == ids.size) TriState.ALL else if (n == 0) TriState.NONE else TriState.SOME
    }
}
