package com.webmaletin.gtaviewer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Escritor mínimo de RenderWare para generar DFF/TXD de prueba conformes al formato SA PC. */
object RwTestWriter {
    const val SA_VERSION = 0x1803FFFF

    class Buf {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v) }
        fun u16(v: Int) = apply { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun i32(v: Int) = apply { for (i in 0..3) out.write((v shr (8 * i)) and 0xFF) }
        fun f32(v: Float) = i32(java.lang.Float.floatToRawIntBits(v))
        fun bytes(b: ByteArray) = apply { out.write(b) }
        fun str(s: String, fixed: Int) = apply {
            val b = s.toByteArray(Charsets.ISO_8859_1)
            out.write(b, 0, minOf(b.size, fixed)); repeat(fixed - minOf(b.size, fixed)) { out.write(0) }
        }
        fun toBytes(): ByteArray = out.toByteArray()
    }

    fun chunk(type: Int, payload: ByteArray, version: Int = SA_VERSION): ByteArray =
        Buf().i32(type).i32(payload.size).i32(version).bytes(payload).toBytes()

    fun chunk(type: Int, vararg children: ByteArray): ByteArray {
        val b = Buf(); children.forEach { b.bytes(it) }; return chunk(type, b.toBytes())
    }

    fun string(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.ISO_8859_1)
        val padded = ((raw.size + 1) + 3) / 4 * 4
        return chunk(0x02, Buf().str(s, padded).toBytes())
    }

    fun struct(b: Buf) = chunk(0x01, b.toBytes())
    fun extension(vararg children: ByteArray) = chunk(0x03, *children)

    // ------------------------------------------------------------------ DFF

    class TestFrame(val name: String?, val parent: Int, val pos: FloatArray = floatArrayOf(0f, 0f, 0f), val rot: FloatArray? = null)
    class TestMaterial(val r: Int, val g: Int, val b: Int, val a: Int = 255, val texture: String? = null)
    class TestGeometry(
        val positions: FloatArray, val triangles: IntArray, val triMats: IntArray,
        val materials: List<TestMaterial>, val uvs: FloatArray? = null, val normals: FloatArray? = null,
        val strip: Boolean = false,
    )
    class TestAtomic(val frame: Int, val geometry: Int, val flags: Int = 4)

    /** Caja con 8 vértices y 12 triángulos, triángulos 0..5 con material 0 y 6..11 con material 1. */
    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, mats: List<TestMaterial>, withUv: Boolean = true): TestGeometry {
        val p = FloatArray(24)
        var k = 0
        for (i in 0..7) {
            p[k++] = cx + (if (i and 1 != 0) sx else -sx) / 2
            p[k++] = cy + (if (i and 2 != 0) sy else -sy) / 2
            p[k++] = cz + (if (i and 4 != 0) sz else -sz) / 2
        }
        val tris = intArrayOf(
            0, 2, 1, 1, 2, 3, 4, 5, 6, 5, 7, 6, 0, 1, 4, 1, 5, 4,
            2, 6, 3, 3, 6, 7, 0, 4, 2, 2, 4, 6, 1, 3, 5, 3, 7, 5,
        )
        val tm = IntArray(12) { if (it < 6 || mats.size == 1) 0 else 1 }
        val uv = if (withUv) FloatArray(16) { (it % 4) / 3f } else null
        return TestGeometry(p, tris, tm, mats, uv)
    }

    fun dff(frames: List<TestFrame>, geoms: List<TestGeometry>, atomics: List<TestAtomic>, version: Int = SA_VERSION): ByteArray {
        // FrameList
        val fs = Buf().i32(frames.size)
        frames.forEach { f ->
            val rot = f.rot ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            rot.forEach { fs.f32(it) }; f.pos.forEach { fs.f32(it) }
            fs.i32(f.parent).i32(0)
        }
        val frameExts = frames.map { f ->
            if (f.name != null) extension(chunk(0x253F2FE, f.name.toByteArray(Charsets.ISO_8859_1), version)) else extension()
        }
        val frameList = chunk(0x0E, listOf(struct(fs)) + frameExts, version)

        val geometryChunks = geoms.map { geometry(it, version) }
        val geomList = chunk(0x1A, listOf(struct(Buf().i32(geoms.size))) + geometryChunks, version)

        val atomicChunks = atomics.map {
            chunk(0x14, listOf(struct(Buf().i32(it.frame).i32(it.geometry).i32(it.flags).i32(0)), extension()), version)
        }
        return chunk(0x10, listOf(struct(Buf().i32(atomics.size).i32(0).i32(0)), frameList, geomList) + atomicChunks + listOf(extension()), version)
    }

    private fun chunk(type: Int, children: List<ByteArray>, version: Int): ByteArray {
        val b = Buf(); children.forEach { b.bytes(it) }; return chunk(type, b.toBytes(), version)
    }

    private fun geometry(g: TestGeometry, version: Int): ByteArray {
        val nv = g.positions.size / 3
        val nt = g.triangles.size / 3
        var flags = 0x02 or 0x10 or 0x20 or 0x40
        if (g.uvs != null) flags = flags or 0x04
        if (g.strip) flags = flags or 0x01
        val s = Buf().i32(flags).i32(nt).i32(nv).i32(1)
        if (g.uvs != null) g.uvs.forEach { s.f32(it) }
        for (t in 0 until nt) {
            s.u16(g.triangles[t * 3 + 1]).u16(g.triangles[t * 3]).u16(g.triMats[t]).u16(g.triangles[t * 3 + 2])
        }
        s.f32(0f).f32(0f).f32(0f).f32(10f).i32(1).i32(1)
        g.positions.forEach { s.f32(it) }
        val normals = g.normals ?: computeNormals(g)
        normals.forEach { s.f32(it) }

        val matChildren = g.materials.map { m ->
            val ms = Buf().i32(0).u8(m.r).u8(m.g).u8(m.b).u8(m.a).i32(0).i32(if (m.texture != null) 1 else 0).f32(1f).f32(1f).f32(1f)
            val kids = ArrayList<ByteArray>()
            kids += struct(ms)
            if (m.texture != null) {
                kids += chunk(0x06, listOf(struct(Buf().i32(0x1106)), string(m.texture), string(""), extension()), version)
            }
            kids += extension()
            chunk(0x07, kids, version)
        }
        val matList = chunk(0x08, listOf(struct(Buf().apply { i32(g.materials.size); g.materials.indices.forEach { _ -> i32(-1) } })) + matChildren, version)

        // BinMesh (tri list) por material
        val bm = Buf()
        val groups = g.materials.indices.map { mi -> (0 until nt).filter { g.triMats[it] == mi } }
        bm.i32(0).i32(groups.size).i32(nt * 3)
        groups.forEachIndexed { mi, tl ->
            bm.i32(tl.size * 3).i32(mi)
            tl.forEach { t -> bm.i32(g.triangles[t * 3]).i32(g.triangles[t * 3 + 1]).i32(g.triangles[t * 3 + 2]) }
        }
        val ext = extension(chunk(0x50E, bm.toBytes(), version))
        return chunk(0x0F, listOf(struct(s), matList, ext), version)
    }

    private fun computeNormals(g: TestGeometry): FloatArray {
        val n = FloatArray(g.positions.size)
        for (t in 0 until g.triangles.size / 3) {
            val a = g.triangles[t * 3]; val b = g.triangles[t * 3 + 1]; val c = g.triangles[t * 3 + 2]
            val ux = g.positions[b * 3] - g.positions[a * 3]; val uy = g.positions[b * 3 + 1] - g.positions[a * 3 + 1]; val uz = g.positions[b * 3 + 2] - g.positions[a * 3 + 2]
            val vx = g.positions[c * 3] - g.positions[a * 3]; val vy = g.positions[c * 3 + 1] - g.positions[a * 3 + 1]; val vz = g.positions[c * 3 + 2] - g.positions[a * 3 + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (i in intArrayOf(a, b, c)) { n[i * 3] += nx; n[i * 3 + 1] += ny; n[i * 3 + 2] += nz }
        }
        for (i in 0 until n.size / 3) {
            val l = Math.sqrt((n[i * 3] * n[i * 3] + n[i * 3 + 1] * n[i * 3 + 1] + n[i * 3 + 2] * n[i * 3 + 2]).toDouble()).toFloat()
            if (l > 0) { n[i * 3] /= l; n[i * 3 + 1] /= l; n[i * 3 + 2] /= l }
        }
        return n
    }

    /** Vehículo SA típico: chassis (2 materiales), wheel plantilla + 4 dummies, puerta, capó, variante dañada. */
    fun sampleVehicleDff(): ByteArray {
        val body = TestMaterial(60, 255, 0, 255, "body")
        val glass = TestMaterial(200, 220, 255, 120, "glass")
        val tyre = TestMaterial(255, 255, 255, 255, "tyre")
        val frames = listOf(
            TestFrame(null, -1),                                              // 0 raíz
            TestFrame("chassis", 0),                                          // 1
            TestFrame("wheel", 0),                                            // 2 plantilla
            TestFrame("wheel_lf_dummy", 0, floatArrayOf(-0.9f, 1.4f, -0.3f)), // 3
            TestFrame("wheel_rf_dummy", 0, floatArrayOf(0.9f, 1.4f, -0.3f)),  // 4
            TestFrame("wheel_lb_dummy", 0, floatArrayOf(-0.9f, -1.4f, -0.3f)),// 5
            TestFrame("wheel_rb_dummy", 0, floatArrayOf(0.9f, -1.4f, -0.3f)), // 6
            TestFrame("door_lf_dummy", 0, floatArrayOf(-0.9f, 0.3f, 0.3f)),   // 7
            TestFrame("bonnet_dummy", 0, floatArrayOf(0f, 1.5f, 0.6f)),       // 8
            TestFrame("chassis_dam", 0),                                      // 9 variante dañada
        )
        val geoms = listOf(
            box(0f, 0f, 0f, 1.8f, 4.2f, 1.0f, listOf(body, glass)),
            box(0f, 0f, 0f, 0.3f, 0.7f, 0.7f, listOf(tyre)),
            box(0f, 0f, 0f, 0.1f, 1.0f, 0.8f, listOf(body)),
            box(0f, 0f, 0f, 1.6f, 1.0f, 0.1f, listOf(body)),
            box(0f, 0f, 0f, 1.8f, 4.2f, 1.0f, listOf(body)),
        )
        val atomics = listOf(
            TestAtomic(1, 0), TestAtomic(2, 1), TestAtomic(7, 2), TestAtomic(8, 3), TestAtomic(9, 4),
        )
        return dff(frames, geoms, atomics)
    }

    // ------------------------------------------------------------------ TXD

    class TestTexture(val name: String, val w: Int, val h: Int, val d3dFormat: Int, val data: ByteArray, val alpha: Boolean = false,
                      val rasterFormat: Int = 0x0500, val depth: Int = 32, val platform: Int = 9)

    fun txd(textures: List<TestTexture>): ByteArray {
        val natives = textures.map { t ->
            val s = Buf().i32(t.platform).i32(0x1106).str(t.name, 32).str("", 32).i32(t.rasterFormat)
            if (t.platform == 9) s.i32(t.d3dFormat) else s.i32(if (t.alpha) 1 else 0)
            s.u16(t.w).u16(t.h).u8(t.depth).u8(1).u8(4)
            s.u8(if (t.platform == 9) (if (t.alpha) 1 else 0) else if (t.d3dFormat == 0x31545844) 1 else if (t.d3dFormat == 0x35545844) 5 else 0)
            s.i32(t.data.size).bytes(t.data)
            chunk(0x15, listOf(struct(s), extension()), SA_VERSION)
        }
        return chunk(0x16, listOf(struct(Buf().u16(textures.size).u16(0))) + natives + listOf(extension()), SA_VERSION)
    }

    fun solidBgra(w: Int, h: Int, r: Int, g: Int, b: Int, a: Int = 255): ByteArray {
        val d = ByteArray(w * h * 4)
        for (i in 0 until w * h) { d[i * 4] = b.toByte(); d[i * 4 + 1] = g.toByte(); d[i * 4 + 2] = r.toByte(); d[i * 4 + 3] = a.toByte() }
        return d
    }

    fun rgb565(r: Int, g: Int, b: Int) = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)

    fun solidDxt1(w: Int, h: Int, r: Int, g: Int, b: Int): ByteArray {
        val blocks = ((w + 3) / 4) * ((h + 3) / 4)
        val bb = Buf()
        repeat(blocks) { bb.u16(rgb565(r, g, b)).u16(0).i32(0) }
        return bb.toBytes()
    }

    fun solidDxt5(w: Int, h: Int, r: Int, g: Int, b: Int, alpha: Int): ByteArray {
        val blocks = ((w + 3) / 4) * ((h + 3) / 4)
        val bb = Buf()
        repeat(blocks) {
            // alpha0=alpha1=alpha, índices 0 → alpha
            bb.u8(alpha).u8(alpha); repeat(6) { bb.u8(0) }
            bb.u16(rgb565(r, g, b)).u16(0).i32(0)
        }
        return bb.toBytes()
    }

    // ------------------------------------------------------------------ ZIP

    fun zip(files: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            files.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() }
        }
        return bos.toByteArray()
    }
}
