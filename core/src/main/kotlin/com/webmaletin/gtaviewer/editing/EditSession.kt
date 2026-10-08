package com.webmaletin.gtaviewer.editing

import com.webmaletin.gtaviewer.model.VehicleModel

/** Estado de edición (inmutable). Los ids son ids de SubMesh. NUNCA toca el DFF/TXD/ZIP originales. */
data class EditState(
    val hidden: Set<Int>,
    val deleted: Set<Int>,
    val selection: Set<Int>,
    val isolated: Boolean = false,
)

/**
 * Sesión de edición sobre la representación interna del modelo: ocultar, eliminar, aislar, mostrar todo
 * y deshacer. "Eliminar" saca la pieza de la escena (y de la exportación); es reversible con [undo]
 * o [restoreDeleted] mientras el modelo siga cargado.
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

    fun isVisible(subId: Int) = subId !in state.hidden && subId !in state.deleted
    fun visibleIds(): Set<Int> = model.subMeshes.map { it.id }.filter { isVisible(it) }.toSet()
    fun isEdited(): Boolean = state.deleted.isNotEmpty() || state.hidden != defaultHidden

    private fun push(next: EditState) {
        undoStack.addLast(state); if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        state = next
    }

    /** La selección se puede cambiar libremente sin ocupar el historial de deshacer. */
    fun select(ids: Set<Int>) { state = state.copy(selection = ids.filter { it in allIds }.toSet()) }
    fun toggle(ids: Set<Int>) {
        val cur = state.selection
        select(if (cur.containsAll(ids)) cur - ids else cur + ids)
    }
    fun clearSelection() = select(emptySet())

    private val allIds: Set<Int> by lazy { model.subMeshes.map { it.id }.toSet() }

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
        val keep = ids - state.deleted
        val hide = allIds - keep - state.deleted
        push(state.copy(hidden = hide, isolated = true))
    }

    fun show(ids: Set<Int>) {
        if (ids.isEmpty()) return
        push(state.copy(hidden = state.hidden - ids))
    }

    /** Vuelve a mostrar lo ocultado por el usuario (las variantes dañadas/LOD siguen ocultas por defecto). */
    fun showAll() = push(state.copy(hidden = defaultHidden - state.deleted, isolated = false))

    fun restoreDeleted() { if (state.deleted.isNotEmpty()) push(state.copy(deleted = emptySet())) }

    fun resetAll() = push(EditState(defaultHidden, emptySet(), emptySet()))

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
