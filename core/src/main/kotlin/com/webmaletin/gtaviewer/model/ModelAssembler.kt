package com.webmaletin.gtaviewer.model

import com.webmaletin.gtaviewer.txd.TextureImage

/** Construye un [VehicleModel] a partir de partes ya hechas (catálogo, proyectos): materiales, texturas, caja y validación. */
object ModelAssembler {
    fun partBounds(p: MeshPart): Bounds {
        val b = Bounds.empty()
        val m = Mat4.multiply(Mat4.GTA_TO_VIEW, p.transform)
        val t = FloatArray(3)
        for (sm in p.subMeshes) {
            val pos = sm.geometry.positions
            for (i in sm.indices) { Mat4.transformPoint(m, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2], t); b.expand(t[0], t[1], t[2]) }
        }
        return b
    }

    fun assemble(
        name: String, moduleId: String, parts: List<MeshPart>, warnings: List<String> = emptyList(),
        dffName: String = "", txdName: String? = null, notes: List<String> = emptyList(), validate: Boolean = true,
    ): VehicleModel {
        val mats = LinkedHashMap<String, MaterialInfo>()
        val texs = LinkedHashMap<String, TextureImage>()
        for (p in parts) for (sm in p.subMeshes) {
            mats.putIfAbsent(sm.material.key, sm.material)
            val m = sm.material
            if (m.texture != null && m.textureName != null) texs.putIfAbsent(m.textureName.lowercase(), m.texture)
        }
        val found = mats.values.filter { it.texture != null }.mapNotNull { it.textureName }.distinct()
        val missing = mats.values.filter { it.textureMissing }.mapNotNull { it.textureName }.distinct()
        val diag = ModelDiagnostics(
            dffFileName = dffName.ifEmpty { name }, txdFileName = txdName, dffSize = 0, txdSize = 0,
            dffParser = "Catálogo/Proyecto (blobs guardados)", txdParser = null, rwVersion = "-",
            dffValid = true, txdValid = true, geometryCount = parts.flatMap { p -> p.subMeshes.map { it.geometry.id } }.distinct().size,
            atomicCount = parts.size, frameCount = 0, materialCount = mats.size, texturesInTxd = texs.size,
            texturesFound = found, texturesMissing = missing, textureErrors = emptyList(), chunkIssues = emptyList(), notes = notes,
        )
        val validation = if (validate) ModelValidator.validate(parts) { partBounds(it) } else ValidationReport(false, emptyList(), null, 1f)
        return VehicleModel(name, moduleId, parts, mats.values.toList(), texs, diag, validation, warnings)
    }

    /**
     * Caja "robusta": ignora el 0,5 % de vértices más extremos por eje (espejos, antenas, vértices sueltos) para que las
     * dimensiones reflejen el vehículo y no sus accesorios salientes.
     */
    fun robustBounds(parts: List<MeshPart>, lo: Float = 0.005f, hi: Float = 0.995f): Bounds? {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val zs = ArrayList<Float>()
        val t = FloatArray(3)
        for (p in parts) {
            val m = Mat4.multiply(Mat4.GTA_TO_VIEW, p.transform)
            val seen = HashSet<Long>()
            for (sm in p.subMeshes) {
                val pos = sm.geometry.positions
                for (i in sm.indices) {
                    if (!seen.add((sm.geometry.id.toLong() shl 32) or i.toLong())) continue
                    Mat4.transformPoint(m, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2], t)
                    xs += t[0]; ys += t[1]; zs += t[2]
                }
            }
        }
        if (xs.isEmpty()) return null
        fun pct(l: ArrayList<Float>, q: Float): Float { l.sort(); return l[(q * (l.size - 1)).toInt().coerceIn(0, l.size - 1)] }
        return Bounds(floatArrayOf(pct(xs, lo), pct(ys, lo), pct(zs, lo)), floatArrayOf(pct(xs, hi), pct(ys, hi), pct(zs, hi)))
    }
}
