package com.webmaletin.gtaviewer

import com.webmaletin.gtaviewer.catalog.FormatRegistry
import com.webmaletin.gtaviewer.classify.*
import com.webmaletin.gtaviewer.export.*
import com.webmaletin.gtaviewer.fit.*
import com.webmaletin.gtaviewer.glb.*
import com.webmaletin.gtaviewer.library.*
import com.webmaletin.gtaviewer.mesh.*
import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.project.*
import com.webmaletin.gtaviewer.texture.*
import com.webmaletin.gtaviewer.txd.TextureImage
import com.webmaletin.gtaviewer.uv.UvTools
import com.webmaletin.gtaviewer.zip.MemorySource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class LabCoreTest {
    private val txd = RwTestWriter.txd(listOf("body", "glass", "tyre", "pzero", "wheel", "brakerotor").mapIndexed { i, n ->
        RwTestWriter.TestTexture(n, 8, 8, 21, RwTestWriter.solidBgra(8, 8, 40 + i * 30, 90, 200 - i * 20)) })

    private fun tmp(): File = Files.createTempDirectory("lab").toFile().also { it.deleteOnExit() }

    private fun item(name: String, k: Float = 1f, outer: Int = 1, source: String = "pack1", txdBytes: ByteArray = txd, wheelK: Float = k) =
        LibraryImporter.Item(name, source, "$source/$name/$name.dff", MemorySource("$name.dff", RwTestWriter.scaledVehicleDff(k, outer, wheelK)), MemorySource("$name.txd", txdBytes))

    private fun lib(root: File = tmp(), vararg items: LibraryImporter.Item): LibraryStore { val s = LibraryStore(root); LibraryImporter(s).importAll(items.toList()); return s }

    // ===================================================================== catálogo permanente

    @Test fun libraryPersistsAcrossRestartAndLoadsOnDemand() {
        val root = tmp()
        val s1 = lib(root, item("Alpha car"))
        val v = s1.vehicles().single()
        val tri = s1.loadVehicle(v.id).totalTriangles
        assertTrue(s1.components().isNotEmpty())
        // "reiniciar la app": nuevo objeto sobre la misma carpeta
        val s2 = LibraryStore(root)
        assertEquals(s1.all().map { it.id }.toSet(), s2.all().map { it.id }.toSet())
        assertEquals(tri, s2.loadVehicle(v.id).totalTriangles)
        assertEquals(v.dims, s2.get(v.id)!!.dims)
        val m = s2.loadVehicle(v.id)
        assertEquals(4, m.parts.count { it.category == PartCategory.WHEEL })
        assertTrue(m.materials.any { it.textureName == "body" && it.texture != null }, "texturas restauradas desde disco")
        // los parts dañados (variantes) no se guardan; el modelo conserva la estructura de ruedas/puertas
        assertTrue(m.parts.none { it.name.endsWith("_dam") })
    }

    @Test fun reimportingTheSameVehicleDoesNotDuplicate_andSharedPartsAreMerged() {
        val s = lib(tmp(), item("A car"))
        val n = s.all().size
        val r = LibraryImporter(s).importOne(item("A car"))
        assertNotNull(r.duplicateOf); assertEquals(n, s.all().size)
        // otro vehículo distinto con las MISMAS ruedas (misma geometría) → la rueda no se duplica
        LibraryImporter(s).importOne(item("B car", 1.2f, source = "pack2", wheelK = 1f))
        assertEquals(2, s.vehicles().size)
        assertEquals(1, s.components().count { it.category == "WHEEL" }, "una sola rueda para ambos vehículos")
        assertTrue(s.components().first { it.category == "WHEEL" }.alsoFoundIn.isNotEmpty())
    }

    @Test fun blobsAreCanonicalAndDeduplicated() {
        val s = lib(tmp(), item("A car"))
        val wheel = s.components().first { it.category == "WHEEL" }
        val m1 = s.loadComponent(wheel.id); val m2 = s.loadComponent(wheel.id)
        val h1 = Hashing.sha1(BlobCodec.encode(m1.parts[0])); val h2 = Hashing.sha1(BlobCodec.encode(m2.parts[0]))
        assertEquals(h1, h2); assertEquals(wheel.blobs.single(), h1)
        assertEquals(4, s.manifestOf(s.vehicles().single().id)!!.parts.count { it.category == "WHEEL" })
        assertEquals(1, s.manifestOf(s.vehicles().single().id)!!.parts.filter { it.category == "WHEEL" }.map { it.blob }.toSet().size, "4 ruedas comparten 1 blob")
    }

    @Test fun metadataEditingFavoritesCategoriesAndSafeDelete() {
        val root = tmp(); val outside = File(tmp(), "original.zip").also { it.writeText("no tocar") }
        val s = lib(root, item("A car"))
        val v = s.vehicles().single(); val door = s.components().first { it.category == "DOOR" }
        s.rename(door.id, "Puerta deportiva"); s.setFavorite(door.id, true); s.addCustomCategory("Mis puertas"); s.setCategory(door.id, "Mis puertas")
        val s2 = LibraryStore(root)
        assertEquals("Puerta deportiva", s2.get(door.id)!!.name); assertTrue(s2.get(door.id)!!.favorite); assertEquals("Mis puertas", s2.get(door.id)!!.category)
        assertEquals(listOf("Mis puertas"), s2.customCategories())
        val before = s2.storageBytes()
        assertEquals(1, s2.delete(setOf(door.id), cascade = false)); assertNull(s2.get(door.id)); assertNotNull(s2.get(v.id))
        assertTrue(s2.loadVehicle(v.id).totalTriangles > 0, "borrar un componente no rompe el vehículo")
        val removed = s2.delete(setOf(v.id), cascade = true)
        assertTrue(removed >= 1); assertEquals(0, s2.vehicles().size)
        assertTrue(s2.storageBytes() < before, "los blobs sin referencias se recolectan")
        assertEquals("no tocar", outside.readText(), "nunca se tocan archivos fuera de la biblioteca")
    }

    @Test fun backupExportImportMergesWithoutDuplicates() {
        val s = lib(tmp(), item("A car"), item("B car", 1.1f, source = "p2"))
        s.setFavorite(s.vehicles().first().id, true)
        val bos = ByteArrayOutputStream(); s.exportBackup(bos)
        val fresh = LibraryStore(tmp())
        val r = fresh.importBackup(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(s.all().size, r.addedPieces); assertEquals(0, r.skippedPieces)
        assertEquals(s.vehicles().first().id, fresh.vehicles().first { it.favorite }.id)
        assertEquals(s.vehicles().first().let { s.loadVehicle(it.id).totalTriangles }, fresh.loadVehicle(s.vehicles().first().id).totalTriangles)
        val r2 = fresh.importBackup(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(0, r2.addedPieces); assertEquals(s.all().size, r2.skippedPieces)
    }

    @Test fun queryFiltersSortsAndSearches() {
        val s = lib(tmp(), item("Zeta big", 1.4f), item("Alpha small", 0.8f, source = "p2"))
        val all = s.all()
        assertEquals(listOf("Alpha small", "Zeta big"), LibraryQuery(kind = "VEHICLE").apply(all).map { it.name })
        assertEquals("Zeta big", LibraryQuery(kind = "VEHICLE", sort = SortKey.LENGTH, descending = true).apply(all).first().name)
        assertTrue(LibraryQuery(text = "alpha").apply(all).all { it.name.contains("Alpha", true) || it.origin.contains("alpha", true) })
        assertTrue(LibraryQuery(category = "WHEEL").apply(all).all { it.category == "WHEEL" })
        assertEquals(listOf("p2"), LibraryQuery(kind = "VEHICLE", source = "p2").apply(all).map { it.source })
        val z = all.first { it.name == "Zeta big" }; s.setFavorite(z.id, true)
        assertEquals(listOf(z.id), LibraryQuery(kind = "VEHICLE", favoritesOnly = true).apply(s.all()).map { it.id })
    }

    @Test fun thumbnailsAreRenderedOnDemandAndCached() {
        val s = lib(tmp(), item("A car"))
        val id = s.vehicles().single().id
        val png = s.thumbnailPng(id)!!
        assertTrue(PngDecoder.isPng(png))
        val img = PngDecoder.decode(png)
        assertEquals(160, img.width)
        assertTrue((0 until img.width * img.height).count { (img.rgba[it * 4 + 3].toInt() and 0xFF) > 0 } > 500, "la miniatura contiene geometría")
        assertTrue(File(s.root, "thumbs/$id.png").exists())
    }

    // ===================================================================== clasificación

    private fun feat(l: Float, w: Float, h: Float, wheels: Int = 4, doors: Int = 4, tokens: Set<String> = emptySet(), axles: Int = 2) =
        VehicleFeatures(l, w, h, wheels, axles, l * 0.55f, w * 0.8f, 0.33f, doors, 20000, tokens)

    @Test fun classifierUsesGeometryNotJustNames_withConfidenceAndMultipleLabels() {
        val truck = VehicleClassifier.classify(feat(12f, 3f, 4f, wheels = 10, axles = 4, tokens = setOf("xyz")))
        assertEquals(VehicleTypes.TRUCK, truck.labels.first().label); assertTrue(truck.labels.first().confidence >= 0.7f)
        val sports = VehicleClassifier.classify(feat(4.5f, 1.9f, 1.15f, doors = 2, tokens = setOf("generic")))
        assertTrue(sports.labels.any { it.label == VehicleTypes.SUPERCAR || it.label == VehicleTypes.SPORTS }, "nombre genérico, pero la forma baja delata un deportivo")
        assertTrue(sports.labels.map { it.label }.containsAll(listOf(VehicleTypes.COUPE)), "etiquetas múltiples: deportivo y coupé")
        val sedan = VehicleClassifier.classify(feat(4.9f, 1.85f, 1.45f, doors = 4))
        assertEquals(VehicleTypes.SEDAN, sedan.labels.first().label)
        assertTrue(sedan.reasons.isNotEmpty(), "se explican las razones")
        // nombre engañoso no basta para un camión: un "truck" de 3.8 m de largo
        val fake = VehicleClassifier.classify(feat(3.8f, 1.7f, 1.4f, tokens = setOf("truck")))
        assertTrue((fake.labels.firstOrNull { it.label == VehicleTypes.TRUCK }?.confidence ?: 0f) < 0.7f)
    }

    @Test fun userCorrectionsAreLearnedForSimilarVehicles() {
        val s = lib(tmp(), item("Corrected car"))
        val v = s.vehicles().single()
        s.correctVehicleTypes(v.id, listOf(VehicleTypes.PICKUP, VehicleTypes.CLASSIC))
        assertTrue(s.get(v.id)!!.typesCorrected); assertEquals(1f, s.get(v.id)!!.types.first().confidence)
        // un vehículo parecido, importado después, hereda la corrección ("aprendida")
        val s2 = LibraryStore(File(s.root.path))
        val f = FeatureExtractor.extract(s2.loadVehicle(v.id), listOf("Corrected car"))
        val r = VehicleClassifier.classify(f, s2.correctionList())
        assertEquals(setOf(VehicleTypes.PICKUP, VehicleTypes.CLASSIC), r.labels.take(2).map { it.label }.toSet())
        assertTrue(r.labels.first().source.contains("corrección"))
    }

    // ===================================================================== mallas

    private fun boxMesh(flipped: Boolean = false, hole: Boolean = false): FlatMesh {
        val m = GtaSaVehicleBuilderForTests.boxFlat()
        if (flipped) m.flipWinding()
        return if (hole) m.withoutTriangles(setOf(0)) else m
    }

    @Test fun analysisFindsFlippedFacesHolesAndDuplicates_repairIsValidated() {
        val clean = MeshAnalysis.analyze(boxMesh())
        assertTrue(clean.isClean && clean.isWatertight && clean.components == 1, clean.summary())
        val flipped = boxMesh(flipped = true)
        val rf = MeshAnalysis.analyze(flipped); assertTrue(rf.flippedVsNormals > 0)
        val rep = MeshAnalysis.repair(flipped)
        assertTrue(rep.validated, "reparación validada por re-análisis"); assertEquals(0, rep.after.flippedVsNormals)
        // agujero pequeño
        val holed = boxMesh(hole = true)
        assertTrue(MeshAnalysis.analyze(holed).openEdges > 0)
        val r2 = MeshAnalysis.repair(holed); assertTrue(r2.after.openEdges < r2.before.openEdges, "el agujero se cerró: ${r2.after.summary()}")
        // duplicada + degenerada
        val d = boxMesh(); d.tris = d.tris + intArrayOf(d.tris[0], d.tris[1], d.tris[2]) + intArrayOf(0, 0, 1); d.triMat = d.triMat + intArrayOf(0, 0)
        val rd = MeshAnalysis.repair(d); assertTrue(rd.validated); assertEquals(0, rd.after.degenerate + rd.after.duplicateFaces)
        // una malla ya limpia NO se declara reparada
        assertFalse(MeshAnalysis.repair(boxMesh()).validated)
    }

    @Test fun componentsRegionGrowingAndSeparation() {
        val a = boxMesh(); val b = boxMesh().transformed(Mat4.translate(5f, 0f, 0f))
        val two = FlatMesh.combine(listOf(a, b))
        val comps = MeshAnalysis.connectedComponents(two); assertEquals(2, comps.size); assertEquals(12, comps[0].size)
        // parche: con 30° cada cara plana de un cubo es su propio parche (2 triángulos)
        val patch = MeshAnalysis.growRegion(a, 0, 30f, sameMaterial = false)
        assertEquals(2, patch.size)
        // con 100° se propaga a las caras vecinas (ángulo de 90°)
        assertTrue(MeshAnalysis.growRegion(a, 0, 100f, sameMaterial = false).size > 2)
    }

    @Test fun fuseWeldsCoincidentVerticesAndKeepsUvSeamsSeparate_stitchClosesGaps() {
        fun posUv(m: FlatMesh): FlatMesh { for (v in 0 until m.vertexCount) { m.uv[v * 2] = m.pos[v * 3]; m.uv[v * 2 + 1] = m.pos[v * 3 + 1] }; return m }
        val a = posUv(boxMesh()); val b = posUv(boxMesh().transformed(Mat4.translate(1f, 0f, 0f))) // comparten una cara de vértices (misma UV)
        val combined = MeshFusion.combine(listOf(a, b))
        val fr = MeshFusion.fuse(combined)
        assertTrue(fr.weldedVertices > 0 && fr.mesh.vertexCount < combined.vertexCount)
        assertTrue(fr.note.contains("no es una booleana"))
        // con UV distintas no se sueldan (se conserva la costura de UV)
        val b2 = b.copy(); for (i in b2.uv.indices) b2.uv[i] += 0.5f
        val fr2 = MeshFusion.fuse(MeshFusion.combine(listOf(a, b2)))
        assertTrue(fr2.weldedVertices < fr.weldedVertices)
        // coser: pieza separada 5 mm
        // (la pieza es abierta: sin la cara -x; solo se cosen los vértices de BORDE)
        val piece = boxMesh().withoutTriangles(setOf(8, 9)).transformed(Mat4.translate(1.005f, 0f, 0f))
        val (res, moved) = MeshFusion.stitch(a, piece, 0.02f)
        assertEquals(4, moved)
        assertEquals(piece.triCount, res.triCount, "no se pierde ni se añade geometría")
        assertTrue((0 until res.vertexCount).any { Math.abs(res.pos[it * 3] - 0.5f) < 1e-6f }, "los bordes quedaron sobre la cara de la base")
    }

    // ===================================================================== texturas

    @Test fun paintDocumentBrushesUndoRedoStampInpaintAndCommit() {
        val w = 64; val h = 64
        val base = ByteArray(w * h * 4) { 255.toByte() } // blanco opaco
        val doc = PaintDocument(w, h, base, "body")
        doc.begin("trazo"); doc.stroke(5f, 5f, 40f, 40f, 3f, 0xFF0000, 1f, 1f); doc.end()
        fun px(x: Int, y: Int) = doc.flatten().let { (it[(y * w + x) * 4].toInt() and 0xFF) to (it[(y * w + x) * 4 + 1].toInt() and 0xFF) }
        assertEquals(255 to 0, px(22, 22), "rojo en el trazo")
        assertTrue(doc.undo()); assertEquals(255 to 255, px(22, 22)); assertTrue(doc.redo()); assertEquals(255 to 0, px(22, 22))
        // el resto del diseño se conserva: un píxel lejano sigue blanco
        assertEquals(255 to 255, px(60, 5))
        // capa nueva + opacidad
        doc.addLayer("logo"); doc.begin("rect"); doc.rect(50, 50, 60, 60, 0x0000FF, 1f); doc.end()
        assertEquals(0, px(55, 55).first); doc.setVisible(1, false); assertEquals(255, px(55, 55).first)
        doc.setVisible(1, true)
        // sello con rotación/escala
        val stampImg = ByteArray(4 * 4 * 4) { i -> if (i % 4 == 3) 255.toByte() else if (i % 4 == 1) 255.toByte() else 0 } // verde
        doc.active = 1; doc.begin("sello"); doc.stamp(stampImg, 4, 4, 20f, 50f, 3f, 30f, 1f); doc.end()
        assertEquals(255, px(20, 50).second); assertNotEquals(0xFF, px(20, 50).first)
        // inpaint: borrar el trazo rojo de la capa base rellenando con el entorno
        doc.active = 0; doc.begin("inpaint"); doc.inpaint(15, 15, 15, 15); doc.end()
        assertTrue(px(22, 22).second > 200, "la zona del 'logo' se rellena con el entorno: ${px(22, 22)}")
        // restaurar pincel hacia el original
        doc.begin("r"); doc.dab(30f, 30f, 6f, 0, 1f, 1f, BrushMode.ERASE); doc.end()
        doc.begin("r2"); doc.dab(30f, 30f, 6f, 0, 1f, 1f, BrushMode.RESTORE); doc.end()
        assertEquals(255, px(30, 30).second)
        // commit a TextureImage (los cambios se guardan en píxeles)
        val tex = TextureImage("body", w, h, base.copyOf(), false, "BGRA")
        doc.commitTo(tex); assertEquals(1, tex.version)
        assertEquals(0, tex.rgba[(55 * w + 55) * 4].toInt() and 0xFF, "el rectángulo azul quedó en la imagen")
        doc.restoreOriginal(); doc.commitTo(tex)
        assertTrue(tex.rgba.all { it == 255.toByte() }, "restaurar devuelve la original")
    }

    @Test fun pngRoundTrip_andExportedTexturesDecode() {
        val rgba = ByteArray(5 * 3 * 4) { (it * 7).toByte() }
        val img = PngDecoder.decode(PngEncoder.encode(5, 3, rgba))
        assertEquals(5, img.width); assertContentEquals(rgba, img.rgba)
    }

    @Test fun uvIslandsStretchAndTransformKeepOriginal() {
        val m = boxMesh(); val g = GeometryData(0, m.vertexCount, m.pos, m.nor, m.uv, true, true, false)
        val islands = UvTools.islands(g, m.tris)
        assertTrue(islands.isNotEmpty())
        val st = UvTools.stretch(g, m.tris)
        val before = m.uv.copyOf()
        val moved = UvTools.transformIsland(m.uv, m.tris, islands[0], 0.1f, 0f, 1f, 0f)
        assertContentEquals(before, m.uv, "el original no se modifica")
        assertNotEquals(before.toList(), moved.toList())
        assertTrue(st.triangles == 12)
    }

    // ===================================================================== proyectos

    private fun twoCarLib(): LibraryStore = lib(tmp(), item("Base car", 1f), item("Donor car", 1.1f, source = "p2", txdBytes = RwTestWriter.txd(listOf("body", "glass", "tyre", "pzero", "wheel", "brakerotor").map { RwTestWriter.TestTexture(it, 8, 8, 21, RwTestWriter.solidBgra(8, 8, 200, 10, 10)) })))

    private fun project(s: LibraryStore): CarProject {
        val p = ProjectStore(s).create("Mi proyecto")
        p.setBaseVehicle(s.vehicles().first { it.name == "Base car" }.id)
        return p
    }

    @Test fun visibilityAndExportAreSeparate_withSummaryLockUndoDeleteRestore() {
        val s = twoCarLib(); val p = project(s)
        val wheels = p.state.parts.filter { it.category == "WHEEL" }.map { it.id }
        assertTrue(p.state.parts.filter { it.isBase }.all { it.locked }, "la base es referencia y llega bloqueada")
        p.setVisible(wheels, false)
        var m = p.materialize()
        assertTrue(wheels.all { id -> p.renderVisible(m).none { it / 1000 == id } }, "ocultas en pantalla")
        assertTrue(p.exportScene().meshes.any { it.partName.contains("Wheel") }, "ocultar NO excluye de la exportación")
        assertEquals(wheels.size, p.exportSummary().hiddenButIncluded.size)
        p.setExport(wheels, false)
        assertTrue(p.exportScene().meshes.none { it.partName.contains("Wheel") })
        val sum = p.exportSummary(); assertTrue(sum.omitted.any { it.second.contains("excluida") }); assertTrue(sum.included.isNotEmpty())
        // bloqueo
        val door = p.state.parts.first { it.category == "DOOR" }
        assertNotNull(p.nudge(listOf(door.id), floatArrayOf(1f, 0f, 0f)), "bloqueada: se rechaza con mensaje")
        fun probe(): Float { val c = FloatArray(3); Mat4.transformPoint(p.materialize().parts.first { it.id == door.id }.transform, 0.3f, 0.2f, 0.1f, c); return c[0] }
        val before = probe()
        p.setLocked(listOf(door.id), false); assertNull(p.nudge(listOf(door.id), floatArrayOf(0.5f, 0f, 0f)))
        assertEquals(before + 0.5f, probe(), 1e-4f)
        // eliminar y recuperar (deshacer y "recuperar")
        p.delete(listOf(door.id)); assertTrue(p.materialize().parts.none { it.id == door.id }); assertTrue(p.exportSummary().omitted.any { it.second.contains("eliminada") })
        assertTrue(p.undo()); assertTrue(p.materialize().parts.any { it.id == door.id })
        p.delete(listOf(door.id)); p.restore(listOf(door.id)); assertTrue(p.materialize().parts.any { it.id == door.id })
        assertTrue(p.canUndo && p.undo() && p.redo())
    }

    @Test fun projectSurvivesRestartAndReportsMissingResources() {
        val s = twoCarLib(); val p = project(s)
        val donorWheel = s.components().first { it.category == "WHEEL" }
        p.addPiece(s.components().first { it.category == "BUMPER" }.id); p.setPaint(0x112233); p.setExport(p.state.parts.take(2).map { it.id }, false)
        ProjectStore(s).save(p)
        val s2 = LibraryStore(s.root)
        val (re, missing) = ProjectStore(s2).open(p.id)
        assertTrue(missing.isEmpty())
        assertEquals(p.state.parts, re.state.parts); assertEquals(0x112233, re.state.paintPrimary)
        assertEquals(p.materialize().totalTriangles, re.materialize().totalTriangles)
        assertEquals(1, ProjectStore(s2).list().size)
        // recurso ausente: se abre sin esa pieza y se avisa
        val blob = p.state.parts.last().blob
        File(s.root, "blobs/$blob.gz").delete()
        val (re2, miss2) = ProjectStore(LibraryStore(s.root)).open(p.id)
        assertTrue(miss2.isNotEmpty() && miss2.first().contains("geometría")); assertTrue(re2.state.parts.size < p.state.parts.size)
        assertNotNull(donorWheel)
    }

    @Test fun addPieceWithFitScalesControlledAndNeverMovesTheBase() {
        val s = twoCarLib(); val p = project(s)
        val base = FitPlanner.baseInfo(s, s.vehicles().first { it.name == "Base car" }.id)!!
        val bumper = s.components().first { it.category == "BUMPER" && it.parentId == s.vehicles().first { v -> v.name == "Donor car" }.id }
        val res = FitPlanner.evaluate(base, bumper, s.get(bumper.parentId!!), emptyList())
        assertTrue(res.scale in 0.8f..1.25f); assertTrue(res.reasons.isNotEmpty())
        assertTrue(res.level != CompatLevel.INCOMPATIBLE)
        val id = p.addPiece(bumper.id, res)
        assertTrue(p.part(id)!!.trs.scale[0] in 0.8f..1.25f)
        val baseBefore = p.state.parts.filter { it.isBase }.map { it.trs }
        p.applyFit(id, res)
        assertEquals(baseBefore, p.state.parts.filter { it.isBase }.map { it.trs }, "la carrocería base no se deforma")
        assertNotNull(p.applyFit(p.state.parts.first { it.isBase }.id, res), "no se ajusta automáticamente la base")
        // un deportivo de otro tamaño extremo se marca dudoso/incompatible, pero se puede probar manualmente
        val tiny = FitPlanner.evaluate(BaseInfo("x", Dims(3f, 3f, 12f), floatArrayOf(0f, 0f, 0f), listOf(VehicleTypes.TRUCK), emptyList(), emptyList()), bumper, s.get(bumper.parentId!!), emptyList())
        assertTrue(tiny.level.rank <= CompatLevel.DOUBTFUL.rank); assertTrue(tiny.reasons.any { it.contains("escalar") || it.contains("familia") || it.contains("proporciones") })
        p.addPiece(bumper.id, tiny) // se permite probar
    }

    @Test fun wheelSetsAreOrientedFromGeometryNotFromSide() {
        for (outer in listOf(1, -1)) {
            val s = lib(tmp(), item("Base car", 1f, outer = 1), item("Donor", 1.1f, outer = outer, source = "p2"))
            val p = project(s)
            val wheel = s.components().first { it.category == "WHEEL" && it.parentId == s.vehicles().first { v -> v.name == "Donor" }.id }
            val ids = p.addWheelSet(wheel.id)
            assertEquals(4, ids.size)
            val m = p.materialize()
            for (id in ids) {
                val part = m.parts.first { it.id == id }
                val dir = FloatArray(3); Mat4.transformDir(part.transform, outer.toFloat(), 0f, 0f, dir)
                val side = if (part.transform[12] > 0) 1 else -1
                assertTrue(dir[0] * side > 0.99f * Mat4.length(dir[0], dir[1], dir[2]), "outer=$outer rueda en x=${part.transform[12]}: cara exterior hacia fuera (dir=${dir.toList()})")
                assertTrue(Mat4.determinant3(part.transform) > 0f, "sin espejo")
            }
        }
    }

    @Test fun materialsEditedByOriginAndTexturesPaintedAndSavedAsPixels() {
        val s = twoCarLib(); val p = project(s)
        val chassis = p.state.parts.first { it.name == "chassis" }
        // cambiar color del material de carrocería: afecta a TODOS los sub-meshes que lo usan
        val subIdx = p.materialize().parts.first { it.id == chassis.id }.subMeshes.indexOfFirst { it.material.textureName == "body" }
        p.editMaterial(chassis.id, subIdx) { it.copy(r = 10, g = 20, b = 30, metallic = 0.8f, roughness = 0.2f) }
        val users = p.materialize().subMeshes.filter { it.material.textureName == "body" }
        assertTrue(users.size > 1 && users.all { it.material.r == 10 && it.material.metallic == 0.8f && it.material.roughness == 0.2f })
        // se exporta con PBR
        val glb = Exporters.forFormat(ExportFormat.GLB).export(p.exportScene(ExportProfile.DEFAULT), "x")
        assertTrue(String(glb.bytes, 20, 4000, Charsets.UTF_8).contains("\"metallicFactor\":0.8"))
        p.restoreMaterial(chassis.id, subIdx); assertTrue(p.materialize().subMeshes.filter { it.material.textureName == "body" }.all { it.material.r != 10 })
        // vidrio independiente de la pintura
        val glassIdx = p.materialize().parts.first { it.id == chassis.id }.subMeshes.indexOfFirst { it.material.textureName == "glass" }
        p.editMaterial(chassis.id, glassIdx) { it.copy(r = 5, g = 5, b = 5, a = 120, glass = true, texMode = "NONE") }
        assertTrue(p.materialize().subMeshes.first { it.material.glass }.material.texture == null)
        assertTrue(p.materialize().subMeshes.filter { it.material.textureName == "body" }.none { it.material.r == 5 }, "cambiar cristales no toca la pintura")
        // textura: pintar y guardar
        val texId = p.adoptTexture(chassis.id, subIdx)!!
        val originalBytes = s.texture(p.textureRef(texId)!!.originalHash)!!.rgba.copyOf()
        val doc = p.paintDocument(texId)
        doc.begin("pintar"); doc.rect(0, 0, 3, 3, 0xFFFF00, 1f); doc.end()
        p.commitTexture(texId)
        val live = p.liveTexture(texId)!!
        assertEquals(255, live.rgba[0].toInt() and 0xFF); assertEquals(255, live.rgba[1].toInt() and 0xFF); assertEquals(0, live.rgba[2].toInt() and 0xFF)
        assertTrue(live.version > 0)
        assertContentEquals(originalBytes, s.texture(p.textureRef(texId)!!.originalHash)!!.rgba, "la textura importada no se modifica")
        // el modelo usa la textura editada y persiste tras reabrir
        assertTrue(p.materialize().subMeshes.filter { it.material.textureName == "body" }.all { it.material.texture === live })
        ProjectStore(s).save(p)
        val (re, _) = ProjectStore(LibraryStore(s.root)).open(p.id)
        val live2 = re.liveTexture(texId)!!
        assertEquals(255, live2.rgba[0].toInt() and 0xFF); assertEquals(0, live2.rgba[2].toInt() and 0xFF)
        assertEquals(1, re.paintDocument(texId).layers.size)
        // variantes y restauración
        p.saveVariant("Amarilla"); p.restoreTexture(texId)
        assertEquals(originalBytes[0], p.liveTexture(texId)!!.rgba[0]); assertTrue(p.applyVariant("Amarilla"))
        assertEquals(0, p.liveTexture(texId)!!.rgba[2].toInt() and 0xFF)
        // exportación contiene la textura editada
        val scene = p.exportScene(ExportProfile.DEFAULT)
        val tex = scene.textures.first { it.fileName.startsWith("body") }
        assertEquals(255, PngDecoder.decode(tex.png).rgba[0].toInt() and 0xFF)
    }

    @Test fun geometryEditsAreNonDestructive_deleteFacesSplitFuseRepairRestore() {
        val s = twoCarLib(); val p = project(s)
        val door = p.state.parts.first { it.category == "DOOR" }; p.setLocked(listOf(door.id), false)
        val before = p.materialize().parts.first { it.id == door.id }.let { pt -> pt.subMeshes.sumOf { it.triangleCount } }
        assertNull(p.deleteFaces(door.id, setOf(0, 1)))
        assertEquals(before - 2, p.materialize().parts.first { it.id == door.id }.subMeshes.sumOf { it.triangleCount })
        assertTrue(p.part(door.id)!!.origBlob != null)
        assertTrue(p.restoreGeometry(door.id)); assertEquals(before, p.materialize().parts.first { it.id == door.id }.subMeshes.sumOf { it.triangleCount })
        // bloqueada la base: se rechaza
        assertNotNull(p.deleteFaces(p.state.parts.first { it.isBase && it.locked }.id, setOf(0)))
        // separar caras → pieza nueva
        val nid = p.separateFaces(door.id, setOf(0, 1, 2, 3))!!; assertEquals(4, p.materialize().parts.first { it.id == nid }.subMeshes.sumOf { it.triangleCount })
        // combinar y fusionar: originales conservados (ocultos/excluidos)
        val a = p.state.parts.first { it.category == "HOOD" }; val b = p.state.parts.first { it.category == "BUMPER" }
        val (fid, fr) = p.fuse(listOf(a.id, b.id))!!
        assertTrue(p.part(a.id)!!.let { !it.visible && !it.export }, "original conservado pero fuera"); assertTrue(p.part(fid)!!.mergedFrom.containsAll(listOf(a.id, b.id)))
        assertTrue(fr.note.isNotEmpty())
        p.undo(); assertTrue(p.part(a.id)!!.visible)
        // reparar
        val rep = p.repair(door.id, apply = false)!!; assertNotNull(rep.after)
        // separación en componentes: no destructiva
        val twoPieces = p.combine(listOf(p.state.parts.first { it.category == "HOOD" }.id, p.state.parts.first { it.category == "BUMPER" }.id))!!
        val parts = p.splitComponents(twoPieces); assertEquals(2, parts.size); assertTrue(p.part(twoPieces)!!.let { !it.visible && !it.export })
    }

    @Test fun uvIslandEditKeepsRestorableOriginal() {
        val s = twoCarLib(); val p = project(s)
        val door = p.state.parts.first { it.category == "DOOR" }; p.setLocked(listOf(door.id), false)
        val before = p.materialize().parts.first { it.id == door.id }.subMeshes[0].geometry.uvs.copyOf()
        assertNull(p.transformUvIsland(door.id, 0, 0, 0.25f, 0f, 1f, 0f))
        val after = p.materialize().parts.first { it.id == door.id }.subMeshes[0].geometry.uvs
        assertNotEquals(before.toList(), after.toList())
        assertTrue(p.restoreGeometry(door.id)); assertContentEquals(before, p.materialize().parts.first { it.id == door.id }.subMeshes[0].geometry.uvs)
    }

    // ===================================================================== exportación para Godot y verificación

    @Test fun godotProfileOrientsOriginsAndGlbReimportsAndVerifies() {
        val s = twoCarLib(); val p = project(s)
        val def = p.exportScene(ExportProfile.DEFAULT); val godot = p.exportScene(ExportProfile.GODOT)
        fun bounds(sc: ExportScene) = Bounds.empty().also { b -> sc.meshes.forEach { m -> for (v in 0 until m.vertexCount) b.expand(m.positions[v * 3], m.positions[v * 3 + 1], m.positions[v * 3 + 2]) } }
        val bd = bounds(def); val bg = bounds(godot)
        assertEquals(0f, bg.min[1], 1e-4f, "origen en el suelo")
        assertEquals(0f, (bg.min[0] + bg.max[0]) / 2, 1e-4f); assertEquals(0f, (bg.min[2] + bg.max[2]) / 2, 1e-4f)
        // frente: el capó (delante, +Z en glTF) queda en -Z en Godot
        val hoodDef = def.meshes.first { it.partName.contains("bonnet") }.positions.let { (0 until it.size / 3).map { i -> it[i * 3 + 2] }.average() }
        val hoodGod = godot.meshes.first { it.partName.contains("bonnet") }.positions.let { (0 until it.size / 3).map { i -> it[i * 3 + 2] }.average() }
        assertTrue(hoodDef > 0 && hoodGod < 0, "frente +Z → -Z")
        assertEquals(bd.size[0], bg.size[0], 1e-3f)
        // ruedas siguen como nodos independientes
        val glb = Exporters.forFormat(ExportFormat.GLB).export(godot, "car")
        val rep = GlbVerifier.verify(godot, glb.bytes)
        assertTrue(rep.ok, rep.text())
        val data = rep.reimported!!; assertEquals(4, data.meshNodes.count { it.name.contains("Wheel") || it.name.contains("wheel") })
        // reimportado al visor
        val vm = GlbImporter.toVehicleModel(data, "reimport"); assertEquals(godot.totalTriangles, vm.totalTriangles)
        // malla combinada
        val combined = p.exportScene(ExportProfile.GODOT.copy(combineMeshes = true))
        assertTrue(combined.meshes.size < godot.meshes.size && combined.meshes.any { it.partName.contains("Wheel") }, "ruedas separadas aun combinando")
        assertEquals(godot.totalTriangles, combined.totalTriangles)
        // verificación NEGATIVA: un GLB corrupto o distinto no pasa
        val bad = glb.bytes.copyOf(); bad[bad.size - 20] = 0x7F
        assertFalse(GlbVerifier.verify(p.exportScene(ExportProfile.DEFAULT), glb.bytes).ok, "perfil distinto → la geometría no coincide")
        assertFalse(GlbVerifier.verify(godot, glb.bytes.copyOf(50)).ok)
    }

    @Test fun exportingOnlyBodyWithoutWheelsInteriorOrSelection() {
        val s = twoCarLib(); val p = project(s)
        p.setExport(p.state.parts.filter { it.category == "WHEEL" }.map { it.id }, false)
        assertTrue(p.exportScene(ExportProfile.DEFAULT).meshes.none { it.partName.lowercase().contains("wheel") })
        val only = p.exportScene(ExportProfile.DEFAULT, onlyIds = p.materialize().parts.filter { it.category == PartCategory.BODY }.flatMap { pt -> pt.subMeshes.map { it.id } }.toSet())
        assertTrue(only.meshes.all { it.partName == "chassis" })
    }

    // ===================================================================== generador

    @Test fun generatorUsesOnlyCatalogPiecesChecksCompatAndReportsMissing() {
        val s = twoCarLib()
        val r = CarGenerator.generate(s, GenRequest(modLevel = 3, seed = 3))
        assertTrue(r.baseId.isNotEmpty())
        assertTrue(r.picks.all { s.get(it.pieceId) != null }, "solo piezas existentes en el catálogo")
        assertTrue(r.picks.all { it.result.level.rank >= CompatLevel.ADJUST.rank }, "nunca combina sin comprobar compatibilidad")
        assertTrue(r.notes.any { it.contains("NO se ha comprobado") }, "no afirma validación de mallas")
        // determinista por semilla
        assertEquals(r.picks.map { it.pieceId }, CarGenerator.generate(s, GenRequest(modLevel = 3, seed = 3)).picks.map { it.pieceId })
        // sin vehículos del tipo pedido → informa lo que falta
        val none = CarGenerator.generate(s, GenRequest(vehicleType = VehicleTypes.TRUCK, seed = 1))
        assertTrue(none.baseId.isEmpty() && none.missing.isNotEmpty())
        // monta en un proyecto y mide mallas de verdad
        val proj = ProjectStore(s).create("gen")
        val added = CarGenerator.apply(proj, s, r)
        assertTrue(proj.state.parts.any { it.isBase }); val meas = CarGenerator.measure(proj, added)
        assertEquals(added.size, meas.size)
        // bloquear una pieza y regenerar la conserva
        val firstNonWheel = r.picks.firstOrNull { it.category != PartCategory.WHEEL }
        if (firstNonWheel != null) {
            val again = CarGenerator.generate(s, GenRequest(baseVehicleId = r.baseId, modLevel = 3, seed = 99, locked = mapOf(firstNonWheel.slot to firstNonWheel.pieceId)))
            assertTrue(again.picks.any { it.pieceId == firstNonWheel.pieceId })
        }
        // alternativas distintas
        val alts = CarGenerator.alternatives(s, GenRequest(modLevel = 2, seed = 10), 3)
        assertEquals(alts.size, alts.map { it.baseId + it.picks.joinToString { p -> p.pieceId } }.toSet().size)
        // piezas sin compatibilidad suficiente → catálogo sin piezas: se informa
        val empty = lib(tmp(), item("Only car"))
        val nothing = CarGenerator.generate(empty, GenRequest(modLevel = 3, seed = 2, useOriginalParts = false, useCustomParts = true))
        assertTrue(nothing.picks.none { it.fromSameVehicle })
        assertTrue(nothing.missing.isNotEmpty())
    }

    @Test fun confirmedFitsAreLearnedAndReused() {
        val s = twoCarLib(); val p = project(s)
        val donorBumper = s.components().first { it.category == "BUMPER" && s.get(it.parentId!!)!!.name == "Donor car" }
        val id = p.addPiece(donorBumper.id)
        p.nudge(listOf(id), floatArrayOf(0.1f, 0.2f, 0f), scaleFactor = 1.05f)
        p.confirmFit(id)
        val base = FitPlanner.baseInfo(s, p.state.baseVehicleId!!)!!
        val r = FitPlanner.evaluate(base, donorBumper, s.get(donorBumper.parentId!!), LibraryStore(s.root).learnedFits())
        assertTrue(r.learned && r.level == CompatLevel.COMPATIBLE); assertTrue(r.reasons.first().contains("confirmado"))
    }

    // ===================================================================== regresión del conversor con el catálogo

    @Test fun converterStillLoadsAndExportsAfterLibraryFeatures() {
        val m = FormatRegistry.default.loadManual("Regresión", MemorySource("a.dff", RwTestWriter.scaledVehicleDff()), MemorySource("a.txd", txd))
        assertTrue(m.totalTriangles > 0)
        for (f in ExportFormat.values()) assertTrue(Exporters.forFormat(f).export(ExportSceneBuilder.build(m, m.subMeshes.map { it.id }.toSet()), "x").bytes.size > 100)
    }
}

/** Mallas de apoyo para los tests de malla. */
object GtaSaVehicleBuilderForTests {
    fun boxFlat(): FlatMesh {
        val g = RwTestWriter.box(0f, 0f, 0f, 1f, 1f, 1f, listOf(RwTestWriter.TestMaterial(255, 255, 255, 255, "body")))
        val m = com.webmaletin.gtaviewer.gta.GtaSaVehicleBuilder().build("cube", MemorySource("c.dff", RwTestWriter.dff(
            listOf(RwTestWriter.TestFrame(null, -1), RwTestWriter.TestFrame("chassis", 0)), listOf(g), listOf(RwTestWriter.TestAtomic(1, 0)))), null)
        return FlatMesh.fromPart(m.parts.first())
    }
}
