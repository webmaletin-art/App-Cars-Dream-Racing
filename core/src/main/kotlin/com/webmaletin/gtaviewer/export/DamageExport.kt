package com.webmaletin.gtaviewer.export

import com.webmaletin.gtaviewer.model.PartClassifier
import com.webmaletin.gtaviewer.model.VehicleModel
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Cómo salen los daños (`*_dam`) al exportar un vehículo. */
enum class DamageMode(val label: String, val help: String) {
    NONE("Sin daños", "Solo el vehículo intacto."),
    EMBEDDED("Daños dentro del mismo GLB", "Un solo archivo: el vehículo y, en un grupo «Danos» oculto, sus piezas dañadas."),
    SEPARATE_ZIP("ZIP: vehículo + daños", "Un solo ZIP con el vehículo y los daños en archivos separados (funciona en cualquier programa o motor)."),
}

/** Elige qué piezas de daño acompañan a lo que se exporta. */
object DamageSelector {
    private fun key(name: String?, frame: String?) = PartClassifier.base(frame ?: name ?: "")

    /**
     * Sub-meshes de daño cuyo gemelo intacto (mismo frame sin `_ok/_dam`) forma parte de [exported]. Así, si el usuario quitó una
     * puerta, su versión dañada tampoco se exporta. Lo eliminado a mano en el visor ([deleted]) nunca se incluye.
     */
    fun damageIds(model: VehicleModel, exported: Set<Int>, deleted: Set<Int> = emptySet()): Set<Int> {
        val intactBases = model.parts.filter { !it.isDamage && it.subMeshes.any { s -> s.id in exported } }.map { key(it.name, it.frameName) }.toSet()
        return model.parts.filter { it.isDamage && key(it.name, it.frameName) in intactBases }
            .flatMap { p -> p.subMeshes.map { it.id } }.filter { it !in deleted }.toSet()
    }
}

object VehicleExport {
    /**
     * Exporta el vehículo ([ids]) con sus daños según [mode]. Si el modelo no trae piezas dañadas, exporta normal y lo avisa.
     * EMBEDDED solo existe en GLB; en OBJ/FBX (que no tienen nodos ocultos) se usa el ZIP con archivos separados.
     */
    fun export(model: VehicleModel, ids: Set<Int>, deleted: Set<Int>, paint: PaintScheme, scale: Float, base: String, format: ExportFormat, mode: DamageMode): ExportOutput {
        val dmg = if (mode == DamageMode.NONE) emptySet() else DamageSelector.damageIds(model, ids, deleted)
        val exporter = Exporters.forFormat(format)
        if (mode == DamageMode.NONE || dmg.isEmpty()) {
            val out = exporter.export(ExportSceneBuilder.build(model, ids, paint, scale, base), base)
            return if (mode != DamageMode.NONE) ExportOutput(out.fileName, out.mime, out.bytes, out.warnings + "Este vehículo no trae piezas dañadas (*_dam): se exportó solo el intacto", out.limitations, out.entries) else out
        }
        val effective = if (mode == DamageMode.EMBEDDED && format != ExportFormat.GLB) DamageMode.SEPARATE_ZIP else mode
        val notes = ArrayList<String>()
        if (effective != mode) notes += "${format.label} no tiene nodos ocultos: los daños van en archivos separados dentro del ZIP"
        if (effective == DamageMode.EMBEDDED) {
            val out = exporter.export(ExportSceneBuilder.build(model, ids + dmg, paint, scale, base), base)
            return ExportOutput(out.fileName, out.mime, out.bytes, out.warnings + notes + "Daños en el grupo «Danos» (oculto con KHR_node_visibility): si su programa no soporta esa extensión, los verá superpuestos a las piezas intactas",
                out.limitations, out.entries)
        }
        val a = exporter.export(ExportSceneBuilder.build(model, ids, paint, scale, base), base)
        val dBase = base + "_danos"
        val b = exporter.export(ExportSceneBuilder.build(model, dmg, paint, scale, dBase, markDamage = false), dBase)
        val files = ArrayList<Pair<String, ByteArray>>()
        fun add(out: ExportOutput, folder: String) {
            if (format == ExportFormat.GLB) files += out.fileName to out.bytes
            else ZipInputStream(ByteArrayInputStream(out.bytes)).use { zin -> while (true) { val e = zin.nextEntry ?: break; files += "$folder/${e.name}" to zin.readBytes() } }
        }
        add(a, base); add(b, dBase)
        return ExportOutput("${base}_con_danos.zip", "application/zip", Exporters.zip(files), (a.warnings + b.warnings + notes).distinct(),
            a.limitations ?: b.limitations, files.map { it.first })
    }
}
