package com.webmaletin.gtaviewer

import com.webmaletin.gtaviewer.export.ExportSceneBuilder
import com.webmaletin.gtaviewer.gta.GtaSaVehicleBuilder
import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.zip.MemorySource
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

/**
 * Transformación de ruedas GTA SA.
 *
 * Por qué (datos de un DFF real, Alpha/Zonda): las 4 ruedas son copias idénticas con la cara exterior (llanta, radios) en +X
 * local y matrices identidad bajo `wheel_*_dummy`; los frenos `hub_*` sí están espejados por lado. Si solo se usa la matriz del
 * frame, las ruedas izquierdas miran hacia dentro. La corrección NO depende del lado nombrado: se detecta en la geometría qué
 * lado del eje es el exterior y se gira 180° (rotación propia, sin espejo ni escalas negativas) SOLO la instancia cuya cara
 * exterior apuntaría hacia el centro del vehículo.
 */
class WheelOrientationTest {
    private val H = 0.1f      // medio ancho de la rueda (eje X local)
    private val R = 0.4f      // radio

    /** Rueda sintética asimétrica: neumático simétrico + disco de llanta en el lado exterior + disco de freno en el interior. */
    private fun wheelGeometry(outerSign: Int, pivot: FloatArray = floatArrayOf(0f, 0f, 0f), symmetric: Boolean = false): RwTestWriter.TestGeometry {
        val pos = ArrayList<Float>(); val tris = ArrayList<Int>(); val mats = ArrayList<Int>()
        fun v(x: Float, y: Float, z: Float): Int { pos += x + pivot[0]; pos += y + pivot[1]; pos += z + pivot[2]; return pos.size / 3 - 1 }
        val n = 24
        // neumático: dos anillos de radio R en x=±H
        val ring = IntArray(2 * n)
        for (k in 0 until n) { val a = 2 * PI * k / n; ring[k] = v(-H, R * cos(a).toFloat(), R * sin(a).toFloat()); ring[n + k] = v(H, R * cos(a).toFloat(), R * sin(a).toFloat()) }
        for (k in 0 until n) { val k2 = (k + 1) % n
            tris += listOf(ring[k], ring[n + k], ring[k2]); mats += 0; tris += listOf(ring[k2], ring[n + k], ring[n + k2]); mats += 0 }
        fun disc(x: Float, r: Float, segs: Int, mat: Int, up: Boolean) {
            val c = v(x, 0f, 0f); val rim = IntArray(segs) { k -> val a = 2 * PI * k / segs; v(x, r * cos(a).toFloat(), r * sin(a).toFloat()) }
            for (k in 0 until segs) { val k2 = (k + 1) % segs; if (up) tris += listOf(c, rim[k], rim[k2]) else tris += listOf(c, rim[k2], rim[k]); mats += mat }
        }
        if (symmetric) { disc(H * 0.9f, 0.3f, 48, 1, true); disc(-H * 0.9f, 0.3f, 48, 1, false) }
        else { disc(outerSign * 0.9f * H, 0.32f, 48, 1, outerSign > 0); disc(-outerSign * 0.2f * H, 0.25f, 24, 2, outerSign < 0) }
        return RwTestWriter.TestGeometry(pos.toFloatArray(), tris.toIntArray(), mats.toIntArray(), listOf(
            RwTestWriter.TestMaterial(204, 204, 204, 255, "pzero"), RwTestWriter.TestMaterial(255, 255, 255, 255, "wheel"),
            RwTestWriter.TestMaterial(180, 180, 180, 255, "brakerotor")), null)
    }

    private val txd = RwTestWriter.txd(listOf("pzero", "wheel", "brakerotor", "body").map {
        RwTestWriter.TestTexture(it, 4, 4, 21, RwTestWriter.solidBgra(4, 4, 90, 90, 90)) })

    private fun body() = RwTestWriter.box(0f, 0f, 0f, 1.8f, 4.2f, 0.8f, listOf(RwTestWriter.TestMaterial(255, 255, 255, 255, "body")))
    private val mountPos = mapOf("lf" to floatArrayOf(-1f, 1.4f, -0.3f), "rf" to floatArrayOf(1f, 1.4f, -0.3f), "lb" to floatArrayOf(-1f, -1.4f, -0.3f), "rb" to floatArrayOf(1f, -1.4f, -0.3f))
    private val ROT180 = floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)

    /** Estilo Alpha real: un atomic de rueda propio por esquina, colgado de wheel_*_dummy. */
    private fun alphaStyle(outerSign: Int, dummyRot: Map<String, FloatArray> = emptyMap(), withEmblem: Boolean = true, symmetric: Boolean = false, pivot: FloatArray = floatArrayOf(0f, 0f, 0f)): VehicleModel {
        val frames = arrayListOf(RwTestWriter.TestFrame(null, -1), RwTestWriter.TestFrame("chassis", 0))
        val geoms = arrayListOf(body())
        val atomics = arrayListOf(RwTestWriter.TestAtomic(1, 0))
        for ((side, p) in mountPos) {
            val d = frames.size; frames += RwTestWriter.TestFrame("wheel_${side}_dummy", 0, p, dummyRot[side])
            val w = frames.size; frames += RwTestWriter.TestFrame("wheel_$side", d)
            geoms += wheelGeometry(outerSign, pivot, symmetric); atomics += RwTestWriter.TestAtomic(w, geoms.size - 1)
            if (withEmblem && side == "lf") { // logo plano pegado a la cara exterior, hijo del frame de la rueda
                val e = frames.size; frames += RwTestWriter.TestFrame("emblem_lf", w)
                geoms += RwTestWriter.box(outerSign * H, 0.2f, 0.1f, 0.001f, 0.1f, 0.1f, listOf(RwTestWriter.TestMaterial(255, 255, 255, 255, "wheel")))
                atomics += RwTestWriter.TestAtomic(e, geoms.size - 1)
            }
        }
        return GtaSaVehicleBuilder().build("AlphaStyle", MemorySource("a.dff", RwTestWriter.dff(frames, geoms, atomics)), MemorySource("a.txd", txd))
    }

    /** Estilo plantilla vanilla: un único atomic `wheel` + 4 dummies sin geometría. */
    private fun templateStyle(outerSign: Int, dummyRot: Map<String, FloatArray> = emptyMap(), symmetric: Boolean = false, pivot: FloatArray = floatArrayOf(0f, 0f, 0f)): VehicleModel {
        val frames = arrayListOf(RwTestWriter.TestFrame(null, -1), RwTestWriter.TestFrame("chassis", 0), RwTestWriter.TestFrame("wheel", 0))
        for ((side, p) in mountPos) frames += RwTestWriter.TestFrame("wheel_${side}_dummy", 0, p, dummyRot[side])
        val geoms = listOf(body(), wheelGeometry(outerSign, pivot, symmetric))
        return GtaSaVehicleBuilder().build("Template", MemorySource("t.dff", RwTestWriter.dff(frames, geoms, listOf(RwTestWriter.TestAtomic(1, 0), RwTestWriter.TestAtomic(2, 1)))), MemorySource("t.txd", txd))
    }

    private fun wheel(m: VehicleModel, side: String): MeshPart = m.parts.first { p -> p.wheelSlot != null && p.frameName!!.lowercase().contains("_$side") && p.category == PartCategory.WHEEL }

    private fun rot(p: MeshPart) = floatArrayOf(p.transform[0], p.transform[1], p.transform[2], p.transform[4], p.transform[5], p.transform[6], p.transform[8], p.transform[9], p.transform[10])
    private fun isIdentityRot(p: MeshPart) = rot(p).zip(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)).all { (a, b) -> kotlin.math.abs(a - b) < 1e-5f }
    private fun isRot180Z(p: MeshPart) = rot(p).zip(ROT180).all { (a, b) -> kotlin.math.abs(a - b) < 1e-5f }

    /** Hacia dónde (en X de mundo GTA) apunta el lado exterior del modelo dado: outerSign·X local transformado. */
    private fun outwardX(p: MeshPart, outerSign: Int) = p.transform[0] * outerSign

    private fun assertProperRigid(m: VehicleModel) {
        for (p in m.parts.filter { it.category == PartCategory.WHEEL }) {
            assertTrue(Mat4.isFinite(p.transform), p.name)
            assertTrue(Mat4.determinant3(p.transform) > 0.999f, "${p.name}: sin espejo ni escala negativa (det=${Mat4.determinant3(p.transform)})")
        }
    }

    // ------------------------------------------------------------------ estilo Alpha (un atomic por rueda)

    @Test fun alphaStyle_outerPlusX_onlyLeftWheelsAreTurned_rightKeepsDffMatrix() {
        val m = alphaStyle(+1)
        assertProperRigid(m)
        for (side in listOf("rf", "rb")) assertTrue(isIdentityRot(wheel(m, side)), "$side: la rueda derecha conserva exactamente la matriz del DFF")
        for (side in listOf("lf", "lb")) assertTrue(isRot180Z(wheel(m, side)), "$side: la rueda izquierda se gira 180° sobre el eje vertical")
        for (side in mountPos.keys) {
            val w = wheel(m, side); val p = mountPos[side]!!
            assertEquals(p[0], w.transform[12], 1e-5f); assertEquals(p[1], w.transform[13], 1e-5f); assertEquals(p[2], w.transform[14], 1e-5f) // misma posición
            assertTrue(outwardX(w, +1) * (if (side.startsWith("l")) -1 else 1) > 0.99f, "$side: cara exterior hacia fuera")
        }
        assertTrue(m.warnings.any { it.contains("wheel_lf") && it.contains("180") })
        assertFalse(m.warnings.any { it.contains("wheel_rf") && it.contains("giro") })
    }

    @Test fun alphaStyle_childEmblemFollowsItsWheel() {
        val m = alphaStyle(+1)
        val emblem = m.parts.first { it.frameName == "emblem_lf" }
        val wheel = wheel(m, "lf")
        assertTrue(isRot180Z(emblem)); assertEquals(wheel.transform[12], emblem.transform[12], 1e-5f)
        // el logo está en la cara exterior (+X local): tras el giro debe quedar hacia fuera (x más negativa que el centro de la rueda izquierda)
        val c = FloatArray(3); Mat4.transformPoint(emblem.transform, H, 0f, 0f, c)
        assertTrue(c[0] < wheel.transform[12] - 0.09f, "logo exterior hacia fuera: x=${c[0]} frente a ${wheel.transform[12]}")
    }

    @Test fun alphaStyle_outerMinusX_nowTheRightWheelsAreTurned_proofThereIsNoSideHardcoding() {
        val m = alphaStyle(-1)
        for (side in listOf("lf", "lb")) assertTrue(isIdentityRot(wheel(m, side)), side)
        for (side in listOf("rf", "rb")) assertTrue(isRot180Z(wheel(m, side)), side)
        assertProperRigid(m)
    }

    // ------------------------------------------------------------------ estilo plantilla + dummies

    @Test fun template_fourInstancesDeriveFromTheSameTemplateGeometryWithCorrectTransforms() {
        val m = templateStyle(+1)
        assertProperRigid(m)
        val wheels = m.parts.filter { it.category == PartCategory.WHEEL }
        assertEquals(4, wheels.size)
        assertEquals(setOf(WheelSlot.FL, WheelSlot.FR, WheelSlot.RL, WheelSlot.RR), wheels.map { it.wheelSlot }.toSet())
        assertEquals(1, wheels.map { it.subMeshes[0].geometry.id }.toSet().size, "las 4 comparten la geometría de la plantilla, sin copiarla ni modificarla")
        // FR/RR: exactamente la matriz del dummy (sin giro arbitrario); FL/RL: giro de 180° porque la cara exterior (+X) miraría hacia dentro
        for (s in listOf(WheelSlot.FR, WheelSlot.RR)) assertTrue(isIdentityRot(wheels.first { it.wheelSlot == s }), "$s no debe girarse")
        for (s in listOf(WheelSlot.FL, WheelSlot.RL)) assertTrue(isRot180Z(wheels.first { it.wheelSlot == s }), "$s debe girarse")
        for (w in wheels) assertTrue(outwardX(w, +1) * (if (w.transform[12] > 0) 1 else -1) > 0.99f, "${w.name} exterior hacia fuera")
    }

    @Test fun template_outerMinusX_rightWheelsAreTurnedInstead() {
        val m = templateStyle(-1)
        val w = m.parts.filter { it.category == PartCategory.WHEEL }
        for (s in listOf(WheelSlot.FL, WheelSlot.RL)) assertTrue(isIdentityRot(w.first { it.wheelSlot == s }), "$s")
        for (s in listOf(WheelSlot.FR, WheelSlot.RR)) assertTrue(isRot180Z(w.first { it.wheelSlot == s }), "$s")
    }

    @Test fun template_dummyAlreadyAuthoredWithRotation_isNotRotatedTwice() {
        // Dummies izquierdos YA girados 180° por el autor del modelo; la plantilla tiene el exterior en +X ⇒ ya miran hacia fuera.
        val m = templateStyle(+1, dummyRot = mapOf("lf" to ROT180, "lb" to ROT180))
        val w = m.parts.filter { it.category == PartCategory.WHEEL }
        for (s in listOf(WheelSlot.FL, WheelSlot.RL)) assertTrue(isRot180Z(w.first { it.wheelSlot == s }), "$s conserva su rotación authorada, sin doble giro")
        for (s in listOf(WheelSlot.FR, WheelSlot.RR)) assertTrue(isIdentityRot(w.first { it.wheelSlot == s }), "$s")
        assertTrue(m.warnings.none { it.contains("giro de 180") }, "no debe añadir ninguna corrección: ${m.warnings}")
    }

    @Test fun symmetricWheelIsUndeterminedAndKeepsDffMatrices() {
        for (m in listOf(templateStyle(+1, symmetric = true), alphaStyle(+1, symmetric = true, withEmblem = false))) {
            val w = m.parts.filter { it.category == PartCategory.WHEEL }
            assertEquals(4, w.size)
            assertTrue(w.all { isIdentityRot(it) }, "sin evidencia no se inventa ninguna rotación")
            assertTrue(m.warnings.any { it.contains("no determinable") })
        }
    }

    @Test fun offCenterGeometry_rotationPreservesTheWheelCenterInWorld() {
        val pivot = floatArrayOf(0.05f, 0.03f, -0.02f)
        val m = alphaStyle(+1, withEmblem = false, pivot = pivot)
        val lf = wheel(m, "lf")
        val c = FloatArray(3); Mat4.transformPoint(lf.transform, pivot[0], pivot[1], pivot[2], c) // centro de la caja local
        val p = mountPos["lf"]!!
        assertEquals(p[0] + pivot[0], c[0], 1e-4f); assertEquals(p[1] + pivot[1], c[1], 1e-4f); assertEquals(p[2] + pivot[2], c[2], 1e-4f)
        assertTrue(isRot180Z(lf))
    }

    @Test fun materialsAndTexturesSurviveAndTemplateGeometryIsUntouched() {
        val m = templateStyle(+1)
        val w = m.parts.filter { it.category == PartCategory.WHEEL }
        for (p in w) {
            assertEquals(setOf("pzero", "wheel", "brakerotor"), p.subMeshes.map { it.material.textureName }.toSet())
            assertTrue(p.subMeshes.all { it.material.texture != null }, "${p.name}: texturas resueltas por nombre")
        }
        val g = w[0].subMeshes[0].geometry
        val ref = wheelGeometry(+1)
        assertEquals(ref.positions.size, g.positions.size)
        for (i in ref.positions.indices) assertEquals(ref.positions[i], g.positions[i], 1e-6f)
    }

    @Test fun exportedGeometryHasRimFacingOutwardOnBothSides() {
        val m = templateStyle(+1)
        val scene = ExportSceneBuilder.build(m, m.subMeshes.map { it.id }.toSet())
        // export: +X = lado izquierdo del vehículo. La llanta (material 'wheel') debe quedar en el lado EXTERIOR del centro de cada rueda.
        for (slot in WheelSlot.values().filter { it.short in listOf("FL", "FR", "RL", "RR") }) {
            val part = m.parts.first { it.wheelSlot == slot }
            val meshes = scene.meshes.filter { it.partName == part.name && it.material.texture?.fileName == "wheel.png" }
            assertTrue(meshes.isNotEmpty(), part.name)
            val all = meshes.flatMap { mm -> (0 until mm.vertexCount).map { mm.positions[it * 3] } }
            val rimX = all.average().toFloat()
            val centerX = -part.transform[12] // GTA x -> export -x
            val isLeft = slot == WheelSlot.FL || slot == WheelSlot.RL
            assertTrue(if (isLeft) rimX > centerX + 0.05f else rimX < centerX - 0.05f, "${slot.short}: llanta en x=$rimX, centro en x=$centerX")
        }
    }

    // ------------------------------------------------------------------ DFF REAL (se omite si el archivo no está disponible)

    @Test fun realAlphaDff_allFourWheelsFaceOutwardAndKeepMaterials() {
        val dir = File(System.getenv("GTA_REAL_DIR") ?: "/tmp/claude-0/real")
        val dff = File(dir, "alpha.dff"); val txdF = File(dir, "alpha.txd")
        assumeTrue(dff.exists() && txdF.exists(), "DFF real no disponible en $dir; test omitido")
        val m = GtaSaVehicleBuilder().build("Alpha", MemorySource("alpha.dff", dff.readBytes()), MemorySource("alpha.txd", txdF.readBytes()))
        val wheels = listOf("wheel_rf", "wheel_lf", "wheel_rb", "wheel_lb").associateWith { n -> m.parts.first { it.frameName == n } }
        assertProperRigid(m)
        // posiciones exactas de los puntos de montaje del DFF real
        assertEquals(1.029f, wheels["wheel_rf"]!!.transform[12], 1e-3f); assertEquals(-1.029f, wheels["wheel_lf"]!!.transform[12], 1e-3f)
        assertEquals(1.060f, wheels["wheel_rb"]!!.transform[12], 1e-3f); assertEquals(-1.060f, wheels["wheel_lb"]!!.transform[12], 1e-3f)
        // derechas: matriz idéntica a la del DFF; izquierdas: giro de 180° (el DFF real trae copias idénticas con exterior en +X local)
        assertTrue(isIdentityRot(wheels["wheel_rf"]!!) && isIdentityRot(wheels["wheel_rb"]!!))
        assertTrue(isRot180Z(wheels["wheel_lf"]!!) && isRot180Z(wheels["wheel_lb"]!!))
        // cada rueda: la cara exterior (+X local) apunta lejos del eje del vehículo
        for ((n, p) in wheels) assertTrue(outwardX(p, +1) * (if (n.contains("_r")) 1 else -1) > 0.99f, n)
        // materiales y texturas conservados
        for (p in wheels.values) {
            val tex = p.subMeshes.mapNotNull { it.material.textureName }.toSet()
            assertTrue(tex.containsAll(listOf("wheel", "brakerotor", "pzero")), "${p.name}: $tex")
            assertTrue(p.subMeshes.filter { it.material.textureName in listOf("wheel", "brakerotor", "pzero") }.all { it.material.texture != null }, "${p.name}: texturas")
        }
        // el logo hijo acompaña a su rueda
        assertTrue(isRot180Z(m.parts.first { it.frameName == "emblem_lf" }) && isIdentityRot(m.parts.first { it.frameName == "emblem_rf" }))
        // los frenos hub_* NO son ruedas y no se tocan
        for (h in listOf("hub_lf", "hub_rf", "hub_lr", "hub_rr")) assertTrue(isIdentityRot(m.parts.first { it.frameName == h }), h)
    }
}
