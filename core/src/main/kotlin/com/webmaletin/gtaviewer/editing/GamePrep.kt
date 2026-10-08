package com.webmaletin.gtaviewer.editing

import com.webmaletin.gtaviewer.model.MeshPart
import com.webmaletin.gtaviewer.model.PartCategory

/** Valores iniciales de «PREPARACIÓN PARA MI JUEGO». Solo inicializan; después cada vehículo se ajusta en su Outliner. */
data class PrepOptions(
    val removeWheels: Boolean = true,
    val removeSuspension: Boolean = false,
    val removeBrakes: Boolean = false,
    val removeOther: Boolean = false,
) {
    fun categories(): Set<PartCategory> = buildSet {
        if (removeWheels) add(PartCategory.WHEEL)
        if (removeSuspension) add(PartCategory.SUSPENSION)
        if (removeBrakes) add(PartCategory.BRAKES)
        if (removeOther) add(PartCategory.OTHER)
    }
}

object GamePrep {
    class Result(val wheels: List<MeshPart>, val hiddenParts: List<MeshPart>, val notes: List<String>)

    /** Qué partes se ocultarían (sin aplicar nada). */
    fun plan(session: EditSession, options: PrepOptions): Result {
        val cats = options.categories()
        val wheels = session.model.parts.filter { session.categoryOf(it) == PartCategory.WHEEL }
        val hide = session.model.parts.filter { session.categoryOf(it) in cats }
        val notes = ArrayList<String>()
        if (options.removeWheels && wheels.isEmpty()) notes += "No se identificaron ruedas automáticamente: selecciónelas tocándolas o reclasifíquelas en el Outliner."
        return Result(wheels, hide, notes)
    }

    /**
     * Parte de la visibilidad original, oculta las categorías elegidas y deja el resto visible. Solo cambia la
     * representación de trabajo; los archivos originales no se tocan. Se puede deshacer.
     */
    fun apply(session: EditSession, options: PrepOptions): Result {
        val r = plan(session, options)
        session.restoreVisibility()
        session.setVisible(r.hiddenParts.flatMap { p -> p.subMeshes.map { it.id } }.toSet(), false)
        return r
    }
}
