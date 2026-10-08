package com.webmaletin.gtaviewer.catalog

import com.webmaletin.gtaviewer.model.VehicleModel
import com.webmaletin.gtaviewer.zip.ByteSource

/** Estado de preparación de cada vehículo (independiente del estado de análisis del escaneo). */
enum class WorkflowStatus(val symbol: String, val label: String) {
    UNREVIEWED("○", "SIN REVISAR"),
    EDITED("◐", "EDITADO"),
    READY("✓", "LISTO PARA EXPORTAR"),
    ERROR("✕", "ERROR"),
}

class Compatibility(val ok: Boolean, val reason: String?)

object CompatibilityRules {
    /** Compatible = DFF válido + TXD presente + geometría presente (la validez del TXD se confirma al cargar). */
    fun of(e: VehicleEntry): Compatibility {
        val s = e.summary
        return when {
            e.status == VehicleStatus.READ_ERROR || s == null || !s.valid -> Compatibility(false, "DFF inválido o ilegible")
            s.geometryCount == 0 || s.frameCount == 0 -> Compatibility(false, "DFF sin geometría")
            e.txd == null -> Compatibility(false, "TXD faltante")
            e.status == VehicleStatus.SUSPECT_DFF -> Compatibility(false, "DFF sospechoso (¿pieza suelta?)")
            else -> Compatibility(true, null)
        }
    }
}

/**
 * Elemento del catálogo (del ZIP o cargado en modo manual). Solo guarda referencias y metadatos:
 * la geometría se construye bajo demanda con [load].
 */
class CatalogItem(
    val id: String,
    val displayName: String,
    val dffLabel: String,
    val txdLabel: String?,
    val compatibility: Compatibility,
    val entry: VehicleEntry?,
    val manual: Boolean,
    val load: () -> VehicleModel,
) {
    val hasTxd: Boolean get() = txdLabel != null
}

class ManualSources(val name: String, val dff: ByteSource, val dffLabel: String, val txd: ByteSource?, val txdLabel: String?)
