package com.webmaletin.gtaviewer.glb

import com.webmaletin.gtaviewer.export.*
import java.io.ByteArrayOutputStream

/**
 * Escritor glTF 2.0 binario (GLB) propio. Contiene geometría, normales, UV, materiales PBR (color base + textura),
 * texturas PNG EMBEBIDAS y un nodo por pieza (las transformaciones de pieza ya están horneadas en los vértices).
 */
class GlbExporter : SceneExporter {
    override val format = ExportFormat.GLB

    private class Bin {
        val out = ByteArrayOutputStream()
        fun align4() { while (out.size() % 4 != 0) out.write(0) }
        val size get() = out.size()
    }

    override fun export(scene: ExportScene, baseName: String): ExportOutput {
        val bin = Bin()
        val views = StringBuilder(); var viewCount = 0
        val accessors = StringBuilder(); var accCount = 0

        fun addView(bytes: ByteArray, target: Int?): Int {
            bin.align4()
            val off = bin.size
            bin.out.write(bytes)
            if (viewCount > 0) views.append(',')
            views.append("{\"buffer\":0,\"byteOffset\":$off,\"byteLength\":${bytes.size}")
            if (target != null) views.append(",\"target\":$target")
            views.append('}')
            return viewCount++
        }

        fun addAccessor(view: Int, comp: Int, count: Int, type: String, minMax: Pair<FloatArray, FloatArray>? = null): Int {
            if (accCount > 0) accessors.append(',')
            accessors.append("{\"bufferView\":$view,\"componentType\":$comp,\"count\":$count,\"type\":\"$type\"")
            if (minMax != null) {
                accessors.append(",\"min\":[${minMax.first.joinToString(",") { exact(it) }}]")
                accessors.append(",\"max\":[${minMax.second.joinToString(",") { exact(it) }}]")
            }
            accessors.append('}')
            return accCount++
        }

        // Imágenes / texturas
        val images = StringBuilder(); val textures = StringBuilder()
        val texIndex = HashMap<ExportTexture, Int>()
        scene.textures.forEachIndexed { i, t ->
            val v = addView(t.png, null)
            if (i > 0) { images.append(','); textures.append(',') }
            images.append("{\"bufferView\":$v,\"mimeType\":\"image/png\",\"name\":${q(t.fileName.removeSuffix(".png"))}}")
            textures.append("{\"sampler\":0,\"source\":$i,\"name\":${q(t.fileName.removeSuffix(".png"))}}")
            texIndex[t] = i
        }

        // Materiales
        val materials = StringBuilder()
        val matIndex = HashMap<ExportMaterial, Int>()
        scene.materials.forEachIndexed { i, m ->
            if (i > 0) materials.append(',')
            materials.append("{\"name\":${q(m.name)},\"pbrMetallicRoughness\":{\"baseColorFactor\":[${Num.f(m.r)},${Num.f(m.g)},${Num.f(m.b)},${Num.f(m.a)}]")
            m.texture?.let { materials.append(",\"baseColorTexture\":{\"index\":${texIndex[it]}}") }
            materials.append(",\"metallicFactor\":0,\"roughnessFactor\":0.75},\"doubleSided\":true")
            materials.append(",\"alphaMode\":\"${if (m.transparent) "BLEND" else "OPAQUE"}\"}")
            matIndex[m] = i
        }

        // Mallas: una por pieza, con una primitiva por (sub-mesh/material)
        val meshes = StringBuilder(); val nodes = StringBuilder()
        var meshCount = 0
        val byPart = scene.meshes.groupBy { it.partName }
        for ((partName, list) in byPart) {
            val prims = StringBuilder()
            for ((pi, m) in list.withIndex()) {
                val pos = floatBytes(m.positions)
                val nor = floatBytes(m.normals)
                val uv = floatBytes(m.uvs)
                val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
                for (v in 0 until m.vertexCount) for (c in 0..2) {
                    val x = m.positions[v * 3 + c]; if (x < mn[c]) mn[c] = x; if (x > mx[c]) mx[c] = x
                }
                val aPos = addAccessor(addView(pos, 34962), 5126, m.vertexCount, "VEC3", mn to mx)
                val aNor = addAccessor(addView(nor, 34962), 5126, m.vertexCount, "VEC3")
                val aUv = addAccessor(addView(uv, 34962), 5126, m.vertexCount, "VEC2")
                val small = m.vertexCount < 65535
                val idxBytes = if (small) shortBytes(m.indices) else intBytes(m.indices)
                val aIdx = addAccessor(addView(idxBytes, 34963), if (small) 5123 else 5125, m.indices.size, "SCALAR")
                if (pi > 0) prims.append(',')
                prims.append("{\"attributes\":{\"POSITION\":$aPos,\"NORMAL\":$aNor,\"TEXCOORD_0\":$aUv},\"indices\":$aIdx,\"material\":${matIndex[m.material]}}")
            }
            if (meshCount > 0) { meshes.append(','); nodes.append(',') }
            meshes.append("{\"name\":${q(partName)},\"primitives\":[$prims]}")
            nodes.append("{\"name\":${q(partName)},\"mesh\":$meshCount}")
            meshCount++
        }

        val childList = (1..meshCount).joinToString(",")
        val json = StringBuilder()
        json.append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"GTA Vehicle Viewer\"},")
        json.append("\"scene\":0,\"scenes\":[{\"name\":${q(scene.name)},\"nodes\":[0]}],")
        json.append("\"nodes\":[{\"name\":${q(scene.name)}${if (meshCount > 0) ",\"children\":[$childList]" else ""}}${if (meshCount > 0) ",$nodes" else ""}]")
        if (meshCount > 0) json.append(",\"meshes\":[$meshes]")
        if (scene.materials.isNotEmpty()) json.append(",\"materials\":[$materials]")
        if (scene.textures.isNotEmpty()) {
            json.append(",\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":10497,\"wrapT\":10497}]")
            json.append(",\"textures\":[$textures],\"images\":[$images]")
        }
        if (accCount > 0) json.append(",\"accessors\":[$accessors]")
        if (viewCount > 0) json.append(",\"bufferViews\":[$views]")
        bin.align4()
        json.append(",\"buffers\":[{\"byteLength\":${bin.size}}]}")

        var jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - jsonBytes.size % 4) % 4
        val binBytes = bin.out.toByteArray()
        val total = 12 + 8 + jsonBytes.size + jsonPad + 8 + binBytes.size
        val out = ByteArrayOutputStream(total)
        fun u32(v: Int) { out.write(v and 255); out.write((v shr 8) and 255); out.write((v shr 16) and 255); out.write((v ushr 24) and 255) }
        u32(0x46546C67); u32(2); u32(total)
        u32(jsonBytes.size + jsonPad); u32(0x4E4F534A); out.write(jsonBytes); repeat(jsonPad) { out.write(0x20) }
        u32(binBytes.size); u32(0x004E4942); out.write(binBytes)

        val fileName = ExportNaming.fileName(baseName, ExportFormat.GLB)
        return ExportOutput(fileName, ExportFormat.GLB.mime, out.toByteArray(), scene.warnings, null, listOf(fileName))
    }

    /** Valor exacto del float como double: glTF exige que min/max coincidan con los datos del buffer. */
    private fun exact(f: Float): String = (if (f.isFinite()) f else 0f).toDouble().toString()

    private fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append("\\u%04x".format(c.code)); else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    private fun floatBytes(a: FloatArray): ByteArray {
        val b = java.nio.ByteBuffer.allocate(a.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (f in a) b.putFloat(if (f.isFinite()) f else 0f)
        return b.array()
    }

    private fun shortBytes(a: IntArray): ByteArray {
        val b = java.nio.ByteBuffer.allocate(a.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in a) b.putShort(i.toShort())
        return b.array()
    }

    private fun intBytes(a: IntArray): ByteArray {
        val b = java.nio.ByteBuffer.allocate(a.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in a) b.putInt(i)
        return b.array()
    }
}
