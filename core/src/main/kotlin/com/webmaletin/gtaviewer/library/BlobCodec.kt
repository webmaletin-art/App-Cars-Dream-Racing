package com.webmaletin.gtaviewer.library

import com.webmaletin.gtaviewer.model.GeometryData
import com.webmaletin.gtaviewer.model.MaterialInfo
import com.webmaletin.gtaviewer.model.MeshPart
import com.webmaletin.gtaviewer.txd.TextureImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

object Hashing {
    fun sha1(b: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
    fun sha1(s: String) = sha1(s.toByteArray())
}

/**
 * Formato binario de geometría de una parte (posiciones, normales, UV, sub-meshes con material). Es canónico: el mismo
 * contenido produce los mismos bytes y, por tanto, el mismo hash (así 4 ruedas iguales o una pieza reimportada no se duplican).
 * Las texturas se guardan aparte por hash de contenido.
 */
object BlobCodec {
    private const val MAGIC = 0x47434C42 // GCLB

    class SubData(val material: MaterialInfo, val textureHash: String?, val indices: IntArray, val geometryIndex: Int)
    class Decoded(val geometries: List<GeometryData>, val subs: List<SubData>)

    fun textureHash(t: TextureImage) = Hashing.sha1(t.rgba + "${t.width}x${t.height}".toByteArray())

    /** Bytes canónicos SIN comprimir (el hash se calcula sobre ellos). */
    fun encode(part: MeshPart, texHash: (TextureImage) -> String = ::textureHash): ByteArray {
        val bos = ByteArrayOutputStream()
        val o = DataOutputStream(bos)
        o.writeInt(MAGIC); o.writeInt(1)
        val geos = part.subMeshes.map { it.geometry }.distinctBy { it.id }
        o.writeInt(geos.size)
        for (g in geos) {
            o.writeInt(g.vertexCount)
            o.writeBoolean(g.hasOriginalNormals); o.writeBoolean(g.hasOriginalUvs); o.writeBoolean(g.hasVertexColors)
            for (i in 0 until g.vertexCount * 3) o.writeFloat(g.positions[i])
            for (i in 0 until g.vertexCount * 3) o.writeFloat(g.normals[i])
            for (i in 0 until g.vertexCount * 2) o.writeFloat(g.uvs[i])
        }
        o.writeInt(part.subMeshes.size)
        for (sm in part.subMeshes) {
            val m = sm.material
            o.writeInt(geos.indexOfFirst { it.id == sm.geometry.id })
            o.writeUTF(m.name); o.writeUTF(m.textureName ?: ""); o.writeUTF(m.texture?.let { texHash(it) } ?: "")
            o.writeInt(m.r); o.writeInt(m.g); o.writeInt(m.b); o.writeInt(m.a); o.writeInt(m.paintSlot)
            o.writeBoolean(m.hasEnvMap); o.writeFloat(m.metallic); o.writeFloat(m.roughness); o.writeBoolean(m.glass)
            o.writeInt(sm.indices.size)
            for (i in sm.indices) o.writeInt(i)
        }
        o.flush()
        return bos.toByteArray()
    }

    fun compress(raw: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        DeflaterOutputStream(bos, Deflater(2)).use { it.write(raw) }
        return bos.toByteArray()
    }

    fun decompress(z: ByteArray): ByteArray = InflaterInputStream(ByteArrayInputStream(z)).use { it.readBytes() }

    fun decode(raw: ByteArray, nextGeometryId: () -> Int, texture: (String) -> TextureImage?): Decoded {
        val i = DataInputStream(ByteArrayInputStream(raw))
        require(i.readInt() == MAGIC) { "Blob de geometría inválido" }
        require(i.readInt() == 1) { "Versión de blob no soportada" }
        val nGeo = i.readInt()
        val geos = ArrayList<GeometryData>(nGeo)
        repeat(nGeo) {
            val vc = i.readInt()
            val hn = i.readBoolean(); val hu = i.readBoolean(); val hc = i.readBoolean()
            val pos = FloatArray(vc * 3) { i.readFloat() }
            val nor = FloatArray(vc * 3) { i.readFloat() }
            val uv = FloatArray(vc * 2) { i.readFloat() }
            geos += GeometryData(nextGeometryId(), vc, pos, nor, uv, hn, hu, hc)
        }
        val nSub = i.readInt()
        val subs = ArrayList<SubData>(nSub)
        repeat(nSub) {
            val gi = i.readInt()
            val name = i.readUTF(); val texName = i.readUTF().ifEmpty { null }; val th = i.readUTF().ifEmpty { null }
            val r = i.readInt(); val g = i.readInt(); val b = i.readInt(); val a = i.readInt(); val slot = i.readInt()
            val env = i.readBoolean(); val metal = i.readFloat(); val rough = i.readFloat(); val glass = i.readBoolean()
            val n = i.readInt()
            val idx = IntArray(n) { i.readInt() }
            val tex = th?.let { texture(it) }
            val key = "${texName?.lowercase() ?: ""}|$r,$g,$b,$a"
            subs += SubData(MaterialInfo(key, name, r, g, b, a, texName, tex, slot, env, metal, rough, glass), th, idx, gi)
        }
        return Decoded(geos, subs)
    }

    // ---- texturas

    fun encodeTexture(t: TextureImage): ByteArray {
        val bos = ByteArrayOutputStream()
        val o = DataOutputStream(bos)
        o.writeInt(t.width); o.writeInt(t.height); o.writeUTF(t.name); o.writeUTF(t.sourceFormat)
        o.write(t.rgba)
        o.flush()
        return compress(bos.toByteArray())
    }

    fun decodeTexture(z: ByteArray): TextureImage {
        val i = DataInputStream(ByteArrayInputStream(decompress(z)))
        val w = i.readInt(); val h = i.readInt(); val name = i.readUTF(); val fmt = i.readUTF()
        val rgba = ByteArray(w * h * 4); i.readFully(rgba)
        return TextureImage(name, w, h, rgba, com.webmaletin.gtaviewer.txd.TextureDecoder.hasTransparency(rgba), fmt)
    }
}
