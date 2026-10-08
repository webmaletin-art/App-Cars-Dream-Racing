package com.webmaletin.gtaviewer.editing

import com.webmaletin.gtaviewer.model.MeshPart
import com.webmaletin.gtaviewer.model.PartCategory
import com.webmaletin.gtaviewer.model.VehicleModel

/**
 * Estado de edición (inmutable). Los ids son ids de SubMesh. NUNCA toca el DFF/TXD/ZIP originales.
 * REGLA ÚNICA: un sub-mesh es visible (y por tanto se exporta) si NO está en [hidden] ni en [deleted].
 * [categories] son reclasificaciones manuales de partes (id de parte → categoría).
 */
data class EditState(
    val hidden: Set<Int>,
    val deleted: Set<Int>,
    val selection: Set<Int>,
    val isolated: Boolean = false,
    val categories: Map<Int, PartCategory> = emptyMap(),
)

/**
 * Sesión de edición sobre la representación interna del modelo. "Visible = exportar": la visibilidad de esta sesión
 * es la única fuente de verdad del visor y de la exportación (no existe otra lista).
 */
class EditSession(val model: VehicleModel, initial: EditState? = null, knownIds: Set<Int> = emptySet()) {
    private val defaultHidden: Set<Int> =
        model.parts.filter { !it.defaultVisible }.flatMap { p -> p.subMeshes.map { it.id } }.toSet()

    var state = if (initial == null) EditState(defaultHidden, emptySet(), emptySet())
    else initial.copy(hidden = initial.hidden + (defaultHidden - knownIds))
        private set

    /** Nueva sesión sobre [newModel] (p. ej. tras añadir piezas) conservando lo oculto/eliminado/seleccionado. */
    fun rebase(newModel: VehicleModel) = EditSession(newModel, state, model.subMeshes.map { it.id }.toSet())

    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    private val allIds: Set<Int> by lazy { model.subMeshes.map { it.id }.toSet() }

    fun isVisible(subId: Int) = subId !in state.hidden && subId !in state.deleted
    fun visibleIds(): Set<Int> = model.subMeshes.map { it.id }.filter { isVisible(it) }.toSet()
    fun isEdited(): Boolean = state.deleted.isNotEmpty() || state.hidden != defaultHidden || state.categories.isNotEmpty()
    fun categoryOf(p: MeshPart): PartCategory = state.categories[p.id] ?: p.category

    private fun push(next: EditState) {
        undoStack.addLast(state); if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        state = next
    }

    // ---------------------------------------------------------------- selección (no entra en el historial)
    fun select(ids: Set<Int>) { state = state.copy(selection = ids.filter { it in allIds }.toSet()) }
    fun toggle(ids: Set<Int>) {
        val cur = state.selection
        select(if (cur.containsAll(ids)) cur - ids else cur + ids)
    }
    fun clearSelection() = select(emptySet())
    fun selectAll() = select(allIds)

    // ---------------------------------------------------------------- visibilidad
    fun hide(ids: Set<Int> = state.selection) {
        if (ids.isEmpty()) return
        push(state.copy(hidden = state.hidden + ids, selection = state.selection - ids))
    }

    fun delete(ids: Set<Int> = state.selection) {
        if (ids.isEmpty()) return
        push(state.copy(deleted = state.deleted + ids, hidden = state.hidden - ids, selection = state.selection - ids))
    }

    /** Muestra solo [ids]: el resto se oculta (no se elimina). */
    fun isolate(ids: Set<Int> = state.selection) {
        if (ids.isEmpty()) return
        val keep = ids
        push(state.copy(hidden = allIds - keep, deleted = emptySet(), isolated = true))
    }

    fun show(ids: Set<Int>) {
        if (ids.isEmpty()) return
        push(state.copy(hidden = state.hidden - ids, deleted = state.deleted - ids))
    }

    /** Casilla del Outliner: marcar = visible (también recupera lo eliminado), desmarcar = oculto. */
    fun setVisible(ids: Set<Int>, visible: Boolean) {
        if (ids.isEmpty()) return
        push(if (visible) state.copy(hidden = state.hidden - ids, deleted = state.deleted - ids, isolated = false)
        else state.copy(hidden = state.hidden + ids, isolated = false))
    }

    fun showEverything() = push(state.copy(hidden = emptySet(), deleted = emptySet(), isolated = false))
    fun hideEverything() = push(state.copy(hidden = allIds, deleted = emptySet(), isolated = false))

    /** Invertir: lo visible pasa a oculto y lo oculto/eliminado pasa a visible. */
    fun invertVisibility() = push(state.copy(hidden = visibleIds(), deleted = emptySet(), isolated = false))

    /** Vuelve a mostrar lo ocultado por el usuario (las variantes dañadas/LOD siguen ocultas por defecto). */
    fun showAll() = push(state.copy(hidden = defaultHidden - state.deleted, isolated = false))

    fun restoreDeleted() { if (state.deleted.isNotEmpty()) push(state.copy(deleted = emptySet())) }

    /** Restaura la visibilidad original del DFF (conserva reclasificaciones manuales). */
    fun restoreVisibility() = push(state.copy(hidden = defaultHidden, deleted = emptySet(), isolated = false))

    fun resetAll() = push(EditState(defaultHidden, emptySet(), emptySet()))

    // ---------------------------------------------------------------- reclasificación manual
    fun setCategory(partIds: Set<Int>, category: PartCategory) {
        val parts = model.parts.filter { it.id in partIds }
        if (parts.isEmpty()) return
        val m = state.categories.toMutableMap()
        for (p in parts) if (category == p.category) m.remove(p.id) else m[p.id] = category
        push(state.copy(categories = m))
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(state); state = prev.copy(selection = state.selection.filter { it in allIds }.toSet())
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(state); state = next.copy(selection = state.selection)
        return true
    }
}
