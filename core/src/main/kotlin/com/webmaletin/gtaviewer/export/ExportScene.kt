package com.webmaletin.gtaviewer.export

import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.txd.TextureImage
import kotlin.math.sqrt

/** Color de pintura que sustituye los marcadores de SA (primario verde 60,255,0 / secundario magenta 255,0,175). */
data class PaintScheme(val primary: Int = 0xC9CDD2, val secondary: Int = 0x2B2F36) {
    fun colorFor(slot: Int): Int? = when (slot) { 1 -> primary; 2 -> secondary; else -> null }
}

class ExportTexture(val fileName: String, val image: TextureImage) {
    val png: ByteArray by lazy { PngEncoder.encode(image.width, image.height, image.rgba) }
}

class ExportMaterial(
    val name: String,
    val r: Float, val g: Float, val b: Float, val a: Float,
    val texture: ExportTexture?,
    val transparent: Boolean,
    val metallic: Float = 0f,
    val roughness: Float = 0.75f,
)

/** Malla lista para escribir: posiciones/normales ya en espacio de exportación (Y arriba, frente +Z), vértices compactados. */
class ExportMesh(
    val partName: String,
    val name: String,
    val material: ExportMaterial,
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val indices: IntArray,
) {
    val vertexCount get() = positions.size / 3
    val triangleCount get() = indices.size / 3
}

class ExportScene(
    val name: String,
    val meshes: List<ExportMesh>,
    val materials: List<ExportMaterial>,
    val textures: List<ExportTexture>,
    val warnings: List<String>,
) {
    val totalTriangles get() = meshes.sumOf { it.triangleCount }
    val totalVertices get() = meshes.sumOf { it.vertexCount }
}

object ExportSceneBuilder {
    /**
     * Aplana el modelo editado a una escena de exportación. Solo se incluyen los sub-meshes de [subIds] (los visibles
     * o la selección). Las transformaciones de cada pieza (incluidas las instancias de rueda) se hornean en los vértices.
     * @param scale factor uniforme de escena (1 = metros originales).
     */
    fun build(model: VehicleModel, subIds: Set<Int>, paint: PaintScheme = PaintScheme(), scale: Float = 1f, name: String = model.name): ExportScene {
        val warnings = ArrayList<String>()
        val materials = LinkedHashMap<String, ExportMaterial>()
        val textures = LinkedHashMap<String, ExportTexture>()
        val usedFileNames = HashSet<String>()
        val meshes = ArrayList<ExportMesh>()

        fun textureFor(mi: MaterialInfo): ExportTexture? {
            val img = mi.texture ?: return null
            val key = mi.textureName!!.lowercase()
            return textures.getOrPut(key) {
                var base = sanitize(mi.textureName)
                var file = "$base.png"; var n = 2
                while (!usedFileNames.add(file.lowercase())) { file = "${base}_$n.png"; n++ }
                ExportTexture(file, img)
            }
        }

        fun materialFor(mi: MaterialInfo): ExportMaterial {
            val painted = paint.colorFor(mi.paintSlot)
            val key = mi.key + "|" + (painted ?: -1) + "|" + mi.metallic + "|" + mi.roughness + "|" + mi.a
            return materials.getOrPut(key) {
                val (r, g, b) = if (painted != null) Triple((painted shr 16) and 255, (painted shr 8) and 255, painted and 255) else Triple(mi.r, mi.g, mi.b)
                val tex = textureFor(mi)
                var nm = sanitize(mi.name)
                if (materials.values.any { it.name == nm }) nm += "_" + (materials.size + 1)
                ExportMaterial(nm, r / 255f, g / 255f, b / 255f, mi.a / 255f, tex, mi.transparent, mi.metallic, mi.roughness)
            }
        }

        val S = Mat4.multiply(Mat4.scale(scale), Mat4.GTA_TO_VIEW)
        val tmp = FloatArray(3)
        for (part in model.parts) {
            val subs = part.subMeshes.filter { it.id in subIds }
            if (subs.isEmpty()) continue
            val m = Mat4.multiply(S, part.transform)
            val flip = Mat4.determinant3(m) < 0f
            for ((k, sm) in subs.withIndex()) {
                val remap = HashMap<Int, Int>()
                val order = ArrayList<Int>()
                for (i in sm.indices) if (remap.putIfAbsent(i, order.size) == null) order.add(i)
                val nv = order.size
                val pos = FloatArray(nv * 3); val nor = FloatArray(nv * 3); val uv = FloatArray(nv * 2)
                val g = sm.geometry
                for ((newI, oldI) in order.withIndex()) {
                    Mat4.transformPoint(m, g.positions[oldI * 3], g.positions[oldI * 3 + 1], g.positions[oldI * 3 + 2], tmp)
                    pos[newI * 3] = tmp[0]; pos[newI * 3 + 1] = tmp[1]; pos[newI * 3 + 2] = tmp[2]
                    Mat4.transformDir(m, g.normals[oldI * 3], g.normals[oldI * 3 + 1], g.normals[oldI * 3 + 2], tmp)
                    val l = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1] + tmp[2] * tmp[2]).let { if (it < 1e-12f) 1f else it }
                    val sgn = if (flip) -1f else 1f
                    nor[newI * 3] = sgn * tmp[0] / l; nor[newI * 3 + 1] = sgn * tmp[1] / l; nor[newI * 3 + 2] = sgn * tmp[2] / l
                    uv[newI * 2] = g.uvs[oldI * 2]; uv[newI * 2 + 1] = g.uvs[oldI * 2 + 1]
                }
                val idx = IntArray(sm.indices.size) { remap[sm.indices[it]]!! }
                if (flip) { var i = 0; while (i + 2 < idx.size) { val t = idx[i + 1]; idx[i + 1] = idx[i + 2]; idx[i + 2] = t; i += 3 } }
                val mat = materialFor(sm.material)
                val meshName = if (part.subMeshes.size == 1) part.name else "${part.name}_${mat.name}"
                meshes += ExportMesh(part.name, meshName, mat, pos, nor, uv, idx)
                if (k >= 0 && sm.material.textureMissing) warnings += "Material '${sm.material.textureName}' sin textura (se exporta el color)"
            }
        }
        val usedMaterials = meshes.map { it.material }.distinct()
        val usedTextures = usedMaterials.mapNotNull { it.texture }.distinct()
        return ExportScene(name, meshes, usedMaterials, usedTextures, warnings.distinct())
    }

    fun sanitize(s: String): String {
        val r = s.trim().replace(Regex("[^A-Za-z0-9_\\-]+"), "_").trim('_')
        return r.ifEmpty { "material" }
    }
}
