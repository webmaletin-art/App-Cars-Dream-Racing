package com.webmaletin.gtaviewer.export

import com.webmaletin.gtaviewer.editing.VehicleConfig
import com.webmaletin.gtaviewer.model.VehicleModel
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BatchJob(
    val id: String,
    val name: String,
    /** Construye el modelo (DFF+TXD) SOLO cuando le toca el turno. */
    val load: () -> VehicleModel,
    /** Configuración propia de este vehículo (null = sin tocar: todo lo visible por defecto). */
    val config: VehicleConfig?,
    /** Nombre del archivo importado (sin extensión): con él se nombra todo lo exportado. */
    val exportName: String = name,
)

enum class BatchState { PROCESSING, OK, WARNING, ERROR, CANCELLED }

class BatchProgress(val index: Int, val total: Int, val jobId: String, val name: String, val state: BatchState, val message: String?)

class BatchResult(
    val total: Int,
    /** Nombres de archivo (o carpetas) incluidos en el ZIP. */
    val okFiles: List<String>,
    val errors: List<Pair<String, String>>,
    val warnings: Map<String, List<String>>,
    val cancelled: Boolean,
) {
    val okCount get() = okFiles.size
}

/**
 * Exportación masiva: procesa los vehículos UNO POR UNO (carga → configuración propia → visibilidad → exporta →
 * libera) y escribe en streaming un único ZIP con solo los archivos correctos. Un fallo no cancela el resto.
 */
object BatchExporter {
    fun run(
        jobs: List<BatchJob>,
        format: ExportFormat,
        out: OutputStream,
        paint: PaintScheme = PaintScheme(),
        scale: Float = 1f,
        damage: DamageMode = DamageMode.EMBEDDED,
        onProgress: (BatchProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): BatchResult {
        val okFiles = ArrayList<String>()
        val errors = ArrayList<Pair<String, String>>()
        val warnings = LinkedHashMap<String, List<String>>()
        val usedNames = HashSet<String>()
        var cancelled = false
        ZipOutputStream(out).use { zip ->
            for ((i, job) in jobs.withIndex()) {
                if (isCancelled()) { cancelled = true; onProgress(BatchProgress(i, jobs.size, job.id, job.name, BatchState.CANCELLED, null)); break }
                onProgress(BatchProgress(i, jobs.size, job.id, job.name, BatchState.PROCESSING, null))
                try {
                    var model: VehicleModel? = job.load()
                    val warn = ArrayList<String>()
                    val m = model!!
                    if (m.diagnostics.txdFileName != null && !m.diagnostics.txdValid)
                        throw IllegalStateException("TXD incompatible (${m.diagnostics.chunkIssues.lastOrNull() ?: "no se pudo leer"})")
                    if (m.totalTriangles == 0) throw IllegalStateException("DFF sin geometría válida")
                    val session = VehicleConfig.sessionFor(m, job.config)
                    val visible = session.visibleIds()
                    if (visible.isEmpty()) throw IllegalStateException("no hay partes visibles que exportar")
                    if (m.diagnostics.txdFileName == null) warn += "sin TXD: se exporta con colores de material"
                    if (m.diagnostics.texturesMissing.isNotEmpty()) warn += "texturas faltantes: ${m.diagnostics.texturesMissing.joinToString()}"
                    if (m.validation.needsReview) warn += "modelo requiere revisión (${m.validation.reasons.firstOrNull()})"

                    val base = uniqueBase(ExportNaming.fileSafe(job.exportName), usedNames)
                    val exported = VehicleExport.export(m, visible, session.state.deleted, paint, scale, base, format, damage)
                    if (exported.mime == ExportFormat.GLB.mime) { // un solo GLB (con o sin daños dentro)
                        zip.putNextEntry(ZipEntry(exported.fileName)); zip.write(exported.bytes); zip.closeEntry()
                        okFiles += exported.fileName
                    } else { // ZIP (OBJ/FBX, o GLB con daños separados): una carpeta por vehículo con todos sus archivos
                        ZipInputStream(ByteArrayInputStream(exported.bytes)).use { zin ->
                            while (true) {
                                val e = zin.nextEntry ?: break
                                // OBJ/FBX con daños: «base/…» queda tal cual y «base_danos/…» pasa a «base/danos/…» (todo dentro de la carpeta del vehículo)
                                val inner = when {
                                    e.name.startsWith("${base}_danos/") -> "danos/" + e.name.removePrefix("${base}_danos/")
                                    e.name.startsWith("$base/") -> e.name.removePrefix("$base/")
                                    else -> e.name
                                }
                                zip.putNextEntry(ZipEntry("$base/$inner")); zin.copyTo(zip); zip.closeEntry()
                            }
                        }
                        okFiles += "$base/"
                    }
                    model = null // libera referencias (geometría, materiales, texturas) antes del siguiente
                    if (warn.isNotEmpty()) warnings[job.name] = warn
                    onProgress(BatchProgress(i, jobs.size, job.id, job.name,
                        if (warn.isEmpty()) BatchState.OK else BatchState.WARNING, warn.joinToString("; ").ifEmpty { null }))
                } catch (e: Throwable) {
                    val msg = if (e is OutOfMemoryError) "memoria insuficiente" else (e.message ?: e.javaClass.simpleName)
                    errors += job.name to msg
                    onProgress(BatchProgress(i, jobs.size, job.id, job.name, BatchState.ERROR, msg))
                }
            }
        }
        return BatchResult(jobs.size, okFiles, errors, warnings, cancelled)
    }

    private fun uniqueBase(base: String, used: MutableSet<String>): String {
        var b = base; var n = 2
        while (!used.add(b.lowercase())) { b = "${base}_$n"; n++ }
        return b
    }
}
