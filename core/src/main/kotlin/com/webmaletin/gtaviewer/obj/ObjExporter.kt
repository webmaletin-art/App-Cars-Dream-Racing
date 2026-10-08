package com.webmaletin.gtaviewer.obj

import com.webmaletin.gtaviewer.export.*

/**
 * Wavefront OBJ + MTL + texturas PNG en un ZIP. Cada cara referencia v/vt/vn; cada grupo usa `usemtl`
 * y el MTL apunta (map_Kd) a textures/<archivo>.png que sí está en el ZIP.
 * El eje V se invierte (OBJ tiene origen abajo-izquierda; RenderWare arriba-izquierda).
 */
class ObjExporter : SceneExporter {
    override val format = ExportFormat.OBJ

    override fun export(scene: ExportScene, baseName: String): ExportOutput {
        val objName = "$baseName.obj"
        val mtlName = "$baseName.mtl"

        val obj = StringBuilder(scene.totalVertices * 40)
        obj.append("# GTA Vehicle Viewer — OBJ\n")
        obj.append("# Ejes: Y arriba, frente del vehículo hacia +Z, unidades en metros\n")
        obj.append("mtllib ").append(mtlName).append('\n')
        var base = 1
        var lastMat: ExportMaterial? = null
        var lastGroup: String? = null
        for (m in scene.meshes) {
            if (m.partName != lastGroup) { obj.append("o ").append(ExportSceneBuilder.sanitize(m.partName)).append('\n'); lastGroup = m.partName }
            obj.append("g ").append(ExportSceneBuilder.sanitize(m.name)).append('\n')
            for (v in 0 until m.vertexCount) obj.append("v ").append(Num.f(m.positions[v * 3])).append(' ').append(Num.f(m.positions[v * 3 + 1])).append(' ').append(Num.f(m.positions[v * 3 + 2])).append('\n')
            for (v in 0 until m.vertexCount) obj.append("vt ").append(Num.f(m.uvs[v * 2])).append(' ').append(Num.f(1f - m.uvs[v * 2 + 1])).append('\n')
            for (v in 0 until m.vertexCount) obj.append("vn ").append(Num.f(m.normals[v * 3])).append(' ').append(Num.f(m.normals[v * 3 + 1])).append(' ').append(Num.f(m.normals[v * 3 + 2])).append('\n')
            if (m.material !== lastMat) { obj.append("usemtl ").append(m.material.name).append('\n'); lastMat = m.material }
            var i = 0
            while (i + 2 < m.indices.size) {
                val a = m.indices[i] + base; val b = m.indices[i + 1] + base; val c = m.indices[i + 2] + base
                obj.append("f ").append(a).append('/').append(a).append('/').append(a).append(' ')
                    .append(b).append('/').append(b).append('/').append(b).append(' ')
                    .append(c).append('/').append(c).append('/').append(c).append('\n')
                i += 3
            }
            base += m.vertexCount
        }

        val mtl = StringBuilder("# GTA Vehicle Viewer — MTL\n")
        for (mat in scene.materials) {
            mtl.append("\nnewmtl ").append(mat.name).append('\n')
            mtl.append("Ka 1 1 1\n")
            mtl.append("Kd ").append(Num.f(mat.r)).append(' ').append(Num.f(mat.g)).append(' ').append(Num.f(mat.b)).append('\n')
            mtl.append("Ks 0 0 0\nNs 10\n")
            mtl.append("d ").append(Num.f(mat.a)).append('\n')
            mtl.append("illum 2\n")
            mat.texture?.let { mtl.append("map_Kd textures/").append(it.fileName).append('\n') }
        }

        val files = ArrayList<Pair<String, ByteArray>>()
        files += objName to obj.toString().toByteArray(Charsets.UTF_8)
        files += mtlName to mtl.toString().toByteArray(Charsets.UTF_8)
        scene.textures.forEach { files += "textures/${it.fileName}" to it.png }
        val fileName = ExportNaming.fileName(baseName, ExportFormat.OBJ)
        return ExportOutput(fileName, ExportFormat.OBJ.mime, Exporters.zip(files), scene.warnings, null, files.map { it.first })
    }
}
