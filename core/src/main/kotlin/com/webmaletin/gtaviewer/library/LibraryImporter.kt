package com.webmaletin.gtaviewer.library

import com.webmaletin.gtaviewer.catalog.FormatRegistry
import com.webmaletin.gtaviewer.catalog.VehicleEntry
import com.webmaletin.gtaviewer.catalog.VehicleFormatParser
import com.webmaletin.gtaviewer.zip.ByteSource
import com.webmaletin.gtaviewer.zip.MemorySource

/** Importa vehículos al catálogo permanente una sola vez y sin duplicar (hash de DFF+TXD). */
class LibraryImporter(private val store: LibraryStore, private val parser: VehicleFormatParser = FormatRegistry.default) {
    class Related(val name: String, val dff: ByteSource, val txd: ByteSource?)
    class Item(val name: String, val source: String, val origin: String, val dff: ByteSource, val txd: ByteSource?, val related: List<Related> = emptyList())

    class Summary(
        val total: Int, val added: Int, val duplicates: Int, val components: Int, val mergedComponents: Int,
        val errors: List<Pair<String, String>>, val cancelled: Boolean,
    )

    class Progress(val index: Int, val total: Int, val name: String, val state: String, val message: String?)

    companion object {
        fun itemOf(e: VehicleEntry) = Item(
            e.displayName, e.dff.archiveName, e.dff.path, e.dff.source, e.txd?.source,
            e.relatedParts.map { Related(it.dff.baseName, it.dff.source, it.txd?.source) },
        )
    }

    fun importOne(item: Item): AddResult {
        val dffBytes = item.dff.readBytes()
        val txdBytes = item.txd?.readBytes()
        val dffHash = Hashing.sha1(dffBytes); val txdHash = txdBytes?.let { Hashing.sha1(it) }
        store.findVehicleByHashes(dffHash, txdHash)?.let { return AddResult(null, it.id, 0, 0, 0) } // ya estaba: no se duplica
        val txdSrc = txdBytes?.let { MemorySource(item.txd!!.displayName, it) }
        val model = parser.loadManual(item.name, MemorySource(item.dff.displayName, dffBytes), txdSrc)
        val related = ArrayList<Pair<String, com.webmaletin.gtaviewer.model.VehicleModel>>()
        for (r in item.related) {
            try {
                related += r.name to parser.loadManual(r.name, MemorySource(r.dff.displayName, r.dff.readBytes()), r.txd?.let { MemorySource(it.displayName, it.readBytes()) } ?: txdSrc)
            } catch (e: Exception) { /* una pieza ilegible no impide guardar el vehículo */ }
        }
        val info = SourceInfo(item.name, item.source, item.origin, dffHash, txdHash, item.dff.displayName, item.txd?.displayName)
        return store.addVehicle(model, info, related)
    }

    /** Uno por uno (nunca dos modelos en memoria a la vez), con progreso y cancelación; un fallo no detiene el resto. */
    fun importAll(items: List<Item>, onProgress: (Progress) -> Unit = {}, isCancelled: () -> Boolean = { false }): Summary {
        var added = 0; var dup = 0; var comps = 0; var merged = 0; var cancelled = false
        val errors = ArrayList<Pair<String, String>>()
        for ((i, it) in items.withIndex()) {
            if (isCancelled()) { cancelled = true; break }
            onProgress(Progress(i, items.size, it.name, "procesando", null))
            try {
                val r = importOne(it)
                if (r.duplicateOf != null) { dup++; onProgress(Progress(i, items.size, it.name, "duplicado", "ya estaba en el catálogo")) }
                else { added++; comps += r.newComponents; merged += r.mergedComponents; onProgress(Progress(i, items.size, it.name, "ok", "${r.newComponents} piezas nuevas, ${r.mergedComponents} ya existentes")) }
            } catch (e: Throwable) {
                val msg = if (e is OutOfMemoryError) "memoria insuficiente" else (e.message ?: e.javaClass.simpleName)
                errors += it.name to msg; onProgress(Progress(i, items.size, it.name, "error", msg))
            }
        }
        return Summary(items.size, added, dup, comps, merged, errors, cancelled)
    }
}

enum class SortKey(val label: String) { NAME("Nombre"), RECENT("Más recientes"), LENGTH("Longitud"), WIDTH("Anchura"), HEIGHT("Altura"), TRIANGLES("Triángulos") }

/** Búsqueda/filtrado/orden del catálogo (en memoria sobre metadatos). */
class LibraryQuery(
    val text: String = "",
    val kind: String? = null,
    val category: String? = null,
    val vehicleType: String? = null,
    val source: String? = null,
    val favoritesOnly: Boolean = false,
    val sort: SortKey = SortKey.NAME,
    val descending: Boolean = false,
) {
    fun apply(list: List<PieceMeta>): List<PieceMeta> {
        val q = text.trim().lowercase()
        val qn = com.webmaletin.gtaviewer.gta.Names.normalize(q)
        // un componente hereda los tipos de su vehículo de origen para el filtro por tipo
        val typesOfVehicle = list.filter { it.kind == "VEHICLE" }.associate { it.id to it.types.map { t -> t.label } }
        var r = list.filter { p ->
            (kind == null || p.kind == kind) && (category == null || p.category == category) && (source == null || p.source == source) &&
                (!favoritesOnly || p.favorite) &&
                (vehicleType == null || (if (p.kind == "VEHICLE") p.types.any { it.label == vehicleType } else typesOfVehicle[p.parentId]?.contains(vehicleType) == true)) &&
                (q.isEmpty() || p.name.lowercase().contains(q) || p.origin.lowercase().contains(q) || p.tags.any { it.lowercase().contains(q) } ||
                    (qn.isNotEmpty() && com.webmaletin.gtaviewer.gta.Names.normalize(p.name).contains(qn)))
        }
        r = when (sort) {
            SortKey.NAME -> r.sortedBy { it.name.lowercase() }
            SortKey.RECENT -> r.sortedBy { it.createdAt }.reversed()
            SortKey.LENGTH -> r.sortedBy { it.dims.l }
            SortKey.WIDTH -> r.sortedBy { it.dims.w }
            SortKey.HEIGHT -> r.sortedBy { it.dims.h }
            SortKey.TRIANGLES -> r.sortedBy { it.triangles }
        }
        return if (descending && sort != SortKey.RECENT) r.reversed() else r
    }
}
