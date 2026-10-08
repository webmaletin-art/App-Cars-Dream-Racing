package com.webmaletin.gtaviewer.gta

import com.webmaletin.gtaviewer.catalog.*
import com.webmaletin.gtaviewer.zip.ArchiveEntry

/**
 * Nombre del vehículo. Prioridad: carpeta específica del vehículo → nombre del DFF → nombre del TXD.
 * Los archivos TXT/FXT solo se usan, de forma opcional, para mejorar el nombre (nunca son necesarios).
 */
class VehicleNamer(private val vehicleDffs: List<ArchiveEntry>, private val hintFiles: List<ArchiveEntry>) {
    class Resolved(val display: String, val source: NameSource)

    private class Folder(val dir: String, val name: String)

    /** Carpeta más cercana, no genérica, que contiene únicamente este vehículo. */
    private fun specificFolder(dff: ArchiveEntry): Folder? {
        var dir = dff.dir
        while (dir.isNotEmpty()) {
            val name = dir.substringAfterLast('/')
            if (!Names.isGenericFolder(name)) {
                val count = vehicleDffs.count { it.archiveId == dff.archiveId && Paths.isUnder(it.dir, dir) }
                return if (count <= 1) Folder(dir, name) else null
            }
            dir = dir.substringBeforeLast('/', "")
        }
        return null
    }

    fun resolve(dff: ArchiveEntry, txd: ArchiveEntry?): Resolved {
        val folder = specificFolder(dff)
        var raw: String
        var source: NameSource
        when {
            folder != null -> { raw = folder.name; source = NameSource.FOLDER }
            Names.normalize(dff.baseName) in Names.GENERIC_FILE_NAMES && txd != null &&
                Names.normalize(txd.baseName) !in Names.GENERIC_FILE_NAMES -> { raw = txd.baseName; source = NameSource.TXD }
            else -> { raw = dff.baseName; source = NameSource.DFF }
        }
        var display = Names.pretty(raw)

        // Mejora opcional con archivos TXT/FXT
        val hint = findHint(dff, folder, raw)
        if (hint != null) {
            val dn = Names.normalize(display); val hn = Names.normalize(hint.name)
            if (hint.exclusive || (hn.length > dn.length && hn.contains(dn))) { display = hint.name; source = NameSource.HINT_FILE }
        }
        return Resolved(display, source)
    }

    private class Hint(val name: String, val exclusive: Boolean)

    private fun findHint(dff: ArchiveEntry, folder: Folder?, rawName: String): Hint? {
        val scopeRoot = folder?.dir ?: dff.dir
        val candidates = hintFiles.filter {
            it.archiveId == dff.archiveId && Paths.isUnder(dff.dir, it.dir) && Paths.isUnder(it.dir, scopeRoot)
        }
        if (candidates.isEmpty()) return null
        val keys = listOf(Names.normalize(dff.baseName), Names.normalize(rawName)).filter { it.length >= 3 }.distinct()
        // 1) Nombre del archivo: DeclasseYosemite.fxt -> "Declasse Yosemite"
        for (h in candidates) {
            val hn = Names.normalize(h.baseName)
            if (keys.any { hn.contains(it) } && hn !in keys) return Hint(Names.pretty(h.baseName), false)
        }
        // 1b) Carpeta propia del vehículo con un único .fxt de nombre distinto: TruffadeAdder.fxt junto a zr350.dff
        if (folder != null) {
            val own = candidates.filter { it.extension == "fxt" && it.dir.equals(folder.dir, true) }
            val only = own.singleOrNull()
            if (only != null) {
                val hn = Names.normalize(only.baseName)
                if (hn !in keys && hn !in Names.GENERIC_FILE_NAMES && hn.length >= 3) return Hint(Names.pretty(only.baseName), true)
            }
        }
        // 2) Contenido: una línea "CLAVE Nombre visible"
        for (h in candidates) {
            val text = try { String(h.source.readPrefix(16 * 1024), Charsets.ISO_8859_1) } catch (e: Exception) { continue }
            for (line in text.lineSequence().take(400)) {
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#") || l.startsWith(";") || l.startsWith("//")) continue
                val parts = l.split(Regex("[\\s=:]+"), limit = 2)
                if (parts.size == 2 && Names.normalize(parts[0]) in keys) {
                    val v = parts[1].trim().trim('"')
                    if (v.length in 2..40 && v.any { it.isLetter() } && Names.normalize(v) != Names.normalize(parts[0])) return Hint(v, false)
                }
            }
        }
        return null
    }
}
