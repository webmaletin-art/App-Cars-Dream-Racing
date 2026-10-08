package com.webmaletin.gtaviewer.io

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.webmaletin.gtaviewer.zip.ArchiveEntry
import com.webmaletin.gtaviewer.zip.ByteSource
import com.webmaletin.gtaviewer.zip.ZipArchive
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** ZIP abierto desde el selector de archivos del sistema. Solo lectura; el ZIP original nunca se modifica. */
class ImportedArchive(val archive: ZipArchive, private val pfd: ParcelFileDescriptor?, private val temp: File?, val copied: Boolean) : Closeable {
    val name: String get() = archive.name
    val entries: List<ArchiveEntry> get() = archive.entries
    override fun close() {
        runCatching { archive.close() }
        runCatching { pfd?.close() }
        temp?.delete()
    }
}

object Storage {
    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
    }

    fun size(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return -1L
    }

    /**
     * Abre el ZIP con acceso aleatorio directamente sobre el descriptor del archivo (sin copiarlo). Si el proveedor
     * no permite posicionarse (p. ej. flujos remotos) se copia una vez a la caché de la app.
     */
    fun openZip(context: Context, uri: Uri, id: Int): ImportedArchive {
        val name = displayName(context, uri)
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        if (pfd != null) {
            try {
                val ch = FileInputStream(pfd.fileDescriptor).channel
                ch.position(0)
                if (ch.size() > 0) return ImportedArchive(ZipArchive.open(id, name, ch), pfd, null, false)
            } catch (e: Exception) {
                // se intenta la copia
            }
            runCatching { pfd.close() }
        }
        val dir = File(context.cacheDir, "imports").apply { mkdirs() }
        val tmp = File(dir, "import_$id.zip")
        context.contentResolver.openInputStream(uri)?.use { ins -> FileOutputStream(tmp).use { ins.copyTo(it, 1 shl 16) } }
            ?: throw IllegalStateException("No se pudo abrir $name")
        return try {
            ImportedArchive(ZipArchive.open(id, tmp, name), null, tmp, true)
        } catch (e: Exception) {
            tmp.delete(); throw e
        }
    }

    fun clearImportCache(context: Context) {
        File(context.cacheDir, "imports").listFiles()?.forEach { it.delete() }
    }
}

/** Archivo suelto del dispositivo (modo manual) como [ByteSource]. */
class UriSource(private val context: Context, private val uri: Uri, override val displayName: String) : ByteSource {
    override val size: Long = Storage.size(context, uri)
    override fun readBytes(): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("No se pudo abrir $displayName")
    override fun readPrefix(max: Int): ByteArray = context.contentResolver.openInputStream(uri)?.use { ins ->
        val buf = ByteArray(max); var n = 0
        while (n < max) { val r = ins.read(buf, n, max - n); if (r < 0) break; n += r }
        buf.copyOf(n)
    } ?: ByteArray(0)
}

object ExportFiles {
    fun cacheFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter { it.name != name && System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        return File(dir, name)
    }

    fun copyTo(context: Context, src: File, dest: Uri) {
        context.contentResolver.openOutputStream(dest, "w")?.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 16) } }
            ?: throw IllegalStateException("No se pudo escribir en el destino")
    }
}
