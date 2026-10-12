package com.webmaletin.gtaviewer

import com.webmaletin.gtaviewer.catalog.FormatRegistry
import com.webmaletin.gtaviewer.export.ExportFormat
import com.webmaletin.gtaviewer.export.ExportProfile
import com.webmaletin.gtaviewer.export.Exporters
import com.webmaletin.gtaviewer.fit.CarGenerator
import com.webmaletin.gtaviewer.fit.GenRequest
import com.webmaletin.gtaviewer.glb.GlbVerifier
import com.webmaletin.gtaviewer.library.LibraryImporter
import com.webmaletin.gtaviewer.library.LibraryStore
import com.webmaletin.gtaviewer.project.ProjectStore
import com.webmaletin.gtaviewer.zip.ArchiveEntry
import com.webmaletin.gtaviewer.zip.ZipArchive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Prueba con los packs REALES (solo si existe la carpeta indicada en GTA_REAL_ZIPS o /tmp/claude-0/zips; si no, se omite).
 * Recorre el flujo completo: importar → catálogo persistente → reabrir → generar → proyecto → GLB Godot → re-importar y verificar.
 */
class RealPacksTest {
    @Test fun realPacksEndToEnd() {
        val dir = File(System.getenv("GTA_REAL_ZIPS") ?: "/tmp/claude-0/zips")
        val zips = dir.listFiles { f -> f.name.endsWith(".zip") }?.sortedBy { it.name } ?: return
        if (zips.isEmpty()) return
        val root = Files.createTempDirectory("real-lib").toFile()
        val store = LibraryStore(root)
        val archives = zips.mapIndexed { i, f -> ZipArchive.open(i, f, f.name) }
        try {
            val entries = ArrayList<ArchiveEntry>(); archives.forEach { entries += it.entries }
            val rep = FormatRegistry.default.scan(entries)
            val t0 = System.currentTimeMillis()
            val sum = LibraryImporter(store).importAll(rep.vehicles.map(LibraryImporter::itemOf))
            println("REAL: ${zips.size} zips · ${rep.vehicles.size} vehículos detectados · añadidos ${sum.added} · duplicados ${sum.duplicates} · piezas ${sum.components} (fusionadas ${sum.mergedComponents}) · errores ${sum.errors.size} · ${(System.currentTimeMillis() - t0) / 1000}s")
            sum.errors.forEach { println("REAL error: ${it.first}: ${it.second}") }
            assertTrue(sum.added >= 20, "debería importar decenas de vehículos reales")
            // re-importar no duplica
            val n = store.all().size
            val again = LibraryImporter(store).importAll(rep.vehicles.map(LibraryImporter::itemOf))
            assertEquals(n, store.all().size); assertEquals(0, again.added)
            // reinicio: otro objeto sobre la misma carpeta
            val s2 = LibraryStore(root)
            assertEquals(n, s2.all().size)
            val typed = s2.vehicles().count { it.types.isNotEmpty() }
            println("REAL: ${s2.vehicles().size} vehículos · ${s2.components().size} piezas · con clasificación $typed · tras reinicio ${s2.all().size} elementos · ${s2.storageBytes() / 1048576} MB")
            // generador + proyecto + GLB
            val res = CarGenerator.alternatives(s2, GenRequest(modLevel = 2, seed = 7), 3)
            assertTrue(res.isNotEmpty())
            for (r in res) {
                val ps = ProjectStore(s2)
                val p = ps.create("real ${r.baseName}")
                val added = CarGenerator.apply(p, s2, r)
                val meas = CarGenerator.measure(p, added)
                val scene = p.exportScene(ExportProfile.GODOT)
                val glb = Exporters.forFormat(ExportFormat.GLB).export(scene, "x")
                val v = GlbVerifier.verify(scene, glb.bytes)
                println("REAL: generado sobre ${r.baseName}: ${r.picks.size} piezas, ${meas.count { !it.verdict.startsWith("sin solapes") }} avisos de malla, GLB ${glb.bytes.size / 1024} KB, verificación ${if (v.ok) "OK" else "FALLA"}")
                if (!v.ok) println(v.text())
                assertTrue(v.ok, v.text())
                ps.save(p)
                val (re, missing) = ps.open(p.id)
                assertEquals(p.state.parts.size, re.state.parts.size); assertTrue(missing.isEmpty())
            }
        } finally { archives.forEach { it.close() } }
    }
}
