package com.webmaletin.gtaviewer.dff

import com.webmaletin.gtaviewer.renderware.*

/**
 * Adaptador GTA III / Vice City / San Andreas (RenderWare 3.x, plataforma PC no nativa) sobre [RenderWareParser].
 *
 * Referencias de formato consultadas: GTAMods wiki "RenderWare Binary Stream File Format" y el comportamiento
 * documentado de rw-parser-ng / rwfury / DFF Loader. No se ha copiado código de ninguno de ellos
 * (implementación propia, sin dependencias, compatible con Android).
 */
class GtaSaDffParser : DffParser {
    override val name = "GTA_SA_DFF_Parser (RenderWare 3.x)"

    override fun parse(data: ByteArray): DffDocument {
        val tree = RenderWareParser.parse(data)
        val issues = ArrayList<String>(tree.issues)
        val clumps = tree.roots.filter { it.type == RwId.CLUMP }
        if (clumps.isEmpty()) {
            val first = tree.roots.firstOrNull()
            throw RwException(
                if (first == null) "Archivo vacío o sin cabecera RenderWare"
                else "No es un DFF: el primer chunk es ${RwId.name(first.type)}, se esperaba Clump",
            )
        }
        if (clumps.size > 1) issues += "El archivo contiene ${clumps.size} Clumps; se usa el primero"
        val clump = clumps[0]
        val version = clump.version

        val frames = clump.child(RwId.FRAME_LIST)?.let { readFrames(tree, it, issues) } ?: run {
            issues += "Clump sin FrameList"; emptyList()
        }

        val geometries = ArrayList<DffGeometry?>()
        clump.child(RwId.GEOMETRY_LIST)?.let { gl ->
            val expected = gl.child(RwId.STRUCT)?.let { tree.stream(it).u32().toInt() } ?: -1
            val geoms = gl.childrenOf(RwId.GEOMETRY)
            if (expected >= 0 && expected != geoms.size) issues += "GeometryList declara $expected geometrías, se encontraron ${geoms.size}"
            geoms.forEachIndexed { i, g ->
                geometries += try {
                    readGeometry(tree, g, i, issues)
                } catch (e: Exception) {
                    issues += "Geometría $i ilegible: ${e.message}"
                    null
                }
            }
        } ?: run { issues += "Clump sin GeometryList" }

        val atomics = ArrayList<DffAtomic>()
        clump.childrenOf(RwId.ATOMIC).forEachIndexed { i, a ->
            try {
                val st = a.child(RwId.STRUCT) ?: throw RwException("Atomic sin Struct")
                val s = tree.stream(st)
                atomics += DffAtomic(i, s.i32(), s.i32(), s.i32())
            } catch (e: Exception) {
                issues += "Atomic $i ilegible: ${e.message}"
            }
        }
        return DffDocument(version, frames, geometries, atomics, data.size, issues, name)
    }

    override fun summarize(prefix: ByteArray): DffSummary {
        return try {
            // Modo rápido: no se descienden los hijos de GeometryList (pueden estar truncados en el prefijo).
            val tree = RenderWareParser.parse(prefix, skipChildrenOf = setOf(RwId.GEOMETRY_LIST))
            val clump = tree.roots.firstOrNull { it.type == RwId.CLUMP }
                ?: return DffSummary(false, 0, emptyList(), 0, 0, listOf("Sin chunk Clump"))
            val issues = ArrayList<String>()
            val frames = clump.child(RwId.FRAME_LIST)?.let { readFrames(tree, it, issues) } ?: emptyList()
            // GeometryList no se descompone en modo resumen: el contador está en su primer Struct (offset + 12).
            val geoCount = clump.child(RwId.GEOMETRY_LIST)?.let { gl ->
                runCatching {
                    val st = RwStream(prefix, gl.offset, prefix.size)
                    if (st.i32() == RwId.STRUCT) { st.i32(); st.i32(); st.u32().toInt() } else 0
                }.getOrDefault(0)
            } ?: 0
            DffSummary(true, clump.version, frames.mapNotNull { it.name }, frames.size, geoCount, issues)
        } catch (e: Exception) {
            DffSummary(false, 0, emptyList(), 0, 0, listOf(e.message ?: "error"))
        }
    }

    // ---------------------------------------------------------------- frames

    private fun readFrames(tree: RwParseResult, node: RwNode, issues: MutableList<String>): List<DffFrame> {
        val st = node.child(RwId.STRUCT) ?: return emptyList()
        val s = tree.stream(st)
        val count = s.u32().toInt()
        if (count < 0 || count > 100_000 || count * 56L > st.size) {
            issues += "FrameList inconsistente (count=$count, struct=${st.size} bytes)"
        }
        val safeCount = minOf(count, (st.size - 4) / 56).coerceAtLeast(0)
        val exts = node.childrenOf(RwId.EXTENSION)
        val out = ArrayList<DffFrame>(safeCount)
        for (i in 0 until safeCount) {
            val rot = FloatArray(9) { s.f32() }
            val pos = FloatArray(3) { s.f32() }
            val parent = s.i32()
            s.u32() // matrix flags
            var name: String? = null
            var bone: Int? = null
            exts.getOrNull(i)?.let { ext ->
                ext.child(RwId.FRAME_NAME)?.let { n ->
                    name = String(tree.data, n.offset, n.size, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
                        .ifEmpty { null }
                }
                ext.child(RwId.HANIM_PLG)?.let { h ->
                    runCatching { val hs = tree.stream(h); hs.u32(); bone = hs.i32() }
                }
            }
            out += DffFrame(i, name, rot, pos, parent, bone)
        }
        return out
    }

    // ---------------------------------------------------------------- geometría

    private fun readGeometry(tree: RwParseResult, node: RwNode, index: Int, issues: MutableList<String>): DffGeometry {
        val st = node.child(RwId.STRUCT) ?: throw RwException("Geometry sin Struct")
        val s = tree.stream(st)
        val flags = s.i32()
        val numTris = s.u32().toInt()
        val numVerts = s.u32().toInt()
        val numMorph = s.u32().toInt()
        if (node.version < 0x34000) { s.f32(); s.f32(); s.f32() }

        val isNative = flags and 0x01000000 != 0
        val isStrip = flags and 0x1 != 0
        val hasPrelit = flags and 0x08 != 0
        val hasNormals = flags and 0x10 != 0
        var uvCount = (flags ushr 16) and 0xFF
        if (uvCount == 0) uvCount = if (flags and 0x80 != 0) 2 else if (flags and 0x04 != 0) 1 else 0

        if (numVerts < 0 || numVerts > 2_000_000 || numTris < 0 || numTris > 4_000_000)
            throw RwException("Contadores absurdos (vértices=$numVerts, triángulos=$numTris)")
        if (numMorph < 1 || numMorph > 64) throw RwException("Morph targets inválidos ($numMorph)")

        var positions = FloatArray(0)
        var normals: FloatArray? = null
        val uvSets = ArrayList<FloatArray>()
        var colors: ByteArray? = null
        var tris = IntArray(0)
        var triMats = IntArray(0)
        var sphere: FloatArray? = null

        if (isNative) {
            issues += "Geometría $index es nativa (PS2/Xbox): sin datos de vértices en el stream estándar"
        } else {
            if (hasPrelit) colors = s.bytes(numVerts * 4)
            for (u in 0 until uvCount) {
                val a = FloatArray(numVerts * 2)
                for (k in a.indices) a[k] = s.f32()
                uvSets += a
            }
            tris = IntArray(numTris * 3)
            triMats = IntArray(numTris)
            for (t in 0 until numTris) {
                val v2 = s.u16(); val v1 = s.u16(); val m = s.u16(); val v3 = s.u16()
                tris[t * 3] = v1; tris[t * 3 + 1] = v2; tris[t * 3 + 2] = v3
                triMats[t] = m
            }
            for (m in 0 until numMorph) {
                val sph = FloatArray(4) { s.f32() }
                val hasV = s.u32() != 0L
                val hasN = s.u32() != 0L
                val v = if (hasV) FloatArray(numVerts * 3).also { arr -> for (k in arr.indices) arr[k] = s.f32() } else null
                val n = if (hasN) FloatArray(numVerts * 3).also { arr -> for (k in arr.indices) arr[k] = s.f32() } else null
                if (m == 0) {
                    sphere = sph
                    positions = v ?: FloatArray(0)
                    normals = n
                }
            }
            if (!hasNormals && normals != null && normals.all { it == 0f }) normals = null
            // Validar índices
            for (i in tris.indices) {
                if (tris[i] >= numVerts) {
                    issues += "Geometría $index: índice de vértice fuera de rango (${tris[i]} >= $numVerts)"
                    break
                }
            }
        }

        // Materiales
        val materials = node.child(RwId.MATERIAL_LIST)?.let { readMaterials(tree, it, issues) } ?: emptyList()

        // Extensión: BinMesh, Skin
        var bin: List<BinMeshGroup>? = null
        var binStrip = false
        var hasSkin = false
        var bones = 0
        node.child(RwId.EXTENSION)?.let { ext ->
            ext.child(RwId.BIN_MESH_PLG)?.let { b ->
                try {
                    val bs = tree.stream(b)
                    binStrip = bs.u32() == 1L
                    val n = bs.u32().toInt()
                    bs.u32() // total indices
                    val list = ArrayList<BinMeshGroup>()
                    for (k in 0 until n) {
                        val cnt = bs.u32().toInt()
                        val mat = bs.u32().toInt()
                        if (cnt < 0 || cnt * 4L > bs.remaining()) throw RwException("BinMesh truncado")
                        val ix = IntArray(cnt) { bs.u32().toInt() }
                        list += BinMeshGroup(mat, ix)
                    }
                    bin = list
                } catch (e: Exception) {
                    issues += "Geometría $index: BinMesh ilegible (${e.message})"
                }
            }
            ext.child(RwId.SKIN_PLG)?.let { sk ->
                hasSkin = true
                bones = runCatching { tree.stream(sk).u8() }.getOrDefault(0)
            }
        }

        return DffGeometry(
            index, flags, isNative, isStrip, numVerts, positions, normals, uvSets, colors,
            tris, triMats, bin, binStrip, materials, hasSkin, bones, sphere,
        )
    }

    private fun readMaterials(tree: RwParseResult, node: RwNode, issues: MutableList<String>): List<DffMaterial> {
        val st = node.child(RwId.STRUCT) ?: return emptyList()
        val s = tree.stream(st)
        val count = s.u32().toInt()
        if (count < 0 || count > 4096 || count * 4L > st.size) { issues += "MaterialList inconsistente ($count)"; return emptyList() }
        val indices = IntArray(count) { s.i32() }
        val matNodes = node.childrenOf(RwId.MATERIAL)
        var next = 0
        val out = ArrayList<DffMaterial>(count)
        for (i in 0 until count) {
            val ref = indices[i]
            val mat: DffMaterial? = if (ref < 0) {
                val mn = matNodes.getOrNull(next++)
                if (mn == null) { issues += "MaterialList: falta el Material #$i"; null }
                else try { readMaterial(tree, mn, i) } catch (e: Exception) { issues += "Material $i ilegible: ${e.message}"; null }
            } else out.getOrNull(ref)?.let { src ->
                DffMaterial(i, src.r, src.g, src.b, src.a, src.texture, src.ambient, src.diffuse, src.specular, src.hasEnvMap, src.hasSpecular)
            }
            out += mat ?: DffMaterial(i, 255, 255, 255, 255, null, 1f, 1f, 1f, false, false)
        }
        return out
    }

    private fun readMaterial(tree: RwParseResult, node: RwNode, index: Int): DffMaterial {
        val st = node.child(RwId.STRUCT) ?: throw RwException("Material sin Struct")
        val s = tree.stream(st)
        s.u32() // flags
        val r = s.u8(); val g = s.u8(); val b = s.u8(); val a = s.u8()
        s.u32() // unused
        val textured = s.i32() != 0
        var amb = 1f; var dif = 1f; var spec = 1f
        if (st.size >= 28) { amb = s.f32(); dif = s.f32(); spec = s.f32() }
        var tex: DffTextureRef? = null
        if (textured) {
            node.child(RwId.TEXTURE)?.let { tn ->
                val strings = tn.childrenOf(RwId.STRING)
                fun str(n: RwNode?) = n?.let { String(tree.data, it.offset, it.size, Charsets.ISO_8859_1).substringBefore('\u0000').trim() } ?: ""
                val name = str(strings.getOrNull(0))
                if (name.isNotEmpty()) tex = DffTextureRef(name, str(strings.getOrNull(1)))
            }
        }
        var env = false; var specular = false
        node.child(RwId.EXTENSION)?.let { ext ->
            env = ext.child(RwId.REFLECTION_MATERIAL) != null || ext.child(RwId.MATFX) != null
            specular = ext.child(RwId.SPECULAR_MATERIAL) != null
        }
        return DffMaterial(index, r, g, b, a, tex, amb, dif, spec, env, specular)
    }
}
