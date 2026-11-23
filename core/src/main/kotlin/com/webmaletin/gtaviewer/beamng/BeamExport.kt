package com.webmaletin.gtaviewer.beamng

import com.webmaletin.gtaviewer.export.*
import com.webmaletin.gtaviewer.model.VehicleModel
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Qué lleva la exportación de un coche de BeamNG (al menos una opción). */
class BeamExportOptions(val model: Boolean = true, val physics: Boolean = true, val audio: Boolean = false, val configs: Boolean = false) {
    init { require(model || physics || audio || configs) { "Elija qué exportar" } }
    val needsZip get() = listOf(physics, audio, configs).any { it } || !model
}

object BeamExport {
    private val audioExt = setOf("wav", "ogg", "mp3", "sbeam", "sfx")

    /** Archivos de audio del mod: todo `art/sound/…` (el mod no dice qué sonido usa cada configuración) y los sonidos sueltos del coche. */
    fun audioFiles(v: BeamVehicleEntry): List<Pair<String, ByteArray>> = v.files.values.filter { e ->
        val lp = e.path.lowercase(); val i = lp.indexOf("art/sound/")
        i >= 0 || (lp.startsWith(v.dir.lowercase()) && e.extension in audioExt)
    }.sortedBy { it.path }.map { e ->
        val lp = e.path.lowercase(); val i = lp.indexOf("art/sound/")
        ("audio/" + (if (i >= 0) e.path.substring(i) else "vehicles/${v.folder}/" + e.fileName)) to e.source.readBytes()
    }

    /** Todas las configuraciones (`.pc` + su ficha `info_*.json`) y el `info.json` del coche. */
    fun configFiles(v: BeamVehicleEntry): List<Pair<String, ByteArray>> = v.files.values.filter { e ->
        e.path.lowercase().startsWith(v.dir.lowercase()) && e.dir.equals(v.dir.trimEnd('/'), true) &&
            (e.extension == "pc" && !e.fileName.startsWith(".") || e.fileName.lowercase().let { it == "info.json" || (it.startsWith("info_") && it.endsWith(".json")) })
    }.sortedBy { it.fileName.lowercase() }.map { "configuraciones/${it.fileName}" to it.source.readBytes() }

    /**
     * Exporta un coche de BeamNG: el vehículo (una malla por pieza, con las variantes dañadas que existan), y opcionalmente el esqueleto
     * de física (nodos/vigas, JSON), el audio del mod y todas las configuraciones, todo junto en UN ZIP.
     */
    fun export(
        r: BeamLoadResult, v: BeamVehicleEntry, model: VehicleModel, ids: Set<Int>, deleted: Set<Int>, paint: PaintScheme, scale: Float, base: String,
        format: ExportFormat, damage: DamageMode, opts: BeamExportOptions,
    ): ExportOutput {
        val warnings = ArrayList<String>(); val files = ArrayList<Pair<String, ByteArray>>()
        var modelOut: ExportOutput? = null
        if (opts.model) {
            val out = VehicleExport.export(model, ids, deleted, paint, scale, base, format, damage)
            modelOut = out; warnings += out.warnings
            warnings += "BeamNG no guarda mallas dañadas salvo variantes sueltas (p. ej. parabrisas roto): la deformación se simula con vigas, por eso no hay «daños» como en GTA"
            if (!opts.needsZip) return ExportOutput(out.fileName, out.mime, out.bytes, warnings.distinct(), out.limitations, out.entries)
            if (out.mime == "application/zip") ZipInputStream(ByteArrayInputStream(out.bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; files += e.name to z.readBytes() } }
            else files += out.fileName to out.bytes
        }
        if (opts.physics) {
            files += "${base}_fisica.json" to r.physics.toJson().toByteArray(Charsets.UTF_8)
            warnings += "La física (nodos + vigas de BeamNG) va en ${base}_fisica.json: Godot no la usa directamente; sirve para derivar masa, ruedas y centro de masa"
        }
        if (opts.audio) {
            val a = audioFiles(v)
            if (a.isEmpty()) warnings += "Este mod no trae archivos de audio (art/sound)" else { files += a; warnings += "Audio: ${a.size} archivos de art/sound del mod (el mod no indica cuál usa cada configuración, van todos)" }
        }
        if (opts.configs) {
            val c = configFiles(v)
            if (c.isEmpty()) warnings += "Este coche no trae configuraciones (.pc)" else files += c
        }
        if (files.isEmpty()) throw IllegalStateException("No hay nada que exportar con esas opciones (${warnings.firstOrNull() ?: "sin archivos"})")
        val name = when {
            !opts.model && !opts.physics && opts.audio && !opts.configs -> "${base}_audio.zip"
            !opts.model && !opts.physics && !opts.audio -> "${base}_configuraciones.zip"
            else -> "${base}_completo.zip"
        }
        return ExportOutput(name, "application/zip", Exporters.zip(files), warnings.distinct(), modelOut?.limitations, files.map { it.first })
    }
}
