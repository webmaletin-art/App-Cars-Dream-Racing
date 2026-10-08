package com.webmaletin.gtaviewer.editing

import com.webmaletin.gtaviewer.model.MeshPart
import com.webmaletin.gtaviewer.model.PartCategory
import com.webmaletin.gtaviewer.model.SubMesh
import com.webmaletin.gtaviewer.model.VehicleModel

/**
 * Configuración de un vehículo guardada en memoria (independiente de ids numéricos: usa claves estables
 * «nombreDePieza#índiceDeSubMesh», deterministas para un mismo DFF). Cada vehículo tiene la suya.
 */
data class VehicleConfig(
    val known: Set<String>,
    val hidden: Set<String>,
    val deleted: Set<String>,
    val categories: Map<String, PartCategory>,
) {
    /** Una pieza es visible si no está oculta ni eliminada (misma regla que [EditSession]). */
    fun isVisible(key: String) = key !in hidden && key !in deleted

    companion object {
        fun key(part: MeshPart, sm: SubMesh) = part.name + "#" + part.subMeshes.indexOf(sm)

        fun capture(model: VehicleModel, state: EditState): VehicleConfig {
            val known = HashSet<String>(); val hidden = HashSet<String>(); val deleted = HashSet<String>()
            for (p in model.parts) for (sm in p.subMeshes) {
                val k = key(p, sm); known += k
                if (sm.id in state.deleted) deleted += k else if (sm.id in state.hidden) hidden += k
            }
            val cats = model.parts.mapNotNull { p -> state.categories[p.id]?.let { p.name to it } }.toMap()
            return VehicleConfig(known, hidden, deleted, cats)
        }

        /** Reconstruye el estado de edición sobre un modelo recién cargado (claves desconocidas se ignoran). */
        fun restore(model: VehicleModel, cfg: VehicleConfig): EditState {
            val session = EditSession(model)
            val base = session.state
            val hidden = HashSet<Int>(); val deleted = HashSet<Int>()
            for (p in model.parts) for (sm in p.subMeshes) {
                val k = key(p, sm)
                if (k !in cfg.known) { if (sm.id in base.hidden) hidden += sm.id; continue } // pieza nueva: valor por defecto
                if (k in cfg.deleted) deleted += sm.id else if (k in cfg.hidden) hidden += sm.id
            }
            val cats = model.parts.mapNotNull { p -> cfg.categories[p.name]?.let { p.id to it } }.toMap()
            return EditState(hidden, deleted, emptySet(), false, cats)
        }

        fun sessionFor(model: VehicleModel, cfg: VehicleConfig?): EditSession =
            if (cfg == null) EditSession(model)
            else EditSession(model, restore(model, cfg), model.subMeshes.map { it.id }.toSet())
    }
}
