package com.webmaletin.gtaviewer

import com.webmaletin.gtaviewer.catalog.*
import com.webmaletin.gtaviewer.editing.*
import com.webmaletin.gtaviewer.export.*
import com.webmaletin.gtaviewer.gta.GtaSaVehicleBuilder
import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.selection.SelectionTools
import com.webmaletin.gtaviewer.zip.MemorySource
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.test.*

class OutlinerBatchTest {
    private val txd = RwTestWriter.txd(listOf(
        RwTestWriter.TestTexture("body", 8, 8, 21, RwTestWriter.solidBgra(8, 8, 120, 10, 10)),
        RwTestWriter.TestTexture("glass", 8, 8, 21, RwTestWriter.solidBgra(8, 8, 90, 120, 200)),
        RwTestWriter.TestTexture("tyre", 8, 8, 21, RwTestWriter.solidBgra(8, 8, 20, 20, 20)),
    ))
    private fun load(name: String = "Test Car", dff: ByteArray = RwTestWriter.sampleVehicleDff(), txdBytes: ByteArray? = txd) =
        GtaSaVehicleBuilder().build(name, MemorySource("$name.dff", dff), txdBytes?.let { MemorySource("$name.txd", it) })

    private fun glbNodeNames(b: ByteArray): List<String> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); bb.position(12)
        val jl = bb.int; bb.int
        val json = JSONObject(String(b, 20, jl))
        val nodes = json.getJSONArray("nodes")
        return (1 until nodes.length()).map { nodes.getJSONObject(it).getString("name") }
    }

    // ---- visible = exportar ---------------------------------------------------------------

    @Test fun uncheckingWheelsRemovesThemFromViewAndGlb() {
        val m = load(); val s = EditSession(m)
        val wheels = SelectionTools.byCategory(m, PartCategory.WHEEL)
        s.setVisible(wheels, false)
        assertTrue(wheels.none { s.isVisible(it) })
        val scene = ExportSceneBuilder.build(m, s.visibleIds())
        val out = Exporters.forFormat(ExportFormat.GLB).export(scene, "x")
        val names = glbNodeNames(out.bytes)
        assertTrue(names.none { it.startsWith("Wheel") }, names.toString())
        assertTrue("chassis" in names && "door_lf_dummy" in names)
        // volver a marcar = vuelve (no se destruyó nada)
        s.setVisible(wheels, true)
        val again = glbNodeNames(Exporters.forFormat(ExportFormat.GLB).export(ExportSceneBuilder.build(m, s.visibleIds()), "x").bytes)
        assertEquals(4, again.count { it.startsWith("Wheel") })
    }

    @Test fun exportedPartsEqualVisiblePartsForAnyToggleCombination() {
        val m = load(); val s = EditSession(m)
        val rnd = java.util.Random(7)
        repeat(30) {
            val p = m.parts[rnd.nextInt(m.parts.size)]
            s.setVisible(p.subMeshes.map { it.id }.toSet(), rnd.nextBoolean())
            val scene = ExportSceneBuilder.build(m, s.visibleIds())
            val expected = m.parts.filter { p2 -> p2.subMeshes.any { s.isVisible(it.id) } }.map { it.name }.toSet()
            assertEquals(expected, scene.meshes.map { it.partName }.toSet())
            // y a nivel de sub-mesh: nº de mallas exportadas == nº de sub-meshes visibles
            assertEquals(s.visibleIds().size, scene.meshes.size)
        }
    }

    @Test fun outlinerButtons() {
        val m = load(); val s = EditSession(m)
        val total = m.subMeshes.size
        s.hideEverything(); assertEquals(0, s.visibleIds().size)
        s.showEverything(); assertEquals(total, s.visibleIds().size)
        s.setVisible(SelectionTools.byCategory(m, PartCategory.WHEEL), false)
        val before = s.visibleIds()
        s.invertVisibility(); assertEquals(total - before.size, s.visibleIds().size); assertTrue(s.visibleIds().none { it in before })
        s.restoreVisibility()
        assertFalse(s.isEdited())
        s.selectAll(); assertEquals(total, s.state.selection.size)
        s.clearSelection(); assertTrue(s.state.selection.isEmpty())
        val door = SelectionTools.byCategory(m, PartCategory.DOOR); s.select(door); s.isolate()
        assertEquals(door, s.visibleIds())
        assertTrue(s.undo()); assertFalse(s.visibleIds() == door)
    }

    @Test fun countersFollowVisibility() {
        val m = load(); val s = EditSession(m)
        var c = Outliner.counters(m, s.visibleIds())
        assertEquals(m.parts.size, c.partsTotal)
        assertEquals(m.parts.size - 1, c.partsVisible, "chassis_dam oculta por defecto")
        s.setVisible(SelectionTools.byCategory(m, PartCategory.WHEEL), false)
        val c2 = Outliner.counters(m, s.visibleIds())
        assertEquals(c.partsVisible - 4, c2.partsVisible)
        assertEquals(c.meshesVisible - 4, c2.meshesVisible)
    }

    // ---- agrupación honesta ---------------------------------------------------------------

    @Test fun groupsOnlyWhenThereIsRealInformationElseFlat() {
        val m = load()
        val t = Outliner.build(m, { it.category }, true)
        assertTrue(t.grouped)
        val wheels = t.groups.first { it.category == PartCategory.WHEEL }
        assertEquals(listOf("Rueda FL", "Rueda FR", "Rueda RL", "Rueda RR"), wheels.rows.map { it.displayName })
        // DFF sin nombres: lista plana "Mesh 001..."
        val anon = RwTestWriter.dff(
            List(4) { RwTestWriter.TestFrame(null, if (it == 0) -1 else 0) },
            List(3) { RwTestWriter.box(0f, 0f, it.toFloat(), 1f, 1f, 1f, listOf(RwTestWriter.TestMaterial(255, 255, 255, 255, "body"))) },
            List(3) { RwTestWriter.TestAtomic(it + 1, it) })
        val m2 = load("Anon", anon)
        assertEquals(listOf("Mesh 001", "Mesh 002", "Mesh 003"), m2.parts.map { it.name })
        val t2 = Outliner.build(m2, { it.category }, true)
        assertFalse(t2.grouped); assertEquals(3, t2.allRows.size)
        assertTrue(t2.groups.isEmpty())
    }

    @Test fun nameAndTextureHeuristicsProposeWheelsAndUserCanOverride() {
        fun f(n: String?, i: Int) = RwTestWriter.TestFrame(n, if (i == 0) -1 else 0)
        val dff = RwTestWriter.dff(
            listOf(f(null, 0), f("chassis", 1), f("tyre_front", 2), f("rim_a", 3), f(null, 4), f(null, 5)),
            listOf(
                RwTestWriter.box(0f, 0f, 0f, 2f, 4f, 1f, listOf(RwTestWriter.TestMaterial(255, 255, 255, 255, "body"))),
                RwTestWriter.box(1f, 1f, 0f, .3f, .6f, .6f, listOf(RwTestWriter.TestMaterial(1, 1, 1))),
                RwTestWriter.box(-1f, 1f, 0f, .3f, .6f, .6f, listOf(RwTestWriter.TestMaterial(1, 1, 1))),
                RwTestWriter.box(1f, -1f, 0f, .3f, .6f, .6f, listOf(RwTestWriter.TestMaterial(1, 1, 1, 255, "tyre"))),   // sin nombre, textura 'tyre'
                RwTestWriter.box(0f, 0f, 1f, 1f, 1f, .2f, listOf(RwTestWriter.TestMaterial(1, 1, 1, 255, "mystery"))),
            ),
            listOf(1, 2, 3, 4, 5).mapIndexed { i, fr -> RwTestWriter.TestAtomic(fr, i) })
        val m = load("Odd", dff)
        assertEquals(PartCategory.WHEEL, m.parts.first { it.name == "tyre_front" }.category)
        assertEquals(PartCategory.WHEEL, m.parts.first { it.name == "rim_a" }.category)
        val unnamed = m.parts.filter { it.frameName == null }
        val byTex = unnamed[0]
        assertEquals(PartCategory.WHEEL, byTex.category); assertFalse(byTex.recognized, "propuesta por textura, no reconocida por frame")
        val mystery = unnamed[1]
        assertEquals(PartCategory.OTHER, mystery.category)
        // el usuario manda: reclasifica 'mystery' como rueda y 'rim_a' como otra cosa
        val s = EditSession(m)
        s.setCategory(setOf(mystery.id), PartCategory.WHEEL)
        s.setCategory(setOf(m.parts.first { it.name == "rim_a" }.id), PartCategory.OTHER)
        val wheels = GamePrep.plan(s, PrepOptions()).wheels.map { it.name }
        assertTrue(mystery.name in wheels && "rim_a" !in wheels, wheels.toString())
        assertTrue(s.isEdited())
    }

    // ---- preparar para mi juego -----------------------------------------------------------

    @Test fun prepareForGameHidesOnlyWheelsByDefault() {
        val m = load(); val s = EditSession(m)
        val r = GamePrep.apply(s, PrepOptions())
        assertEquals(4, r.wheels.size)
        val hiddenNames = m.parts.filter { p -> p.subMeshes.all { !s.isVisible(it.id) } }.map { it.name }.toSet()
        assertEquals(setOf("Wheel FL", "Wheel FR", "Wheel RL", "Wheel RR", "chassis_dam"), hiddenNames)
        // deshacible y reversible; el modelo original no cambia
        assertTrue(s.undo()); assertEquals(m.parts.size - 1, Outliner.counters(m, s.visibleIds()).partsVisible)
        // opciones extra
        val s2 = EditSession(m)
        GamePrep.apply(s2, PrepOptions(removeWheels = false, removeOther = true))
        assertTrue(m.parts.filter { it.category == PartCategory.OTHER }.all { p -> p.subMeshes.none { s2.isVisible(it.id) } })
        assertTrue(m.parts.filter { it.category == PartCategory.WHEEL }.all { p -> p.subMeshes.all { s2.isVisible(it.id) } })
    }

    @Test fun noWheelsDetectedProducesANoteAndHidesNothing() {
        val m = load("NoWheels", RwTestWriter.dff(listOf(RwTestWriter.TestFrame(null, -1), RwTestWriter.TestFrame("chassis", 0)),
            listOf(RwTestWriter.box(0f, 0f, 0f, 1f, 2f, 1f, listOf(RwTestWriter.TestMaterial(255, 255, 255)))), listOf(RwTestWriter.TestAtomic(1, 0))))
        val s = EditSession(m)
        val r = GamePrep.apply(s, PrepOptions())
        assertTrue(r.wheels.isEmpty()); assertTrue(r.notes.isNotEmpty())
        assertFalse(s.isEdited())
    }

    // ---- configuración por vehículo -------------------------------------------------------

    @Test fun configRoundTripAndIsolationBetweenVehicles() {
        val a = load("Audi A3"); val b = load("BMW M4", RwTestWriter.sampleVehicleDff())
        val sa = EditSession(a); GamePrep.apply(sa, PrepOptions())
        val cfgA = VehicleConfig.capture(a, sa.state)
        val sb = EditSession(b); sb.setVisible(SelectionTools.partSubs(b, b.parts.first { it.name == "bonnet_dummy" }.id), false)
        val cfgB = VehicleConfig.capture(b, sb.state)

        // recarga "desde cero" (ids nuevos) y restaura
        val a2 = load("Audi A3"); val ra = VehicleConfig.sessionFor(a2, cfgA)
        assertTrue(a2.parts.filter { it.category == PartCategory.WHEEL }.all { p -> p.subMeshes.none { ra.isVisible(it.id) } })
        assertTrue(a2.parts.first { it.name == "bonnet_dummy" }.subMeshes.all { ra.isVisible(it.id) }, "A3 conserva el capó")
        val b2 = load("BMW M4"); val rb = VehicleConfig.sessionFor(b2, cfgB)
        assertTrue(b2.parts.filter { it.category == PartCategory.WHEEL }.all { p -> p.subMeshes.all { rb.isVisible(it.id) } }, "M4 conserva sus ruedas")
        assertTrue(b2.parts.first { it.name == "bonnet_dummy" }.subMeshes.none { rb.isVisible(it.id) })
        assertNotEquals(cfgA, cfgB)
        // sin config = por defecto
        assertEquals(EditSession(a2).visibleIds().size, VehicleConfig.sessionFor(a2, null).visibleIds().size)
    }

    @Test fun configKeepsCategoryOverrides() {
        val m = load(); val s = EditSession(m)
        val door = m.parts.first { it.name == "door_lf_dummy" }
        s.setCategory(setOf(door.id), PartCategory.WHEEL)
        val cfg = VehicleConfig.capture(m, s.state)
        val m2 = load(); val s2 = VehicleConfig.sessionFor(m2, cfg)
        assertEquals(PartCategory.WHEEL, s2.categoryOf(m2.parts.first { it.name == "door_lf_dummy" }))
    }

    @Test fun compatibilityRules() {
        val z = com.webmaletin.gtaviewer.zip.ZipArchive.open(0, java.io.File.createTempFile("cmp", ".zip").also {
            it.deleteOnExit(); it.writeBytes(RwTestWriter.zip(mapOf(
                "ok/ok.dff" to RwTestWriter.sampleVehicleDff(), "ok/ok.txd" to txd,
                "notxd/notxd.dff" to RwTestWriter.sampleVehicleDff(), "bad/bad.dff" to ByteArray(80) { 3 }, "bad/bad.txd" to txd)))
        })
        val r = FormatRegistry.default.scan(z.entries)
        fun c(n: String) = CompatibilityRules.of(r.vehicles.first { it.rawName == n })
        assertTrue(c("ok").ok); assertFalse(c("notxd").ok); assertEquals("TXD faltante", c("notxd").reason)
        assertFalse(c("bad").ok)
        z.close()
    }

    // ---- exportación masiva ---------------------------------------------------------------

    private fun unzip(b: ByteArray): Map<String, ByteArray> {
        val r = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(b)).use { z -> while (true) { val e = z.nextEntry ?: break; r[e.name] = z.readBytes() } }
        return r
    }

    @Test fun batchExportsEachVehicleWithItsOwnConfigOneByOneAndSurvivesErrors() {
        val probe = load()
        val cfgNoWheels = VehicleConfig.capture(probe, EditSession(probe).also { GamePrep.apply(it, PrepOptions()) }.state)
        val cfgNoDoorNoBonnet = VehicleConfig.capture(probe, EditSession(probe).also { s ->
            s.setVisible(SelectionTools.byCategory(probe, PartCategory.DOOR) + SelectionTools.byCategory(probe, PartCategory.HOOD), false) }.state)
        var loaded = 0; var maxAlive = 0; var alive = 0
        fun job(id: String, name: String, cfg: VehicleConfig?, dff: ByteArray = RwTestWriter.sampleVehicleDff(), t: ByteArray? = txd) =
            BatchJob(id, name, { loaded++; alive++; maxAlive = maxOf(maxAlive, alive); try { load(name, dff, t) } finally { alive-- } }, cfg)
        val jobs = listOf(
            job("1", "Audi A3", cfgNoWheels),
            job("2", "BMW M4 GTS", cfgNoDoorNoBonnet),
            job("3", "Golf GTI", cfgNoWheels, t = ByteArray(40) { 1 }),         // TXD incompatible
            job("4", "Ferrari F40", null, dff = ByteArray(100) { 9 }),           // DFF inválido
            job("5", "Audi A3", null),                                            // mismo nombre -> archivo único
        )
        val bos = ByteArrayOutputStream(); val progress = ArrayList<BatchProgress>()
        val res = BatchExporter.run(jobs, ExportFormat.GLB, bos, onProgress = { progress += it })
        assertEquals(5, res.total); assertEquals(3, res.okCount); assertEquals(2, res.errors.size)
        assertEquals(setOf("Golf GTI", "Ferrari F40"), res.errors.map { it.first }.toSet())
        assertTrue(res.errors.first { it.first == "Golf GTI" }.second.contains("TXD incompatible"), res.errors.toString())
        assertEquals(5, loaded)
        val files = unzip(bos.toByteArray())
        assertEquals(listOf("Audi_A3.glb", "BMW_M4_GTS.glb", "Audi_A3_2.glb"), files.keys.toList(), "solo GLB correctos, nombres humanos")
        val a = glbNodeNames(files["Audi_A3.glb"]!!); val b = glbNodeNames(files["BMW_M4_GTS.glb"]!!); val c = glbNodeNames(files["Audi_A3_2.glb"]!!)
        assertTrue(a.none { it.startsWith("Wheel") } && "door_lf_dummy" in a && "bonnet_dummy" in a)
        assertTrue(b.count { it.startsWith("Wheel") } == 4 && "door_lf_dummy" !in b && "bonnet_dummy" !in b && "chassis" in b)
        assertEquals(4, c.count { it.startsWith("Wheel") }, "sin config = todo lo visible por defecto")
        assertTrue(progress.any { it.state == BatchState.ERROR && it.name == "Golf GTI" })
        assertEquals(5, progress.count { it.state == BatchState.PROCESSING })
        assertEquals(1, maxAlive, "nunca más de un modelo en memoria a la vez durante la carga")
    }

    @Test fun batchObjFbxPutEachVehicleInItsFolder() {
        val res = ByteArrayOutputStream()
        val r = BatchExporter.run(listOf(BatchJob("1", "Audi A3", { load("Audi A3") }, null)), ExportFormat.OBJ, res)
        assertEquals(1, r.okCount)
        val f = unzip(res.toByteArray()).keys
        assertTrue("Audi_A3/Audi_A3.obj" in f && "Audi_A3/Audi_A3.mtl" in f && f.any { it.startsWith("Audi_A3/textures/") })
    }

    @Test fun batchCanBeCancelledKeepingFinishedOnes() {
        var n = 0
        val jobs = (1..4).map { i -> BatchJob("$i", "Car $i", { load("Car $i") }, null) }
        val bos = ByteArrayOutputStream()
        val r = BatchExporter.run(jobs, ExportFormat.GLB, bos, isCancelled = { n++ >= 2 })
        assertTrue(r.cancelled); assertEquals(2, r.okCount)
        assertEquals(2, unzip(bos.toByteArray()).size)
    }

    @Test fun fileNamesStayHuman() {
        assertEquals("Audi_A3", ExportNaming.fileSafe("Audi A3"))
        assertEquals("BMW_M4_GTS", ExportNaming.fileSafe("BMW M4 GTS"))
        assertEquals("Chevrolet_Camaro", ExportNaming.fileSafe("Chevrolet Camaro"))
        assertEquals("AC_DC_Truck", ExportNaming.fileSafe("AC/DC: Truck"))
    }
}
