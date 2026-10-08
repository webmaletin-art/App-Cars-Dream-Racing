package com.webmaletin.gtaviewer.gta

import com.webmaletin.gtaviewer.catalog.*
import com.webmaletin.gtaviewer.dff.DffParser
import com.webmaletin.gtaviewer.dff.DffSummary
import com.webmaletin.gtaviewer.dff.GtaSaDffParser
import com.webmaletin.gtaviewer.zip.ArchiveEntry

/**
 * Escáner inteligente de ZIP GTA SA: busca DFF/TXD recursivamente (sin estructura fija), empareja por niveles,
 * clasifica vehículo/pieza, elimina duplicados lógicos y construye el catálogo. Todo con un único paso por archivo
 * y sin cargar geometría.
 */
class GtaSaScanner(private val dffParser: DffParser = GtaSaDffParser()) {

    private class DffInfo(val entry: ArchiveEntry, val summary: DffSummary?, val readError: String?) {
        lateinit var classification: DffClassification
        var txd: ArchiveEntry? = null
        var matchNote: String? = null
    }

    fun scan(entries: List<ArchiveEntry>, onProgress: (ScanProgress) -> Unit = {}, isCancelled: () -> Boolean = { false }): ScanReport {
        val t0 = System.currentTimeMillis()
        val log = ArrayList<String>()
        onProgress(ScanProgress("Escaneando ZIP...", 0, "${entries.size} archivos"))

        val dffs = entries.filter { it.extension == "dff" }
        val txds = entries.filter { it.extension == "txd" }
        val hints = entries.filter { it.extension == "txt" || it.extension == "fxt" }
        log += "Archivos totales: ${entries.size}; DFF: ${dffs.size}; TXD: ${txds.size}; TXT/FXT: ${hints.size}"
        onProgress(ScanProgress("Escaneando ZIP...", 5, "${dffs.size} DFF, ${txds.size} TXD"))

        // 1) Resumen ligero de cada DFF (solo cabecera: frames y nº de geometrías)
        val infos = ArrayList<DffInfo>(dffs.size)
        dffs.forEachIndexed { i, e ->
            if (isCancelled()) throw InterruptedException("Escaneo cancelado")
            val info = try {
                val prefix = e.source.readPrefix(PREFIX_BYTES)
                val s = dffParser.summarize(prefix)
                if (s.valid) DffInfo(e, s, null) else DffInfo(e, s, s.issues.firstOrNull() ?: "cabecera RenderWare inválida")
            } catch (ex: Exception) {
                DffInfo(e, null, ex.message ?: ex.javaClass.simpleName)
            }
            infos += info
            if (info.readError != null) log += "ERROR  ${e.path}: ${info.readError}"
            onProgress(ScanProgress("Analizando DFF...", 5 + 55 * (i + 1) / maxOf(1, dffs.size), "${i + 1}/${dffs.size} ${e.fileName}"))
        }

        // 2) Clasificación (por archivo)
        infos.forEach { it.classification = DffClassifier.classify(it.entry, it.summary) }

        // 3) Emparejamiento DFF↔TXD, por archivo comprimido (nunca se mezclan ZIP distintos)
        onProgress(ScanProgress("Emparejando TXD...", 62))
        val claimedTxd = HashSet<ArchiveEntry>()
        for ((_, group) in infos.groupBy { it.entry.archiveId }) {
            val gTxds = txds.filter { it.archiveId == group.first().entry.archiveId }
            val result = TxdMatcher.match(group.map { it.entry }, gTxds, group.associate { it.entry to (it.classification.kind == DffKind.VEHICLE) })
            group.forEach { info ->
                val m = result[info.entry]
                if (m != null) { info.txd = m.txd; info.matchNote = m.note; claimedTxd += m.txd }
            }
        }
        val orphanTxds = txds.filter { it !in claimedTxd }
        orphanTxds.forEach { log += "TXD sin DFF (no se muestra como vehículo): ${it.path}" }

        // 4) Piezas -> vehículos
        onProgress(ScanProgress("Construyendo catálogo...", 70))
        val vehicleInfos = infos.filter { it.classification.kind == DffKind.VEHICLE }
        val partInfos = infos.filter { it.classification.kind == DffKind.PART }
        val attached = HashMap<DffInfo, MutableList<RelatedPart>>()
        val unattachedParts = ArrayList<DffInfo>()
        for (p in partInfos) {
            val owner = PartAttacher.findOwner(p.entry, vehicleInfos.filter { it.entry.archiveId == p.entry.archiveId }.map { it.entry })
            if (owner.vehicle != null) {
                val v = vehicleInfos.first { it.entry === owner.vehicle }
                attached.getOrPut(v) { ArrayList() } += RelatedPart(p.entry, p.txd, owner.reason)
                log += "PIEZA  ${p.entry.path} -> ${v.entry.fileName} (${owner.reason})"
            } else {
                unattachedParts += p
                log += "PIEZA SUELTA  ${p.entry.path} (${owner.reason})"
            }
        }

        // 5) Duplicados lógicos
        val deduped = Dedupe.collapse(vehicleInfos.map { Dedupe.Item(it, it.entry, it.txd) })
        val duplicatesCollapsed = deduped.sumOf { it.duplicates.size }
        deduped.forEach { g -> g.duplicates.forEach { log += "DUPLICADO  ${it.path} == ${g.primary.entry.path}" } }

        // 6) Nombres
        onProgress(ScanProgress("Construyendo catálogo...", 85))
        val primaries = deduped.map { it.primary }
        val namer = VehicleNamer(primaries.map { it.entry }, hints)
        val used = HashMap<String, Int>()
        val vehicles = ArrayList<VehicleEntry>()

        fun build(info: DffInfo, dups: List<ArchiveEntry>, forcedStatus: VehicleStatus?, extraWarn: List<String>): VehicleEntry {
            val resolved = namer.resolve(info.entry, info.txd)
            val n = used.merge(resolved.display.lowercase(), 1, Int::plus)!!
            val display = if (n > 1) "${resolved.display} ($n)" else resolved.display
            val parts = attached[info].orEmpty()
            val warnings = ArrayList<String>(extraWarn)
            val s = info.summary
            if (info.readError != null) warnings += "No se pudo leer el DFF: ${info.readError}"
            else if (s != null && (s.frameCount == 0 || s.geometryCount == 0)) warnings += "El DFF no contiene ${if (s.frameCount == 0) "frames" else "geometrías"}"
            if (info.txd == null) warnings += "TXD faltante: no hay un TXD que corresponda a ${info.entry.fileName}"
            info.matchNote?.let { warnings += it }
            if (parts.isNotEmpty()) warnings += "${parts.size} pieza(s) en DFF separados: ${parts.joinToString { it.dff.fileName }}"
            val status = forcedStatus ?: when {
                info.readError != null -> VehicleStatus.READ_ERROR
                s != null && (s.frameCount == 0 || s.geometryCount == 0) -> VehicleStatus.SUSPECT_DFF
                info.txd == null -> VehicleStatus.TXD_MISSING
                parts.isNotEmpty() -> VehicleStatus.SEPARATE_PARTS
                else -> VehicleStatus.COMPLETE
            }
            return VehicleEntry(
                id = "${info.entry.archiveId}:${info.entry.path}", displayName = display, rawName = info.entry.baseName,
                nameSource = resolved.source, dff = info.entry, txd = info.txd, status = status, warnings = warnings,
                relatedParts = parts, duplicates = dups, summary = info.summary, classification = info.classification, matchNote = info.matchNote,
            )
        }

        deduped.forEach { g -> vehicles += build(g.primary, g.duplicates, null, emptyList()) }
        unattachedParts.forEach { p ->
            vehicles += build(p, emptyList(), if (p.readError != null) VehicleStatus.READ_ERROR else VehicleStatus.SUSPECT_DFF,
                listOf("Parece una pieza suelta (${p.classification.reasons.joinToString("; ")}); no se pudo determinar a qué vehículo pertenece"))
        }
        vehicles.sortBy { it.displayName.lowercase() }

        val errors = vehicles.count { it.status == VehicleStatus.READ_ERROR }
        val withWarn = vehicles.count { it.warnings.isNotEmpty() }
        vehicles.forEach { v -> v.warnings.forEach { log += "AVISO  ${v.displayName}: $it" } }
        val report = ScanReport(
            vehicles = vehicles, orphanTxds = orphanTxds, totalFiles = entries.size, dffCount = dffs.size, txdCount = txds.size,
            hintFileCount = hints.size,
            automaticPairs = infos.count { it.txd != null }, dffWithoutTxd = infos.count { it.txd == null },
            separatedPartFiles = partInfos.size, duplicatesCollapsed = duplicatesCollapsed,
            vehiclesWithWarnings = withWarn, errors = errors, log = log, durationMs = System.currentTimeMillis() - t0,
        )
        onProgress(ScanProgress("Construyendo catálogo...", 100, "${vehicles.size} vehículos detectados"))
        return report
    }

    companion object {
        const val PREFIX_BYTES = 256 * 1024
    }
}
