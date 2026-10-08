package com.webmaletin.gtaviewer.fbx

import com.webmaletin.gtaviewer.export.*

/**
 * Exportación FBX 7.4 en formato ASCII (sin SDK de Autodesk) + texturas PNG separadas en `textures/`.
 * Un Model + Geometry por sub-mesh, materiales Lambert con DiffuseColor y textura (Texture + Video) conectada.
 *
 * LIMITACIONES (se informan siempre al usuario): sin esqueleto/animación/skin, sin colores de vértice, sin jerarquía
 * de nodos (lista plana de mallas), sin canales de normal/specular/reflexión. Es ASCII (más grande que el binario) y
 * no se ha validado contra el SDK oficial de Autodesk.
 */
class FbxExporter : SceneExporter {
    override val format = ExportFormat.FBX

    private var nextId = 1_000_000L
    private fun id() = nextId++

    override fun export(scene: ExportScene, baseName: String): ExportOutput {
        nextId = 1_000_000L
        val fbxName = "$baseName.fbx"
        val matIds = HashMap<ExportMaterial, Long>()
        val texIds = HashMap<ExportTexture, Pair<Long, Long>>() // textura, video
        scene.materials.forEach { matIds[it] = id() }
        scene.textures.forEach { texIds[it] = id() to id() }
        val modelIds = scene.meshes.map { id() }
        val geomIds = scene.meshes.map { id() }

        val sb = StringBuilder(scene.totalVertices * 60 + 4096)
        sb.append("; FBX 7.4.0 project file\n; Generado por GTA Vehicle Viewer (ASCII, sin SDK de Autodesk)\n; ----------------------------------------------------\n\n")
        sb.append("FBXHeaderExtension:  {\n\tFBXHeaderVersion: 1003\n\tFBXVersion: 7400\n\tCreator: \"GTA Vehicle Viewer\"\n}\n")
        sb.append("GlobalSettings:  {\n\tVersion: 1000\n\tProperties70:  {\n")
        sb.append("\t\tP: \"UpAxis\", \"int\", \"Integer\", \"\",1\n\t\tP: \"UpAxisSign\", \"int\", \"Integer\", \"\",1\n")
        sb.append("\t\tP: \"FrontAxis\", \"int\", \"Integer\", \"\",2\n\t\tP: \"FrontAxisSign\", \"int\", \"Integer\", \"\",1\n")
        sb.append("\t\tP: \"CoordAxis\", \"int\", \"Integer\", \"\",0\n\t\tP: \"CoordAxisSign\", \"int\", \"Integer\", \"\",1\n")
        sb.append("\t\tP: \"UnitScaleFactor\", \"double\", \"Number\", \"\",100\n\t\tP: \"OriginalUnitScaleFactor\", \"double\", \"Number\", \"\",100\n")
        sb.append("\t}\n}\n\n")
        sb.append("Documents:  {\n\tCount: 1\n\tDocument: 1000000000, \"Scene\", \"Scene\" {\n\t\tRootNode: 0\n\t}\n}\nReferences:  {\n}\n\n")

        sb.append("Definitions:  {\n\tVersion: 100\n\tCount: ${1 + scene.meshes.size * 2 + scene.materials.size + scene.textures.size * 2}\n")
        sb.append("\tObjectType: \"GlobalSettings\" {\n\t\tCount: 1\n\t}\n")
        sb.append("\tObjectType: \"Model\" {\n\t\tCount: ${scene.meshes.size}\n\t}\n")
        sb.append("\tObjectType: \"Geometry\" {\n\t\tCount: ${scene.meshes.size}\n\t}\n")
        sb.append("\tObjectType: \"Material\" {\n\t\tCount: ${scene.materials.size}\n\t}\n")
        if (scene.textures.isNotEmpty()) {
            sb.append("\tObjectType: \"Texture\" {\n\t\tCount: ${scene.textures.size}\n\t}\n")
            sb.append("\tObjectType: \"Video\" {\n\t\tCount: ${scene.textures.size}\n\t}\n")
        }
        sb.append("}\n\nObjects:  {\n")

        scene.meshes.forEachIndexed { i, m ->
            val polyIdx = StringBuilder()
            var k = 0
            while (k + 2 < m.indices.size) {
                if (k > 0) polyIdx.append(',')
                polyIdx.append(m.indices[k]).append(',').append(m.indices[k + 1]).append(',').append(-(m.indices[k + 2]) - 1)
                k += 3
            }
            val verts = joinFloats(m.positions)
            val uvArr = StringBuilder()
            for (v in 0 until m.vertexCount) { if (v > 0) uvArr.append(','); uvArr.append(Num.f(m.uvs[v * 2])).append(',').append(Num.f(1f - m.uvs[v * 2 + 1])) }
            val normArr = StringBuilder()
            var first = true
            for (idx in m.indices) {
                if (!first) normArr.append(','); first = false
                normArr.append(Num.f(m.normals[idx * 3])).append(',').append(Num.f(m.normals[idx * 3 + 1])).append(',').append(Num.f(m.normals[idx * 3 + 2]))
            }
            val uvIdx = m.indices.joinToString(",")
            val name = ExportSceneBuilder.sanitize(m.name)

            sb.append("\tGeometry: ${geomIds[i]}, \"Geometry::$name\", \"Mesh\" {\n")
            sb.append("\t\tVertices: *${m.positions.size} {\n\t\t\ta: $verts\n\t\t}\n")
            sb.append("\t\tPolygonVertexIndex: *${m.indices.size} {\n\t\t\ta: $polyIdx\n\t\t}\n")
            sb.append("\t\tGeometryVersion: 124\n")
            sb.append("\t\tLayerElementNormal: 0 {\n\t\t\tVersion: 101\n\t\t\tName: \"\"\n\t\t\tMappingInformationType: \"ByPolygonVertex\"\n\t\t\tReferenceInformationType: \"Direct\"\n")
            sb.append("\t\t\tNormals: *${m.indices.size * 3} {\n\t\t\t\ta: $normArr\n\t\t\t}\n\t\t}\n")
            sb.append("\t\tLayerElementUV: 0 {\n\t\t\tVersion: 101\n\t\t\tName: \"UVMap\"\n\t\t\tMappingInformationType: \"ByPolygonVertex\"\n\t\t\tReferenceInformationType: \"IndexToDirect\"\n")
            sb.append("\t\t\tUV: *${m.vertexCount * 2} {\n\t\t\t\ta: $uvArr\n\t\t\t}\n")
            sb.append("\t\t\tUVIndex: *${m.indices.size} {\n\t\t\t\ta: $uvIdx\n\t\t\t}\n\t\t}\n")
            sb.append("\t\tLayerElementMaterial: 0 {\n\t\t\tVersion: 101\n\t\t\tName: \"\"\n\t\t\tMappingInformationType: \"AllSame\"\n\t\t\tReferenceInformationType: \"IndexToDirect\"\n\t\t\tMaterials: *1 {\n\t\t\t\ta: 0\n\t\t\t}\n\t\t}\n")
            sb.append("\t\tLayer: 0 {\n\t\t\tVersion: 100\n")
            sb.append("\t\t\tLayerElement:  {\n\t\t\t\tType: \"LayerElementNormal\"\n\t\t\t\tTypedIndex: 0\n\t\t\t}\n")
            sb.append("\t\t\tLayerElement:  {\n\t\t\t\tType: \"LayerElementMaterial\"\n\t\t\t\tTypedIndex: 0\n\t\t\t}\n")
            sb.append("\t\t\tLayerElement:  {\n\t\t\t\tType: \"LayerElementUV\"\n\t\t\t\tTypedIndex: 0\n\t\t\t}\n\t\t}\n\t}\n")

            sb.append("\tModel: ${modelIds[i]}, \"Model::$name\", \"Mesh\" {\n\t\tVersion: 232\n\t\tProperties70:  {\n")
            sb.append("\t\t\tP: \"InheritType\", \"enum\", \"\", \"\",1\n\t\t\tP: \"DefaultAttributeIndex\", \"int\", \"Integer\", \"\",0\n")
            sb.append("\t\t}\n\t\tShading: T\n\t\tCulling: \"CullingOff\"\n\t}\n")
        }

        for (mat in scene.materials) {
            sb.append("\tMaterial: ${matIds[mat]}, \"Material::${mat.name}\", \"\" {\n\t\tVersion: 102\n\t\tShadingModel: \"lambert\"\n\t\tMultiLayer: 0\n\t\tProperties70:  {\n")
            sb.append("\t\t\tP: \"DiffuseColor\", \"Color\", \"\", \"A\",${Num.f(mat.r)},${Num.f(mat.g)},${Num.f(mat.b)}\n")
            sb.append("\t\t\tP: \"DiffuseFactor\", \"Number\", \"\", \"A\",1\n")
            sb.append("\t\t\tP: \"Opacity\", \"Number\", \"\", \"A\",${Num.f(mat.a)}\n")
            sb.append("\t\t}\n\t}\n")
        }
        for (t in scene.textures) {
            val (tid, vid) = texIds[t]!!
            val rel = "textures/${t.fileName}"
            val n = t.fileName.removeSuffix(".png")
            sb.append("\tTexture: $tid, \"Texture::$n\", \"\" {\n\t\tType: \"TextureVideoClip\"\n\t\tVersion: 202\n\t\tTextureName: \"Texture::$n\"\n\t\tProperties70:  {\n")
            sb.append("\t\t\tP: \"UseMaterial\", \"bool\", \"\", \"\",1\n\t\t}\n\t\tMedia: \"Video::$n\"\n\t\tFileName: \"$rel\"\n\t\tRelativeFilename: \"$rel\"\n")
            sb.append("\t\tModelUVTranslation: 0,0\n\t\tModelUVScaling: 1,1\n\t\tTexture_Alpha_Source: \"None\"\n\t\tCropping: 0,0,0,0\n\t}\n")
            sb.append("\tVideo: $vid, \"Video::$n\", \"Clip\" {\n\t\tType: \"Clip\"\n\t\tProperties70:  {\n\t\t\tP: \"Path\", \"KString\", \"XRefUrl\", \"\", \"$rel\"\n\t\t}\n")
            sb.append("\t\tUseMipMap: 0\n\t\tFilename: \"$rel\"\n\t\tRelativeFilename: \"$rel\"\n\t}\n")
        }
        sb.append("}\n\nConnections:  {\n")
        scene.meshes.forEachIndexed { i, m ->
            sb.append("\tC: \"OO\",${modelIds[i]},0\n")
            sb.append("\tC: \"OO\",${geomIds[i]},${modelIds[i]}\n")
            sb.append("\tC: \"OO\",${matIds[m.material]},${modelIds[i]}\n")
        }
        for ((t, ids) in texIds) {
            sb.append("\tC: \"OO\",${ids.second},${ids.first}\n")
        }
        for (mat in scene.materials) mat.texture?.let { t -> sb.append("\tC: \"OP\",${texIds[t]!!.first},${matIds[mat]}, \"DiffuseColor\"\n") }
        sb.append("}\n\nTakes:  {\n\tCurrent: \"\"\n}\n")

        val files = ArrayList<Pair<String, ByteArray>>()
        files += fbxName to sb.toString().toByteArray(Charsets.UTF_8)
        scene.textures.forEach { files += "textures/${it.fileName}" to it.png }
        val limitations = "FBX exportado con limitaciones: formato ASCII FBX 7.4 generado sin el SDK de Autodesk (no verificado con él); " +
            "sin esqueleto/animación, sin colores de vértice, sin jerarquía de nodos (mallas planas) y sin mapas de normales/especular."
        val fileName = ExportNaming.fileName(baseName, ExportFormat.FBX)
        return ExportOutput(fileName, ExportFormat.FBX.mime, Exporters.zip(files), scene.warnings, limitations, files.map { it.first })
    }

    private fun joinFloats(a: FloatArray): String {
        val sb = StringBuilder(a.size * 8)
        for (i in a.indices) { if (i > 0) sb.append(','); sb.append(Num.f(a[i])) }
        return sb.toString()
    }
}
