package com.webmaletin.gtaviewer.beamng

import com.webmaletin.gtaviewer.export.*
import com.webmaletin.gtaviewer.model.VehicleModel
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object BeamExport {
    /**
     * Exporta un coche de BeamNG: el vehículo (con sus piezas separadas, una malla por pieza), opcionalmente con las variantes
     * dañadas que existan, y opcionalmente el esqueleto de física (nodos/vigas) como JSON dentro del mismo ZIP.
     */
    fun export(
        r: BeamLoadResult, model: VehicleModel, ids: Set<Int>, deleted: Set<Int>, paint: PaintScheme, scale: Float, base: String,
        format: ExportFormat, damage: DamageMode, physics: Boolean,
    ): ExportOutput {
        val out = VehicleExport.export(model, ids, deleted, paint, scale, base, format, damage)
        val warnings = ArrayList(out.warnings)
        warnings += "BeamNG no guarda mallas dañadas salvo variantes sueltas (p. ej. parabrisas roto): la deformación se simula con vigas, por eso no hay «daños» como en GTA"
        if (!physics) return ExportOutput(out.fileName, out.mime, out.bytes, warnings.distinct(), out.limitations, out.entries)
        val files = ArrayList<Pair<String, ByteArray>>()
        if (out.mime == "application/zip") ZipInputStream(ByteArrayInputStream(out.bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; files += e.name to z.readBytes() } }
        else files += out.fileName to out.bytes
        val json = r.physics.toJson().toByteArray(Charsets.UTF_8)
        files += "${base}_fisica.json" to json
        warnings += "La física (nodos + vigas de BeamNG) va en ${base}_fisica.json: Godot no la usa directamente; sirve para derivar masa, ruedas y centro de masa"
        return ExportOutput("${base}_completo.zip", "application/zip", Exporters.zip(files), warnings.distinct(), out.limitations, files.map { it.first })
    }
}
