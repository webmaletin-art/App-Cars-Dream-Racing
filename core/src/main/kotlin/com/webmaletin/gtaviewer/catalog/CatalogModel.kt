package com.webmaletin.gtaviewer.catalog

import com.webmaletin.gtaviewer.dff.DffSummary
import com.webmaletin.gtaviewer.zip.ArchiveEntry

enum class VehicleStatus(val symbol: String, val label: String) {
    COMPLETE("✓", "Completo"),
    TXD_MISSING("⚠", "TXD faltante"),
    SUSPECT_DFF("⚠", "DFF sospechoso"),
    SEPARATE_PARTS("⚠", "Piezas separadas"),
    READ_ERROR("✕", "Error de lectura"),
}

enum class NameSource { FOLDER, DFF, TXD, HINT_FILE }

enum class DffKind { VEHICLE, PART }

class DffClassification(val kind: DffKind, val vehicleScore: Int, val partScore: Int, val reasons: List<String>)

class RelatedPart(val dff: ArchiveEntry, val txd: ArchiveEntry?, val reason: String)

/** Tarjeta del catálogo. No contiene geometría: se carga bajo demanda. */
class VehicleEntry(
    val id: String,
    val displayName: String,
    val rawName: String,
    val nameSource: NameSource,
    val dff: ArchiveEntry,
    val txd: ArchiveEntry?,
    val status: VehicleStatus,
    val warnings: List<String>,
    val relatedParts: List<RelatedPart>,
    val duplicates: List<ArchiveEntry>,
    val summary: DffSummary?,
    val classification: DffClassification,
    val matchNote: String?,
)

class ScanReport(
    val vehicles: List<VehicleEntry>,
    val orphanTxds: List<ArchiveEntry>,
    val totalFiles: Int,
    val dffCount: Int,
    val txdCount: Int,
    val hintFileCount: Int,
    val automaticPairs: Int,
    val dffWithoutTxd: Int,
    val separatedPartFiles: Int,
    val duplicatesCollapsed: Int,
    val vehiclesWithWarnings: Int,
    val errors: Int,
    val log: List<String>,
    val durationMs: Long,
) {
    fun toLogText(): String = buildString {
        appendLine("ZIP:")
        appendLine("${vehicles.size} vehículos detectados")
        appendLine()
        appendLine("Archivos en el ZIP: $totalFiles")
        appendLine("DFF:"); appendLine(dffCount)
        appendLine("TXD:"); appendLine(txdCount)
        appendLine("Pares automáticos:"); appendLine(automaticPairs)
        appendLine("DFF sin TXD:"); appendLine(dffWithoutTxd)
        appendLine("TXD sin DFF:"); appendLine(orphanTxds.size)
        appendLine("Piezas separadas (DFF de piezas):"); appendLine(separatedPartFiles)
        appendLine("Duplicados lógicos colapsados:"); appendLine(duplicatesCollapsed)
        appendLine("Vehículos con advertencias:"); appendLine(vehiclesWithWarnings)
        appendLine("Errores:"); appendLine(errors)
        appendLine("Tiempo de análisis: $durationMs ms")
        appendLine()
        appendLine("--- Detalle ---")
        log.forEach { appendLine(it) }
    }
}

data class ScanProgress(val stage: String, val percent: Int, val detail: String = "")
