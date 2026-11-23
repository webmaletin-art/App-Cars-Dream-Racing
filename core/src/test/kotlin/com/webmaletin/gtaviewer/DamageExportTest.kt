package com.webmaletin.gtaviewer

import com.webmaletin.gtaviewer.export.*
import com.webmaletin.gtaviewer.gta.GtaSaVehicleBuilder
import com.webmaletin.gtaviewer.model.VehicleModel
import com.webmaletin.gtaviewer.zip.MemorySource
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.test.*

class DamageExportTest {
    private val txd = RwTestWriter.txd(listOf("body", "glass", "tyre").map { RwTestWriter.TestTexture(it, 8, 8, 21, RwTestWriter.solidBgra(8, 8, 90, 20, 20)) })
    private fun load(): VehicleModel = GtaSaVehicleBuilder().build("Test car", MemorySource("t.dff", RwTestWriter.scaledVehicleDff(1f, 1)), MemorySource("t.txd", txd))
    private fun ids(m: VehicleModel) = m.parts.filter { it.defaultVisible }.flatMap { p -> p.subMeshes.map { it.id } }.toSet()
    private fun json(glb: ByteArray): JSONObject {
        val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        val len = bb.getInt(12)
        return JSONObject(String(glb, 20, len, Charsets.UTF_8))
    }
    private fun zipNames(b: ByteArray): List<String> = ZipInputStream(ByteArrayInputStream(b)).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
    private fun zipFile(b: ByteArray, name: String): ByteArray = ZipInputStream(ByteArrayInputStream(b)).use { z -> generateSequence { z.nextEntry }.first { it.name == name }.let { z.readBytes() } }

    @Test fun damageIsHiddenInTheViewerButTheTwinIsKnown() {
        val m = load()
        val dam = m.parts.filter { it.isDamage }
        assertTrue(dam.isNotEmpty() && dam.none { it.defaultVisible }, "las variantes dañadas no se ven por defecto")
        val sel = DamageSelector.damageIds(m, ids(m))
        assertEquals(dam.flatMap { p -> p.subMeshes.map { it.id } }.toSet(), sel, "el chasis visible arrastra su versión dañada")
        // si el chasis intacto se quita, su daño tampoco acompaña
        val noChassis = ids(m) - m.parts.filter { it.name == "chassis" }.flatMap { p -> p.subMeshes.map { it.id } }.toSet()
        assertTrue(DamageSelector.damageIds(m, noChassis).isEmpty())
        // lo eliminado a mano nunca se incluye
        assertTrue(DamageSelector.damageIds(m, ids(m), deleted = sel).isEmpty())
    }

    @Test fun noDamageModeKeepsTheOldFile() {
        val m = load()
        val out = VehicleExport.export(m, ids(m), emptySet(), PaintScheme(), 1f, "Car", ExportFormat.GLB, DamageMode.NONE)
        assertEquals("Car.glb", out.fileName)
        assertFalse(String(out.bytes, Charsets.ISO_8859_1).contains("_dam"))
    }

    @Test fun embeddedGlbCarriesDamageInAHiddenGroup() {
        val m = load()
        val out = VehicleExport.export(m, ids(m), emptySet(), PaintScheme(), 1f, "Car", ExportFormat.GLB, DamageMode.EMBEDDED)
        assertEquals("Car.glb", out.fileName)
        val j = json(out.bytes)
        assertEquals("KHR_node_visibility", j.getJSONArray("extensionsUsed").getString(0))
        val nodes = j.getJSONArray("nodes")
        val group = (0 until nodes.length()).map { nodes.getJSONObject(it) }.first { it.optString("name") == "Danos" }
        assertFalse(group.getJSONObject("extensions").getJSONObject("KHR_node_visibility").getBoolean("visible"))
        val kids = group.getJSONArray("children")
        for (i in 0 until kids.length()) assertTrue(nodes.getJSONObject(kids.getInt(i)).getString("name").endsWith("_dam"))
        // el grupo cuelga del nodo raíz y las piezas dañadas NO cuelgan directamente de él
        val rootKids = nodes.getJSONObject(0).getJSONArray("children").let { a -> (0 until a.length()).map { a.getInt(it) } }
        assertTrue(rootKids.contains((0 until nodes.length()).first { nodes.getJSONObject(it).optString("name") == "Danos" }))
        for (i in 0 until kids.length()) assertFalse(rootKids.contains(kids.getInt(i)))
        // las intactas siguen ahí
        assertTrue((0 until nodes.length()).any { nodes.getJSONObject(it).optString("name") == "chassis" })
    }

    @Test fun separateZipHasVehicleAndDamageFilesTogether() {
        val m = load()
        val out = VehicleExport.export(m, ids(m), emptySet(), PaintScheme(), 1f, "Car", ExportFormat.GLB, DamageMode.SEPARATE_ZIP)
        assertEquals("Car_con_danos.zip", out.fileName)
        assertEquals(listOf("Car.glb", "Car_danos.glb"), zipNames(out.bytes))
        val dmgNodes = json(zipFile(out.bytes, "Car_danos.glb")).getJSONArray("nodes")
        val names = (0 until dmgNodes.length()).map { dmgNodes.getJSONObject(it).optString("name") }
        assertTrue(names.any { it.endsWith("_dam") } && names.none { it == "chassis" }, names.toString())
        assertFalse(json(zipFile(out.bytes, "Car_danos.glb")).has("extensionsUsed"), "en su propio archivo los daños son visibles")
        val intact = json(zipFile(out.bytes, "Car.glb")).getJSONArray("nodes")
        assertTrue((0 until intact.length()).none { intact.getJSONObject(it).optString("name").endsWith("_dam") })
    }

    @Test fun objAndFbxFallBackToSeparateFoldersInsideOneZip() {
        val m = load()
        for (f in listOf(ExportFormat.OBJ, ExportFormat.FBX)) {
            val out = VehicleExport.export(m, ids(m), emptySet(), PaintScheme(), 1f, "Car", f, DamageMode.EMBEDDED)
            assertEquals("Car_con_danos.zip", out.fileName)
            val n = zipNames(out.bytes)
            assertTrue(n.any { it.startsWith("Car/") } && n.any { it.startsWith("Car_danos/") }, n.toString())
            assertTrue(out.warnings.any { it.contains("separados") })
        }
    }

    @Test fun batchObjKeepsOneFolderPerVehicleWithADanosSubfolder() {
        val bos = java.io.ByteArrayOutputStream()
        BatchExporter.run(listOf(BatchJob("a", "Car A", { load() }, null)), ExportFormat.OBJ, bos)
        val n = zipNames(bos.toByteArray())
        assertTrue(n.any { it == "Car_A/Car_A.obj" } && n.any { it.startsWith("Car_A/danos/") && it.endsWith(".obj") }, n.toString())
    }

    @Test fun vehicleWithoutDamageExportsNormallyAndSaysSo() {
        val m = load()
        val noDam = ids(m) // el modelo sí tiene _dam; se simula pidiendo solo piezas sin gemelo dañado
        val onlyWheels = m.parts.filter { it.category == com.webmaletin.gtaviewer.model.PartCategory.WHEEL }.flatMap { p -> p.subMeshes.map { it.id } }.toSet()
        val out = VehicleExport.export(m, onlyWheels, emptySet(), PaintScheme(), 1f, "Wheels", ExportFormat.GLB, DamageMode.EMBEDDED)
        assertEquals("Wheels.glb", out.fileName)
        assertTrue(out.warnings.any { it.contains("no trae piezas dañadas") })
        assertTrue(noDam.isNotEmpty())
    }

    @Test fun batchPutsDamageInsideEachVehicle() {
        val dff = RwTestWriter.scaledVehicleDff(1f, 1)
        val jobs = listOf(BatchJob("a", "Car A", { load() }, null), BatchJob("b", "Car B", { load() }, null))
        val bos = java.io.ByteArrayOutputStream()
        val res = BatchExporter.run(jobs, ExportFormat.GLB, bos, damage = DamageMode.SEPARATE_ZIP)
        assertTrue(res.errors.isEmpty(), res.errors.toString())
        val n = zipNames(bos.toByteArray())
        assertTrue(n.containsAll(listOf("Car_A/Car_A.glb", "Car_A/Car_A_danos.glb", "Car_B/Car_B.glb", "Car_B/Car_B_danos.glb")), n.toString())
        val bos2 = java.io.ByteArrayOutputStream()
        BatchExporter.run(jobs, ExportFormat.GLB, bos2)
        assertEquals(listOf("Car_A.glb", "Car_B.glb"), zipNames(bos2.toByteArray()))
        assertTrue(dff.isNotEmpty())
    }
}
