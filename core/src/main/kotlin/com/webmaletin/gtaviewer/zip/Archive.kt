package com.webmaletin.gtaviewer.zip

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Origen de bytes de un archivo (entrada de ZIP, archivo suelto del dispositivo, memoria...). Solo lectura. */
interface ByteSource {
    val displayName: String
    val size: Long
    fun readBytes(): ByteArray
    /** Lee como máximo [max] bytes del inicio (descomprime solo lo necesario). */
    fun readPrefix(max: Int): ByteArray
    /** Flujo de lectura (descomprime sobre la marcha): para archivos enormes que no deben cargarse enteros en memoria. */
    fun openStream(): java.io.InputStream = java.io.ByteArrayInputStream(readBytes())
}

class MemorySource(override val displayName: String, private val data: ByteArray) : ByteSource {
    override val size: Long get() = data.size.toLong()
    override fun readBytes() = data.copyOf()
    override fun readPrefix(max: Int) = data.copyOf(minOf(max, data.size))
}

/** Entrada de un archivo comprimido, con ruta normalizada ('/' como separador, sin '/' inicial). */
class ArchiveEntry(
    val archiveId: Int,
    val archiveName: String,
    val path: String,
    val size: Long,
    val crc: Long,
    val source: ByteSource,
) {
    val dir: String get() = path.substringBeforeLast('/', "")
    val fileName: String get() = path.substringAfterLast('/')
    val baseName: String get() = fileName.substringBeforeLast('.', fileName)
    val extension: String get() = if ('.' in fileName) fileName.substringAfterLast('.').lowercase() else ""
    override fun toString() = "[$archiveName] $path"
}

/** ZIP abierto con acceso aleatorio (no se extrae a disco). Seguro para uso desde varios hilos. */
class ZipArchive private constructor(
    val id: Int,
    val name: String,
    private val zip: ZipFile,
) : AutoCloseable {
    private val lock = Any()

    val entries: List<ArchiveEntry> by lazy {
        val out = ArrayList<ArchiveEntry>()
        val en = zip.entries
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (e.isDirectory) continue
            val path = normalizePath(e.name) ?: continue
            out += ArchiveEntry(id, name, path, e.size, e.crc, EntrySource(e, path.substringAfterLast('/')))
        }
        out
    }

    private inner class EntrySource(private val entry: ZipArchiveEntry, override val displayName: String) : ByteSource {
        override val size: Long get() = entry.size
        override fun readBytes(): ByteArray = synchronized(lock) {
            zip.getInputStream(entry).use { it.readBytes() }
        }
        /** Cada llamada abre su propio flujo sobre la entrada (el ZipFile permite lecturas concurrentes de entradas distintas). */
        override fun openStream(): java.io.InputStream = zip.getInputStream(entry)
        override fun readPrefix(max: Int): ByteArray = synchronized(lock) {
            zip.getInputStream(entry).use { ins ->
                val buf = ByteArray(minOf(max.toLong(), if (entry.size >= 0) entry.size else Long.MAX_VALUE).toInt())
                var n = 0
                while (n < buf.size) {
                    val r = ins.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n == buf.size) buf else buf.copyOf(n)
            }
        }
    }

    override fun close() = zip.close()

    companion object {
        fun open(id: Int, name: String, channel: SeekableByteChannel): ZipArchive =
            ZipArchive(id, name, ZipFile.builder().setSeekableByteChannel(channel).setUseUnicodeExtraFields(true).get())

        fun open(id: Int, file: File, displayName: String = file.name): ZipArchive =
            open(id, displayName, Files.newByteChannel(file.toPath(), StandardOpenOption.READ))

        /** Normaliza separadores y descarta basura de macOS / rutas peligrosas. */
        fun normalizePath(raw: String): String? {
            val p = raw.replace('\\', '/').trimStart('/')
            if (p.isEmpty()) return null
            val segs = p.split('/')
            if (segs.any { it == ".." }) return null
            if (segs.first() == "__MACOSX" || segs.last().startsWith("._")) return null
            return p
        }
    }
}
