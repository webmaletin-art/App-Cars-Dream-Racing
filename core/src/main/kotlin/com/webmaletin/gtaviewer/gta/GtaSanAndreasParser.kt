package com.webmaletin.gtaviewer.gta

import com.webmaletin.gtaviewer.catalog.*
import com.webmaletin.gtaviewer.model.VehicleModel
import com.webmaletin.gtaviewer.zip.ArchiveEntry
import com.webmaletin.gtaviewer.zip.ByteSource

/** Módulo GTA San Andreas (también lee DFF/TXD de GTA III y Vice City, que comparten RenderWare 3.x). */
class GtaSanAndreasParser : VehicleFormatParser {
    override val module = FormatModule.GTA_SA
    private val scanner = GtaSaScanner()
    private val builder = GtaSaVehicleBuilder()

    override fun scan(entries: List<ArchiveEntry>, onProgress: (ScanProgress) -> Unit, isCancelled: () -> Boolean): ScanReport =
        scanner.scan(entries, onProgress, isCancelled)

    override fun load(entry: VehicleEntry): VehicleModel = builder.build(entry.displayName, entry.dff.source, entry.txd?.source)

    override fun loadManual(name: String, dff: ByteSource, txd: ByteSource?): VehicleModel = builder.build(name, dff, txd)

    override fun addRelatedPart(model: VehicleModel, part: RelatedPart): VehicleModel =
        builder.addPart(model, part.dff.fileName, part.dff.source, part.txd?.source)
}
