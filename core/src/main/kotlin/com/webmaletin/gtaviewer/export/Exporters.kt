package com.webmaletin.gtaviewer.export

import com.webmaletin.gtaviewer.fbx.FbxExporter
import com.webmaletin.gtaviewer.glb.GlbExporter
import com.webmaletin.gtaviewer.obj.ObjExporter
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val label: String, val mime: String) {
    GLB("GLB", "model/gltf-binary"),
    OBJ("OBJ + MTL + texturas", "application/zip"),
    FBX("FBX + texturas", "application/zip"),
}

class ExportOutput(
    val fileName: String,
    val mime: String,
    val bytes: ByteArray,
    val warnings: List<String>,
    /** No nulo si el formato no pudo conservar algo del modelo (se muestra al usuario; nunca se oculta). */
    val limitations: String?,
    /** Archivos internos (para tests/diagnóstico). */
    val entries: List<String>,
)

interface SceneExporter {
    val format: ExportFormat
    fun export(scene: ExportScene, baseName: String): ExportOutput
}

object Exporters {
    fun forFormat(f: ExportFormat): SceneExporter = when (f) {
        ExportFormat.GLB -> GlbExporter()
        ExportFormat.OBJ -> ObjExporter()
        ExportFormat.FBX -> FbxExporter()
    }

    fun zip(files: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, data) in files) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
        }
        return bos.toByteArray()
    }
}

/** Nombres de archivo de exportación: SIEMPRE el nombre del archivo importado (así no hay que volver a escribirlo). */
object ExportNaming {
    fun fileSafe(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").replace(Regex("_+"), "_").trim('_').ifEmpty { "vehiculo" }

    /**
     * @param source nombre del archivo importado (sin extensión).
     * @param label si no es nulo (p. ej. "ruedas", "puertas") se añade al nombre al exportar solo una selección: «nombre_ruedas».
     * @param edited se mantiene por compatibilidad; ya NO añade «_editado»: el archivo se llama igual que el importado.
     */
    @Suppress("UNUSED_PARAMETER")
    fun baseName(source: String, edited: Boolean = false, label: String? = null): String {
        val b = fileSafe(source)
        return if (label != null) "${b}_${fileSafe(label)}" else b
    }

    fun fileName(base: String, format: ExportFormat): String = when (format) {
        ExportFormat.GLB -> "$base.glb"
        ExportFormat.OBJ -> "${base}_OBJ.zip"
        ExportFormat.FBX -> "${base}_FBX.zip"
    }
}
