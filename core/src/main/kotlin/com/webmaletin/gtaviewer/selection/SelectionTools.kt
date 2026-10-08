package com.webmaletin.gtaviewer.selection

import com.webmaletin.gtaviewer.model.*

enum class PickMode(val label: String) {
    PART("Pieza"), SUBMESH("Sub-mesh"), MATERIAL("Material"), TEXTURE("Textura"),
}

/** Operaciones de selección sobre el modelo (devuelven ids de SubMesh). */
object SelectionTools {
    fun partSubs(model: VehicleModel, partId: Int): Set<Int> = model.part(partId)?.subMeshes?.map { it.id }?.toSet().orEmpty()

    /** Expande una elección puntual (sub-mesh tocado) según el modo. */
    fun expand(model: VehicleModel, subId: Int, mode: PickMode): Set<Int> {
        val sm = model.subMesh(subId) ?: return emptySet()
        return when (mode) {
            PickMode.SUBMESH -> setOf(subId)
            PickMode.PART -> partSubs(model, sm.partId)
            PickMode.MATERIAL -> byMaterial(model, sm.material.key)
            PickMode.TEXTURE -> sm.material.textureName?.let { byTexture(model, it) } ?: byMaterial(model, sm.material.key)
        }
    }

    fun byMaterial(model: VehicleModel, key: String): Set<Int> = model.subMeshes.filter { it.material.key == key }.map { it.id }.toSet()

    fun byTexture(model: VehicleModel, textureName: String): Set<Int> =
        model.subMeshes.filter { it.material.textureName.equals(textureName, true) }.map { it.id }.toSet()

    fun byCategory(model: VehicleModel, category: PartCategory): Set<Int> =
        model.parts.filter { it.category == category }.flatMap { p -> p.subMeshes.map { it.id } }.toSet()

    fun byWheelSlot(model: VehicleModel, slot: WheelSlot): Set<Int> =
        model.parts.filter { it.wheelSlot == slot }.flatMap { p -> p.subMeshes.map { it.id } }.toSet()

    /**
     * "Seleccionar parecidas": mismas geometrías instanciadas, misma categoría reconocida (excepto carrocería/otros)
     * o misma firma de materiales con un número de vértices similar (±15 %).
     */
    fun similar(model: VehicleModel, subId: Int): Set<Int> {
        val base = model.partOf(subId) ?: return emptySet()
        val baseGeoIds = base.subMeshes.map { it.geometry.id }.toSet()
        val baseSig = signature(base)
        val baseVerts = base.subMeshes.sumOf { it.triangleCount }
        val out = HashSet<Int>()
        for (p in model.parts) {
            val sameGeometry = p.subMeshes.any { it.geometry.id in baseGeoIds }
            val sameCategory = base.recognized && p.recognized && base.category == p.category &&
                base.category != PartCategory.OTHER && base.category != PartCategory.BODY
            val tris = p.subMeshes.sumOf { it.triangleCount }
            val sameSig = signature(p) == baseSig && baseVerts > 0 && kotlin.math.abs(tris - baseVerts) <= baseVerts * 0.15
            if (sameGeometry || sameCategory || sameSig) p.subMeshes.forEach { out += it.id }
        }
        return out
    }

    private fun signature(p: MeshPart): Set<String> = p.subMeshes.map { it.material.key }.toSet()

    /** Piezas agrupadas para el árbol (solo categorías con contenido, en orden fijo). */
    fun groupedParts(model: VehicleModel): List<Pair<PartCategory, List<MeshPart>>> =
        model.parts.groupBy { it.category }.toList().sortedBy { it.first.order }.map { (c, l) ->
            c to l.sortedWith(compareBy({ it.wheelSlot?.ordinal ?: 99 }, { it.name.lowercase() }))
        }
}
