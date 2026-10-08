package com.webmaletin.gtaviewer

import com.webmaletin.gtaviewer.catalog.*
import com.webmaletin.gtaviewer.editing.EditSession
import com.webmaletin.gtaviewer.export.*
import com.webmaletin.gtaviewer.model.PartCategory
import com.webmaletin.gtaviewer.selection.SelectionTools
import com.webmaletin.gtaviewer.zip.MemorySource
import com.webmaletin.gtaviewer.zip.ZipArchive
import java.io.File
import kotlin.test.*

class EndToEndTest {
    private val txd = RwTestWriter.txd(listOf(
        RwTestWriter.TestTexture("body", 8, 8, 21, RwTestWriter.solidBgra(8, 8, 120, 10, 10)),
        RwTestWriter.TestTexture("glass", 8, 8, 21, RwTestWriter.solidBgra(8, 8, 90, 120, 200)),
        RwTestWriter.TestTexture("tyre", 8, 8, 21, RwTestWriter.solidBgra(8, 8, 20, 20, 20)),
    ))

    private fun open(files: Map<String, ByteArray>): ZipArchive {
        val f = File.createTempFile("e2e", ".zip"); f.deleteOnExit(); f.writeBytes(RwTestWriter.zip(files))
        return ZipArchive.open(0, f)
    }

    @Test fun zipToCatalogToEditedExportInAllFormats() {
        val z = open(mapOf(
            "Audi A4/audia4.dff" to RwTestWriter.sampleVehicleDff(), "Audi A4/audia4.txd" to txd, "Audi A4/audia4.fxt" to "x".toByteArray(),
            "misc/nothing.txt" to "hola".toByteArray(),
        ))
        val parser = FormatRegistry.default
        assertEquals(FormatModule.GTA_SA, parser.module)
        val progress = ArrayList<ScanProgress>()
        val report = parser.scan(z.entries, { progress += it })
        assertEquals(listOf("Audi A4"), report.vehicles.map { it.displayName })
        assertTrue(progress.map { it.percent }.zipWithNext().all { (a, b) -> b >= a }, "el progreso no retrocede")
        assertEquals(100, progress.last().percent)
        assertTrue(progress.any { it.stage.startsWith("Analizando DFF") } && progress.any { it.stage.startsWith("Emparejando TXD") })

        val model = parser.load(report.vehicles[0])
        assertEquals("GTA_SA", model.moduleId)
        val s = EditSession(model)
        s.select(SelectionTools.byCategory(model, PartCategory.WHEEL)); s.delete()
        val base = ExportNaming.baseName(model.name, s.isEdited())
        assertEquals("Audi_A4_editado", base)
        val scene = ExportSceneBuilder.build(model, s.visibleIds(), PaintScheme(), 1f, base)
        assertTrue(scene.meshes.none { it.partName.startsWith("Wheel") })
        for (f in ExportFormat.values()) {
            val out = Exporters.forFormat(f).export(scene, base)
            assertTrue(out.bytes.size > 200, f.name)
            assertTrue(out.fileName.startsWith("Audi_A4_editado"), out.fileName)
        }
        z.close()
    }

    @Test fun manualModeLoadsExactCombinationAcrossFolders() {
        val z = open(mapOf("weird/a/thing.dff" to RwTestWriter.sampleVehicleDff(), "other/place/xyz.txd" to txd))
        val dff = z.entries.first { it.extension == "dff" }; val txdE = z.entries.first { it.extension == "txd" }
        val m = FormatRegistry.default.loadManual("Manual", dff.source, txdE.source)
        assertTrue(m.diagnostics.txdValid)
        assertTrue(m.diagnostics.texturesFound.containsAll(listOf("body", "glass", "tyre")))
        val m2 = FormatRegistry.default.loadManual("Sin TXD", dff.source, null)
        assertTrue(m2.totalTriangles > 0)
        z.close()
    }

    @Test fun relatedPartCanBeAddedToTheScene() {
        val partDff = RwTestWriter.dff(
            listOf(RwTestWriter.TestFrame(null, -1), RwTestWriter.TestFrame("spoiler_hi", 0, floatArrayOf(0f, -2f, 0.8f))),
            listOf(RwTestWriter.box(0f, 0f, 0f, 1.5f, .3f, .1f, listOf(RwTestWriter.TestMaterial(255, 255, 255, 255, "body")))),
            listOf(RwTestWriter.TestAtomic(1, 0)),
        )
        val z = open(mapOf(
            "car/car.dff" to RwTestWriter.sampleVehicleDff(), "car/car.txd" to txd, "car/tuning_spoiler.dff" to partDff,
        ))
        val p = FormatRegistry.default
        val report = p.scan(z.entries)
        val v = report.vehicles.single()
        assertEquals(1, v.relatedParts.size)
        val base = p.load(v)
        val extended = p.addRelatedPart(base, v.relatedParts[0])
        assertEquals(base.parts.size + 1, extended.parts.size)
        val added = extended.parts.last()
        assertEquals(PartCategory.SPOILER, added.category)
        assertNotNull(added.extraSource)
        assertTrue(added.subMeshes.all { it.id !in base.subMeshes.map { s -> s.id } })
        // las ediciones previas se conservan al re-basar la sesión
        val s = EditSession(base); s.select(SelectionTools.byCategory(base, PartCategory.WHEEL)); s.delete()
        val s2 = s.rebase(extended)
        assertEquals(s.state.deleted, s2.state.deleted)
        assertTrue(s2.isVisible(added.subMeshes[0].id))
        z.close()
    }

    @Test fun scalesToLargeZips() {
        val dff = RwTestWriter.sampleVehicleDff()
        val files = LinkedHashMap<String, ByteArray>()
        for (i in 0 until 600) {
            files["pack/car$i/car$i.dff"] = RwTestWriter.dff(
                listOf(RwTestWriter.TestFrame(null, -1), RwTestWriter.TestFrame("chassis", 0)),
                listOf(RwTestWriter.box(0f, 0f, 0f, 1f + i % 7, 2f, 1f, listOf(RwTestWriter.TestMaterial(255, 255, 255)))), listOf(RwTestWriter.TestAtomic(1, 0)))
            files["pack/car$i/car$i.txd"] = txd
            repeat(3) { k -> files["pack/car$i/extra$k.bin"] = ByteArray(64) }
        }
        files["x.dff"] = dff
        val z = open(files)
        val t0 = System.currentTimeMillis()
        val r = FormatRegistry.default.scan(z.entries)
        val ms = System.currentTimeMillis() - t0
        assertEquals(601, r.vehicles.size)
        assertEquals(600, r.vehicles.count { it.txd != null })
        println("Escaneo de ${z.entries.size} archivos / 601 DFF en $ms ms")
        assertTrue(ms < 20_000, "escaneo demasiado lento: $ms ms")
        z.close()
    }
}
