package com.webmaletin.gtaviewer

import com.webmaletin.gtaviewer.beamng.*
import com.webmaletin.gtaviewer.export.*
import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.zip.ArchiveEntry
import com.webmaletin.gtaviewer.zip.MemorySource
import com.webmaletin.gtaviewer.zip.ZipArchive
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.test.*

class BeamNgTest {
    // ---------------------------------------------------------------------------------------------- formatos

    @Test fun lenientJsonAcceptsCommentsTrailingCommasAndMissingCommas() {
        val v = LenientJson.asMap(LenientJson.parse("""
            { // comentario
              "a": [1, 2, 3,],   /* otro */
              "rows": [
                ["id", "x"]
                ["n1", 1.5]
                ["n2", -2e1],
              ]
              "b": {"c": true, "d": null}
              "soundNode:":["f15"]
            }
        """.trimIndent()))
        assertEquals(listOf(1.0, 2.0, 3.0), LenientJson.asList(v["a"]))
        assertEquals(3, LenientJson.asList(v["rows"]).size)
        assertEquals(-20.0, LenientJson.num(LenientJson.asList(LenientJson.asList(v["rows"])[2])[1]))
        assertEquals(true, LenientJson.asMap(v["b"])["c"]); assertEquals(listOf("f15"), LenientJson.asList(v["soundNode:"]))
    }

    @Test fun jbeamTablesApplyModifiersToFollowingRows() {
        val parts = Jbeam.parseFile("""{"p1":{"slotType":"main","information":{"name":"Principal"},
            "slots":[["type","default","description"],["wheel","w1","Rueda",{"nodeOffset":{"x":0.5,"y":-1.0,"z":0.3}}],["opt","","Nada"]],
            "nodes":[["id","posX","posY","posZ"],{"nodeWeight":7.5},{"group":"hub"},["a",0,0,0],["b",1,0,0,{"nodeWeight":9}]],
            "beams":[["id1:","id2:"],{"beamSpring":1000,"beamDamp":5},["a","b"]],
            "flexbodies":[["mesh","[group]:","nonFlexMaterials"],["m1",["g"],[],{"pos":{"x":1,"y":2,"z":3},"rot":{"x":0,"y":0,"z":180}}]]},
            "w1":{"slotType":"wheel","nodes":[["id","posX","posY","posZ"],["w",0,0,0]]}}""", "t.jbeam")
        val p = parts.first { it.name == "p1" }
        assertEquals(listOf(7.5, 9.0), p.nodes.map { it.weight }); assertEquals("hub", p.nodes[0].group)
        assertEquals(1000.0, p.beams.single().spring)
        assertEquals(listOf(1.0, 2.0, 3.0), p.flexbodies.single().pos.toList()); assertEquals(180.0, p.flexbodies.single().rot[2])
        assertEquals(0.5, p.slots[0].nodeOffset[0])
        val (act, notes) = BeamConfigResolver.resolve(p, parts.associateBy { it.name }, emptyMap())
        assertEquals(listOf("p1", "w1"), act.map { it.part.name }); assertEquals(-1.0, act[1].offset[1]); assertTrue(notes.isEmpty())
    }

    private val dae = """<?xml version="1.0" encoding="utf-8"?>
<COLLADA xmlns="http://www.collada.org/2005/11/COLLADASchema" version="1.4.1"><asset><unit name="meter" meter="1"/><up_axis>Z_UP</up_axis></asset>
<library_effects><effect id="e1"><profile_COMMON><technique sid="common"><lambert><diffuse><color sid="diffuse">0.5 0.25 0.125 1</color></diffuse></lambert></technique></profile_COMMON></effect></library_effects>
<library_materials><material id="m1-material" name="paint"><instance_effect url="#e1"/></material></library_materials>
<library_geometries>
 <geometry id="g1" name="quad"><mesh>
  <source id="g1-pos"><float_array id="g1-pos-array" count="12">0 0 0 1 0 0 1 1 0 0 1 0</float_array><technique_common><accessor source="#g1-pos-array" count="4" stride="3"/></technique_common></source>
  <source id="g1-nor"><float_array id="g1-nor-array" count="3">0 0 1</float_array><technique_common><accessor source="#g1-nor-array" count="1" stride="3"/></technique_common></source>
  <source id="g1-uv"><float_array id="g1-uv-array" count="8">0 0 1 0 1 1 0 1</float_array><technique_common><accessor source="#g1-uv-array" count="4" stride="2"/></technique_common></source>
  <vertices id="g1-v"><input semantic="POSITION" source="#g1-pos"/></vertices>
  <polylist material="m1-material" count="1"><input semantic="VERTEX" source="#g1-v" offset="0"/><input semantic="NORMAL" source="#g1-nor" offset="1"/><input semantic="TEXCOORD" source="#g1-uv" offset="2" set="0"/>
   <vcount>4</vcount><p>0 0 0 1 0 1 2 0 2 3 0 3</p></polylist></mesh></geometry>
</library_geometries>
<library_visual_scenes><visual_scene id="S"><node id="n1" name="car_body"><matrix sid="transform">1 0 0 2 0 1 0 0 0 0 1 0 0 0 0 1</matrix>
 <instance_geometry url="#g1"><bind_material><technique_common><instance_material symbol="m1-material" target="#m1-material"/></technique_common></bind_material></instance_geometry></node></visual_scene></library_visual_scenes></COLLADA>"""

    @Test fun colladaStreamsSceneAndTriangulatesPolylists() {
        val sc = Collada.readScene(ByteArrayInputStream(dae.toByteArray()))
        assertEquals("Z_UP", sc.upAxis); assertEquals(1, sc.nodes.size)
        assertEquals("car_body", sc.nodes[0].name); assertEquals(2f, sc.nodes[0].world[12])        // traslación en la columna 4
        assertEquals("paint", sc.materialNames["m1-material"]); assertEquals(0.25f, sc.materialColors["m1-material"]!![1])
        val g = Collada.readGeometries(ByteArrayInputStream(dae.toByteArray()), setOf("g1")).getValue("g1")
        assertEquals(4, g.vertexCount); assertEquals(6, g.groups.single().indices.size)               // quad → 2 triángulos
        assertEquals(1f, g.uvs[1], "v invertida: v=0 en Collada → 1 (origen arriba-izquierda)")
        assertTrue(Collada.readGeometries(ByteArrayInputStream(dae.toByteArray()), setOf("nope")).isEmpty())
    }

    // ---------------------------------------------------------------------------------------------- mod sintético completo

    private fun entry(path: String, text: String) = ArchiveEntry(1, "mod", path, text.length.toLong(), 0, MemorySource(path.substringAfterLast('/'), text.toByteArray()))

    private fun modEntries(): List<ArchiveEntry> {
        val d = "vehicles/tcar/"
        val twoMesh = dae.replace("car_body", "tcar_body").replace("</visual_scene>", """<node id="n2" name="tcar_wheel"><matrix sid="transform">1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1</matrix>
 <instance_geometry url="#g1"><bind_material><technique_common><instance_material symbol="m1-material" target="#m1-material"/></technique_common></bind_material></instance_geometry></node>
 <node id="n3" name="tcar_windshield_dmg"><matrix sid="transform">1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1</matrix>
 <instance_geometry url="#g1"><bind_material><technique_common><instance_material symbol="m1-material" target="#m1-material"/></technique_common></bind_material></instance_geometry></node></visual_scene>""")
        return listOf(
            entry(d + "tcar.dae", twoMesh),
            entry(d + "tcar.jbeam", """{"tcar":{"slotType":"main","information":{"name":"Test"},
                "slots":[["type","default","description"],["tcar_wheel","tcar_wheel_a","Rueda",{"nodeOffset":{"x":0.8,"y":-1.4,"z":0.3}}],["tcar_glass","tcar_glass_a","Cristal"]],
                "flexbodies":[["mesh","[group]:"],["tcar_body",["tcar"]]],
                "nodes":[["id","posX","posY","posZ"],{"nodeWeight":100},["r1",0,0,0.5],["r2",0,2,0.5]],"beams":[["id1:","id2:"],["r1","r2"]]},
                "tcar_wheel_a":{"slotType":"tcar_wheel","flexbodies":[["mesh","[group]:"],["tcar_wheel",["wheel_FL"],[],{"pos":{"x":0,"y":0,"z":0}}]],
                  "nodes":[["id","posX","posY","posZ"],["fw1",0,0,0],["fw2",0.2,0,0]],
                  "pressureWheels":[["name","hubGroup","group","node1:","node2:"],{"radius":0.31,"tireWidth":0.2},["FL","","","fw1","fw2"]]},
                "tcar_glass_a":{"slotType":"tcar_glass","flexbodies":[["mesh","[group]:"],["tcar_windshield_dmg",["g"]]]}}"""),
            entry(d + "main.materials.json", """{"paint":{"name":"paint","mapTo":"paint","Stages":[{"colorPaletteMap":"/x.png","metallicFactor":0.9},{}]}}"""),
            entry(d + "info.json", """{"Brand":"Test","Name":"Car","default_pc":"base","defaultPaintName1":"Rojo","paints":{"Rojo":{"baseColor":[1,0,0,1],"metallic":0.5,"roughness":0.3}}}"""),
            entry(d + "base.pc", """{"mainPartName":"tcar","parts":{"tcar_wheel":"tcar_wheel_a","tcar_glass":"tcar_glass_a"}}"""),
            entry(d + "info_base.json", """{"Configuration":"Base","Drivetrain":"RWD","Power":100,"Weight":1000}"""),
        )
    }

    @Test fun syntheticModLoadsWithCorrectAxesWheelsDamageAndPhysics() {
        val scan = BeamNgScanner.scan("x", "Mi_Archivo", modEntries())
        val v = scan.vehicles.single(); assertEquals("Test Car", v.displayName); assertEquals("base", v.defaultConfig); assertEquals("Mi_Archivo", v.sourceName)
        assertEquals("Base · RWD · 100 hp · 1000 kg".replace("Base · ", ""), v.configs.single().summary.replace(" · Factory", ""))
        val r = BeamNgBuilder.load(v, null)
        assertEquals("Mi_Archivo", r.model.name, "el modelo lleva el nombre del archivo importado")
        val body = r.model.parts.first { it.name == "body" }
        // BeamNG +X izquierda / +Y atrás  →  GTA +X derecha / +Y delante: giro de 180° sobre Z, sin espejo
        assertTrue(Mat4.determinant3(body.transform) > 0.99f)
        val o = FloatArray(3)
        Mat4.transformPoint(body.transform, 1f, 1f, 0f, o)    // (1,1,0) + traslación del nodo (2,0,0) en BeamNG (izquierda y atrás) → (-3,-1,0) en GTA (izquierda y atrás)
        assertEquals(-3f, o[0]); assertEquals(-1f, o[1]); assertEquals(0f, o[2])
        // la rueda se coloca con el nodeOffset de su ranura (x +0.8 izquierda → -0.8 en GTA; y -1.4 delante → +1.4)
        val wheel = r.model.parts.first { it.category == PartCategory.WHEEL }
        assertEquals(WheelSlot.FL, wheel.wheelSlot)
        Mat4.transformPoint(wheel.transform, 0f, 0f, 0f, o)
        assertEquals(-0.8f, o[0], 1e-5f); assertEquals(1.4f, o[1], 1e-5f); assertEquals(0.3f, o[2], 1e-5f)
        // el parabrisas roto es una variante de daño: oculto por defecto
        val dmg = r.model.parts.single { it.isDamage }; assertFalse(dmg.defaultVisible)
        // pintura por defecto desde info.json y material pintable sin textura
        assertEquals(0xFF0000, r.paintPrimary); assertEquals(1, r.model.subMeshes.first().material.paintSlot); assertEquals(0.9f, r.model.subMeshes.first().material.metallic)
        // física
        assertEquals(2 * 100.0 + 25.0 * 2, r.physics.totalMass, 1e-6); assertEquals(1, r.physics.beams.size)
        val js = JSONObject(r.physics.toJson()); assertEquals(4, js.getJSONArray("nodos").length()); assertEquals(1, js.getJSONArray("vigas").length())
        assertEquals(0.31, js.getJSONArray("ruedas").getJSONObject(0).getDouble("radio"), 1e-6)
        val c = js.getJSONArray("ruedas").getJSONObject(0).getJSONArray("centro")                  // a la izquierda del coche = +X en el GLB
        assertEquals(0.9, c.getDouble(0), 1e-6)
    }

    @Test fun exportKeepsImportedNameDamageAndPhysicsInOneZip() {
        val v = BeamNgScanner.scan("x", "Mi_Archivo", modEntries()).vehicles.single()
        val r = BeamNgBuilder.load(v, null)
        val ids = r.model.parts.filter { it.defaultVisible }.flatMap { p -> p.subMeshes.map { it.id } }.toSet()
        val plain = BeamExport.export(r, r.model, ids, emptySet(), PaintScheme(), 1f, "Mi_Archivo", ExportFormat.GLB, DamageMode.NONE, physics = false)
        assertEquals("Mi_Archivo.glb", plain.fileName)
        val full = BeamExport.export(r, r.model, ids, emptySet(), PaintScheme(), 1f, "Mi_Archivo", ExportFormat.GLB, DamageMode.EMBEDDED, physics = true)
        assertEquals("Mi_Archivo_completo.zip", full.fileName)
        val names = ZipInputStream(ByteArrayInputStream(full.bytes)).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertEquals(listOf("Mi_Archivo.glb", "Mi_Archivo_fisica.json"), names)
        // el parabrisas roto no tiene gemelo intacto exportado («windshield»): no se cuela como daño; el GLB solo lleva carrocería y rueda
        val glb = ZipInputStream(ByteArrayInputStream(full.bytes)).use { z -> generateSequence { z.nextEntry }.first { it.name.endsWith(".glb") }.let { z.readBytes() } }
        val j = JSONObject(String(glb, 20, ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN).getInt(12), Charsets.UTF_8))
        assertEquals(3, j.getJSONArray("nodes").length(), "raíz + carrocería + rueda")
    }

    // ---------------------------------------------------------------------------------------------- mod real (solo si está)

    @Test fun realSkylineModLoadsRendersAndExports() {
        val f = File(System.getenv("BEAMNG_REAL_ZIP") ?: "/tmp/claude-0/beam/inner.zip"); if (!f.exists()) return
        ZipArchive.open(1, f).use { z ->
            val scan = BeamNgScanner.scan("r", "Nissan_Skyline_R32", z.entries)
            val v = scan.vehicles.single()
            assertEquals(27, v.configs.size); assertEquals("GTS-t_M", v.defaultConfig)
            val r = BeamNgBuilder.load(v, null, 256)
            val b = r.model.fullBounds()!!
            val len = b.max[2] - b.min[2]; val wid = b.max[0] - b.min[0]
            assertTrue(len in 4.2f..4.7f && wid in 1.6f..2.0f, "dimensiones de un R32: ${len}×$wid")
            assertEquals(4, r.model.parts.count { it.category == PartCategory.WHEEL })
            assertTrue(r.model.textures.size >= 15, "texturas decodificadas (DDS BC1/BC3/BC7, PNG): ${r.model.textures.size}")
            assertTrue(r.physics.totalMass in 800.0..2500.0 && r.physics.beams.size > 1000)
            val ids = r.model.parts.filter { it.defaultVisible }.flatMap { p -> p.subMeshes.map { it.id } }.toSet()
            val out = BeamExport.export(r, r.model, ids, emptySet(), PaintScheme(r.paintPrimary, 0x222222), 1f, "Nissan_Skyline_R32", ExportFormat.GLB, DamageMode.EMBEDDED, physics = true)
            assertEquals("Nissan_Skyline_R32_completo.zip", out.fileName)
            println("REAL-BEAM: ${r.model.parts.size} piezas, ${r.model.totalTriangles} tris, ${r.model.textures.size} texturas, zip ${out.bytes.size / 1024} KB, avisos ${r.model.warnings.size}")
        }
    }
}
