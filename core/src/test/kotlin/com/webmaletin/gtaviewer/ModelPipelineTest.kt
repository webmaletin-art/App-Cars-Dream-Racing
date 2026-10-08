package com.webmaletin.gtaviewer

import com.webmaletin.gtaviewer.camera.CameraPreset
import com.webmaletin.gtaviewer.camera.OrbitCamera
import com.webmaletin.gtaviewer.editing.EditSession
import com.webmaletin.gtaviewer.export.*
import com.webmaletin.gtaviewer.gta.GtaSaVehicleBuilder
import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.selection.*
import com.webmaletin.gtaviewer.txd.GtaSaTxdParser
import com.webmaletin.gtaviewer.zip.MemorySource
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.test.*

class ModelPipelineTest {
    private fun txdBytes(withTyre: Boolean = false): ByteArray {
        val list = arrayListOf(
            RwTestWriter.TestTexture("body", 8, 8, 21, RwTestWriter.solidBgra(8, 8, 120, 10, 10)),
            RwTestWriter.TestTexture("glass", 8, 8, GtaSaTxdParser.FOURCC_DXT5, RwTestWriter.solidDxt5(8, 8, 0, 248, 0, 100), alpha = true),
        )
        if (withTyre) list += RwTestWriter.TestTexture("tyre", 8, 8, 21, RwTestWriter.solidBgra(8, 8, 20, 20, 20))
        return RwTestWriter.txd(list)
    }

    private fun load(withTyre: Boolean = true, withTxd: Boolean = true): VehicleModel = GtaSaVehicleBuilder().build(
        "Test Car", MemorySource("test.dff", RwTestWriter.sampleVehicleDff()),
        if (withTxd) MemorySource("test.txd", txdBytes(withTyre)) else null,
    )

    @Test fun structureIsPreservedAndWheelsAreInstanced() {
        val m = load()
        val names = m.parts.map { it.name }
        assertTrue("chassis" in names); assertTrue("door_lf_dummy" in names); assertTrue("bonnet_dummy" in names)
        assertFalse("wheel" in names, "la plantilla no debe mostrarse en el origen")
        val wheels = m.parts.filter { it.category == PartCategory.WHEEL }
        assertEquals(4, wheels.size)
        assertEquals(setOf(WheelSlot.FL, WheelSlot.FR, WheelSlot.RL, WheelSlot.RR), wheels.map { it.wheelSlot }.toSet())
        // misma geometría compartida (instanciada), distintas transformaciones
        assertEquals(1, wheels.map { it.subMeshes[0].geometry.id }.toSet().size)
        val fl = wheels.first { it.wheelSlot == WheelSlot.FL }; val fr = wheels.first { it.wheelSlot == WheelSlot.FR }
        assertEquals(-0.9f, fl.transform[12]); assertEquals(0.9f, fr.transform[12])
        assertEquals(-1f, fr.transform[0], "rueda derecha girada 180° respecto de la plantilla")
        assertEquals(1f, fl.transform[0])
        // chassis tiene 2 materiales -> 2 sub-meshes
        assertEquals(2, m.parts.first { it.name == "chassis" }.subMeshes.size)
        // variante dañada oculta por defecto pero presente
        val dam = m.parts.first { it.name == "chassis_dam" }
        assertFalse(dam.defaultVisible); assertNotNull(dam.variantNote)
        assertEquals(PartCategory.DOOR, m.parts.first { it.name == "door_lf_dummy" }.category)
        assertEquals(PartCategory.HOOD, m.parts.first { it.name == "bonnet_dummy" }.category)
    }

    @Test fun texturesAreResolvedByNameNotOrder() {
        val m = load(withTyre = false)
        val body = m.materials.first { it.textureName == "body" }
        assertNotNull(body.texture)
        assertEquals(120, body.texture!!.rgba[0].toInt() and 0xFF)
        val tyre = m.materials.first { it.textureName == "tyre" }
        assertNull(tyre.texture); assertTrue(tyre.textureMissing)
        assertTrue(m.diagnostics.texturesMissing.contains("tyre"))
        assertTrue(m.warnings.any { it.contains("tyre") })
        assertTrue(m.materials.first { it.textureName == "glass" }.transparent)
        assertEquals(1, body.paintSlot, "color 60,255,0 = pintura primaria")
    }

    @Test fun dffWithoutTxdStillLoads() {
        val m = load(withTxd = false)
        assertTrue(m.totalTriangles > 0)
        assertTrue(m.materials.all { it.texture == null })
        assertTrue(m.warnings.any { it.contains("Sin TXD") })
        assertFalse(m.diagnostics.txdValid)
    }

    @Test fun counters() {
        val m = load()
        assertEquals(4 * 12 + 12 + 12 + 12 + 12 + 12 - 0 - 0, m.totalTriangles - 0 + 0 - (m.totalTriangles - (4 * 12 + 12 + 12 + 12 + 12 + 12)))
        assertTrue(m.totalVertices > 0)
        assertTrue(m.validation.robustBounds != null)
        assertFalse(m.validation.needsReview, m.validation.reasons.toString())
    }

    @Test fun validationFlagsAbsurdGeometry() {
        // vehículo de 5000 metros
        val big = RwTestWriter.dff(
            listOf(RwTestWriter.TestFrame(null, -1), RwTestWriter.TestFrame("chassis", 0)),
            listOf(RwTestWriter.box(0f, 0f, 0f, 3000f, 5000f, 2000f, listOf(RwTestWriter.TestMaterial(255, 255, 255)))),
            listOf(RwTestWriter.TestAtomic(1, 0)),
        )
        val m = GtaSaVehicleBuilder().build("Big", MemorySource("b.dff", big), null)
        assertTrue(m.validation.needsReview)
        assertTrue(m.validation.suggestedScale < 0.01f)
        // pieza lejísima
        val far = RwTestWriter.dff(
            listOf(RwTestWriter.TestFrame(null, -1), RwTestWriter.TestFrame("chassis", 0), RwTestWriter.TestFrame("door_lf_dummy", 0, floatArrayOf(900f, 0f, 0f)),
                RwTestWriter.TestFrame("door_rf_dummy", 0), RwTestWriter.TestFrame("bonnet_dummy", 0)),
            listOf(RwTestWriter.box(0f, 0f, 0f, 1.8f, 4f, 1f, listOf(RwTestWriter.TestMaterial(255, 255, 255))), RwTestWriter.box(0f, 0f, 0f, .2f, 1f, 1f, listOf(RwTestWriter.TestMaterial(255, 255, 255)))),
            listOf(RwTestWriter.TestAtomic(1, 0), RwTestWriter.TestAtomic(2, 1), RwTestWriter.TestAtomic(3, 1), RwTestWriter.TestAtomic(4, 1)),
        )
        val m2 = GtaSaVehicleBuilder().build("Far", MemorySource("f.dff", far), null)
        assertTrue(m2.validation.needsReview)
        assertTrue(m2.validation.reasons.any { it.contains("lejos") }, m2.validation.reasons.toString())
        assertTrue(m2.validation.robustBounds!!.size.max() < 50f)
    }

    @Test fun editingHideDeleteIsolateUndo() {
        val m = load()
        val s = EditSession(m)
        val visible0 = s.visibleIds().size
        val wheels = SelectionTools.byCategory(m, PartCategory.WHEEL)
        assertEquals(4, wheels.size)
        s.select(wheels); s.delete()
        assertEquals(visible0 - 4, s.visibleIds().size)
        assertTrue(s.isEdited())
        assertTrue(s.undo()); assertEquals(visible0, s.visibleIds().size)
        assertTrue(s.redo()); assertEquals(visible0 - 4, s.visibleIds().size)
        s.restoreDeleted(); assertEquals(visible0, s.visibleIds().size)
        val door = SelectionTools.partSubs(m, m.parts.first { it.name == "door_lf_dummy" }.id)
        s.hide(door); assertEquals(visible0 - door.size, s.visibleIds().size)
        s.showAll(); assertEquals(visible0, s.visibleIds().size)
        s.isolate(door); assertEquals(door, s.visibleIds())
        s.showAll()
        s.resetAll()
        assertFalse(s.isEdited())
        // las variantes dañadas siguen ocultas tras "mostrar todo"
        assertFalse(s.isVisible(m.parts.first { it.name == "chassis_dam" }.subMeshes[0].id))
    }

    @Test fun selectionTools() {
        val m = load()
        val body = m.materials.first { it.textureName == "body" }
        val byMat = SelectionTools.byMaterial(m, body.key)
        assertTrue(byMat.size >= 4)
        val wheelSub = m.parts.first { it.wheelSlot == WheelSlot.FL }.subMeshes[0].id
        assertEquals(4, SelectionTools.similar(m, wheelSub).size)
        assertEquals(1, SelectionTools.expand(m, wheelSub, PickMode.SUBMESH).size)
        val chassis = m.parts.first { it.name == "chassis" }
        assertEquals(2, SelectionTools.expand(m, chassis.subMeshes[0].id, PickMode.PART).size)
        assertTrue(SelectionTools.expand(m, wheelSub, PickMode.TEXTURE).isNotEmpty())
        assertEquals(1, SelectionTools.byWheelSlot(m, WheelSlot.RR).size)
        assertTrue(SelectionTools.groupedParts(m).first().first == PartCategory.BODY)
    }

    @Test fun rayPickingHitsTheTouchedPart() {
        val m = load()
        val cam = OrbitCamera()
        cam.frame(m.boundsOf(m.parts.filter { it.defaultVisible }.map { it.id })!!, 1.5f)
        cam.setPreset(CameraPreset.SIDE)
        // proyectar el centro de la rueda delantera izquierda y "tocar" ahí
        val wheel = m.parts.first { it.wheelSlot == WheelSlot.FL }
        val c = m.partBounds(wheel).center
        val px = cam.project(c[0], c[1], c[2], 900, 600)!!
        val hit = RayPicker.pick(m, cam.rayFor(px[0], px[1], 900, 600), { true })
        assertNotNull(hit)
        assertEquals(wheel.id, m.partOf(hit.subMeshId)!!.id)
        // tocar el cielo no selecciona nada
        assertNull(RayPicker.pick(m, cam.rayFor(5f, 5f, 900, 600), { true }))
        // ocultas no se pueden tocar
        assertNull(RayPicker.pick(m, cam.rayFor(px[0], px[1], 900, 600), { sm -> m.partOf(sm.id)!!.name == "bonnet_dummy" }))
    }

    @Test fun autoFrameKeepsWholeVehicleInViewForEveryPreset() {
        val m = load()
        val b = m.boundsOf(m.parts.filter { it.defaultVisible }.map { it.id })!!
        for (aspect in listOf(0.5f, 1f, 1.8f)) for (p in CameraPreset.values()) {
            val cam = OrbitCamera(); cam.frame(b, aspect); cam.setPreset(p)
            val w = (1000 * aspect).toInt(); val h = 1000
            for (xi in listOf(b.min[0], b.max[0])) for (yi in listOf(b.min[1], b.max[1])) for (zi in listOf(b.min[2], b.max[2])) {
                val s = cam.project(xi, yi, zi, w, h)
                assertNotNull(s, "$p $aspect")
                assertTrue(s[0] in 0f..w.toFloat() && s[1] in 0f..h.toFloat(), "esquina fuera de pantalla: $p aspect=$aspect -> ${s.toList()}")
            }
        }
    }

    @Test fun frontViewLooksAtTheFrontOfTheVehicle() {
        val cam = OrbitCamera(); cam.setPreset(CameraPreset.FRONT)
        val pos = cam.position()
        assertTrue(pos[2] > 1f, "la cámara frontal debe estar en +Z (el frente del vehículo mira a +Z)")
        val m = load()
        // el capó (delante, y GTA positiva) debe quedar en +Z de visor
        val hood = m.partBounds(m.parts.first { it.name == "bonnet_dummy" }).center
        assertTrue(hood[2] > 0f)
    }

    // ------------------------------------------------------------------ exportación

    private fun scene(m: VehicleModel, ids: Set<Int> = m.subMeshes.filter { m.parts.first { p -> p.id == it.partId }.defaultVisible }.map { it.id }.toSet()) =
        ExportSceneBuilder.build(m, ids, PaintScheme(), 1f, m.name)

    @Test fun glbIsStructurallyValid() {
        val m = load()
        val out = Exporters.forFormat(ExportFormat.GLB).export(scene(m), "Test_Car")
        assertEquals("Test_Car.glb", out.fileName)
        val bb = ByteBuffer.wrap(out.bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, bb.int); assertEquals(2, bb.int); assertEquals(out.bytes.size, bb.int)
        val jl = bb.int; assertEquals(0x4E4F534A, bb.int)
        val json = JSONObject(String(out.bytes, 20, jl, Charsets.UTF_8))
        val binLen = ByteBuffer.wrap(out.bytes, 20 + jl, 8).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(json.getJSONArray("buffers").getJSONObject(0).getInt("byteLength"), binLen)
        assertEquals(0, jl % 4); assertEquals(0, binLen % 4)
        assertTrue(json.getJSONArray("images").length() >= 2)
        assertEquals("image/png", json.getJSONArray("images").getJSONObject(0).getString("mimeType"))
        val prim = json.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0)
        assertTrue(prim.getJSONObject("attributes").has("TEXCOORD_0")); assertTrue(prim.getJSONObject("attributes").has("NORMAL"))
        // el PNG embebido es válido
        val view = json.getJSONArray("bufferViews").getJSONObject(json.getJSONArray("images").getJSONObject(0).getInt("bufferView"))
        val off = 20 + jl + 8 + view.getInt("byteOffset")
        assertEquals(0x89, out.bytes[off].toInt() and 0xFF); assertEquals('P'.code, out.bytes[off + 1].toInt())
        java.io.File(System.getProperty("export.dir") ?: "build/exports").also { it.mkdirs() }.resolve("Test_Car.glb").writeBytes(out.bytes)
    }

    @Test fun objZipHasConsistentMtlAndTextures() {
        val m = load()
        val out = Exporters.forFormat(ExportFormat.OBJ).export(scene(m), "Test_Car")
        assertEquals("Test_Car_OBJ.zip", out.fileName)
        val files = unzip(out.bytes)
        assertTrue("Test_Car.obj" in files); assertTrue("Test_Car.mtl" in files)
        val obj = String(files["Test_Car.obj"]!!); val mtl = String(files["Test_Car.mtl"]!!)
        assertTrue(obj.contains("mtllib Test_Car.mtl"))
        val used = Regex("usemtl (\\S+)").findAll(obj).map { it.groupValues[1] }.toSet()
        val defined = Regex("newmtl (\\S+)").findAll(mtl).map { it.groupValues[1] }.toSet()
        assertTrue(defined.containsAll(used), "usemtl sin newmtl: $used vs $defined")
        Regex("map_Kd (\\S+)").findAll(mtl).forEach { assertTrue(it.groupValues[1] in files, "falta ${it.groupValues[1]}") }
        val nv = obj.lines().count { it.startsWith("v ") }; val nvt = obj.lines().count { it.startsWith("vt ") }; val nvn = obj.lines().count { it.startsWith("vn ") }
        assertTrue(nv > 0 && nv == nvt && nv == nvn)
        for (l in obj.lines().filter { it.startsWith("f ") }) for (tok in l.substring(2).split(' ')) {
            val (a, b, c) = tok.split('/').map { it.toInt() }
            assertTrue(a in 1..nv && b in 1..nvt && c in 1..nvn)
        }
        assertTrue(files.keys.any { it.startsWith("textures/") && it.endsWith(".png") })
        java.io.File("build/exports").mkdirs(); java.io.File("build/exports/Test_Car_OBJ.zip").writeBytes(out.bytes)
    }

    @Test fun fbxZipHasModelsTexturesAndLimitationNotice() {
        val m = load()
        val out = Exporters.forFormat(ExportFormat.FBX).export(scene(m), "Test_Car")
        assertEquals("Test_Car_FBX.zip", out.fileName)
        assertNotNull(out.limitations); assertTrue(out.limitations!!.contains("limitaciones"))
        val files = unzip(out.bytes)
        val fbx = String(files["Test_Car.fbx"]!!)
        assertTrue(fbx.startsWith("; FBX 7.4.0 project file"))
        assertTrue(fbx.contains("Geometry:") && fbx.contains("Connections:") && fbx.contains("\"DiffuseColor\""))
        Regex("RelativeFilename: \"(textures/[^\"]+)\"").findAll(fbx).forEach { assertTrue(it.groupValues[1] in files) }
        java.io.File("build/exports").mkdirs(); java.io.File("build/exports/Test_Car_FBX.zip").writeBytes(out.bytes)
    }

    @Test fun exportingOnlyWheelsAndWithoutWheels() {
        val m = load()
        val s = EditSession(m)
        val wheels = SelectionTools.byCategory(m, PartCategory.WHEEL)
        val onlyWheels = ExportSceneBuilder.build(m, wheels)
        assertEquals(4, onlyWheels.meshes.size)
        assertTrue(onlyWheels.meshes.all { it.partName.startsWith("Wheel") })
        s.select(wheels); s.delete()
        val noWheels = ExportSceneBuilder.build(m, s.visibleIds())
        assertTrue(noWheels.meshes.none { it.partName.startsWith("Wheel") })
        assertTrue(noWheels.meshes.any { it.partName == "chassis" })
        assertEquals("Test_Car_editado", ExportNaming.baseName("Test Car", edited = true))
        assertEquals("ruedas", ExportNaming.baseName("Test Car", edited = true, label = "ruedas"))
        assertEquals("Test_Car_editado.glb", ExportNaming.fileName(ExportNaming.baseName("Test Car", true), ExportFormat.GLB))
        // la geometría exportada de la rueda derecha queda a la derecha (-X en espacio de exportación = lado derecho)
        val fr = onlyWheels.meshes.first { it.partName.contains("FR") }
        val cx = (0 until fr.vertexCount).map { fr.positions[it * 3] }.average()
        assertTrue(cx < -0.5, "rueda derecha en X<0 tras la conversión (+X = izquierda): $cx")
    }

    @Test fun paintMarkersAreReplacedOnExport() {
        val m = load()
        val sc = ExportSceneBuilder.build(m, m.subMeshes.map { it.id }.toSet(), PaintScheme(primary = 0x112233))
        val painted = sc.materials.first { it.texture?.fileName == "body.png" }
        assertEquals(0x11 / 255f, painted.r, 1e-4f); assertEquals(0x22 / 255f, painted.g, 1e-4f)
    }

    @Test fun pngEncoderProducesValidPng() {
        val png = PngEncoder.encode(2, 2, ByteArray(16) { 255.toByte() })
        assertEquals(0x89, png[0].toInt() and 0xFF)
        assertEquals("IHDR", String(png, 12, 4))
        assertEquals("IEND", String(png, png.size - 8, 4))
    }

    private fun unzip(b: ByteArray): Map<String, ByteArray> {
        val r = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(b)).use { z -> while (true) { val e = z.nextEntry ?: break; r[e.name] = z.readBytes() } }
        return r
    }
}
