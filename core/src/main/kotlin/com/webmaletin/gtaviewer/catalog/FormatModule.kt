package com.webmaletin.gtaviewer.catalog

import com.webmaletin.gtaviewer.model.VehicleModel
import com.webmaletin.gtaviewer.zip.ArchiveEntry
import com.webmaletin.gtaviewer.zip.ByteSource

/**
 * Módulos de formato de juego. Hoy solo existe GTA_SA. Para añadir otro juego (p. ej. Need for Speed):
 *  1. añadir el valor aquí;
 *  2. implementar [VehicleFormatParser] en su propio paquete;
 *  3. registrarlo en [FormatRegistry.parsers].
 * Nada más de la app (UI, edición, exportación) necesita cambiar: trabajan sobre [VehicleModel].
 */
enum class FormatModule(val id: String, val displayName: String) {
    GTA_SA("GTA_SA", "GTA San Andreas"),
}

interface VehicleFormatParser {
    val module: FormatModule

    /** Escanea las entradas de uno o varios archivos y construye el catálogo (sin cargar geometría). */
    fun scan(entries: List<ArchiveEntry>, onProgress: (ScanProgress) -> Unit = {}, isCancelled: () -> Boolean = { false }): ScanReport

    /** Carga un único vehículo del catálogo (bajo demanda). */
    fun load(entry: VehicleEntry): VehicleModel

    /** Modo manual: combinación exacta elegida por el usuario (el TXD es opcional). */
    fun loadManual(name: String, dff: ByteSource, txd: ByteSource?): VehicleModel

    /** Añade una pieza relacionada (DFF separado) a la escena. Devuelve un modelo nuevo. */
    fun addRelatedPart(model: VehicleModel, part: RelatedPart): VehicleModel
}

object FormatRegistry {
    val parsers: List<VehicleFormatParser> by lazy { listOf(com.webmaletin.gtaviewer.gta.GtaSanAndreasParser()) }
    fun forModule(m: FormatModule): VehicleFormatParser = parsers.first { it.module == m }
    val default: VehicleFormatParser get() = forModule(FormatModule.GTA_SA)
}
