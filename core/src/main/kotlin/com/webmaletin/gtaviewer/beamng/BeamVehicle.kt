package com.webmaletin.gtaviewer.beamng

import com.webmaletin.gtaviewer.beamng.LenientJson.Companion.asList
import com.webmaletin.gtaviewer.beamng.LenientJson.Companion.asMap
import com.webmaletin.gtaviewer.beamng.LenientJson.Companion.num
import com.webmaletin.gtaviewer.image.Images
import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.txd.TextureImage
import com.webmaletin.gtaviewer.zip.ArchiveEntry

/** Una configuración (.pc) del coche: piezas elegidas + ficha (potencia, peso, tracción…). */
class BeamConfig(val name: String, val label: String, val summary: String, val pc: ArchiveEntry)

/** Un coche de un mod de BeamNG (carpeta `vehicles/<nombre>/`). */
class BeamVehicleEntry(
    val id: String,
    /** Nombre del archivo importado (sin extensión): se usa para nombrar lo que se exporta. */
    val sourceName: String,
    val folder: String,
    val dir: String,
    val displayName: String,
    val info: Map<String, Any?>,
    val configs: List<BeamConfig>,
    val defaultConfig: String?,
    val dae: ArchiveEntry?,
    /** Todos los archivos del mod, por ruta en minúsculas. */
    val files: Map<String, ArchiveEntry>,
    val jbeams: List<ArchiveEntry>,
)

class BeamScan(val vehicles: List<BeamVehicleEntry>, val nestedZips: List<ArchiveEntry>)

object BeamNgScanner {
    /** @param sourceName nombre del archivo importado (sin extensión). */
    fun scan(archiveId: String, sourceName: String, entries: List<ArchiveEntry>): BeamScan {
        val files = HashMap<String, ArchiveEntry>(entries.size * 2)
        for (e in entries) files[e.path.lowercase()] = e
        val dirs = LinkedHashMap<String, MutableList<ArchiveEntry>>()
        for (e in entries) {
            val p = e.path; val lp = p.lowercase()
            val i = lp.indexOf("vehicles/"); if (i < 0) continue
            val rest = p.substring(i + 9); val folder = rest.substringBefore('/', "")
            if (folder.isEmpty() || rest == folder || folder.equals("common", true)) continue
            dirs.getOrPut(p.substring(0, i + 9) + folder + "/") { ArrayList() } += e
        }
        val vehicles = ArrayList<BeamVehicleEntry>()
        for ((dir, list) in dirs) {
            val dae = list.filter { it.extension == "dae" }.maxByOrNull { it.size }
            val jb = list.filter { it.extension == "jbeam" }
            if (dae == null || jb.isEmpty()) continue
            val folder = dir.trimEnd('/').substringAfterLast('/')
            val info = list.firstOrNull { it.fileName.equals("info.json", true) }?.let { runCatching { asMap(LenientJson.parse(String(it.source.readBytes(), Charsets.UTF_8))) }.getOrNull() } ?: emptyMap()
            val configs = list.filter { it.extension == "pc" && !it.fileName.startsWith(".") }.sortedBy { it.fileName.lowercase() }.map { pc ->
                val n = pc.baseName
                val ci = list.firstOrNull { it.fileName.equals("info_$n.json", true) }?.let { runCatching { asMap(LenientJson.parse(String(it.source.readBytes(), Charsets.UTF_8))) }.getOrNull() } ?: emptyMap()
                val label = ci["Configuration"]?.toString() ?: n
                val bits = listOfNotNull(ci["Drivetrain"], ci["Power"]?.let { "${num(it)?.toInt()} hp" }, ci["Weight"]?.let { "${num(it)?.toInt()} kg" }, ci["Transmission"], ci["Config Type"]).map { it.toString() }
                BeamConfig(n, label, bits.joinToString(" · "), pc)
            }
            val brand = info["Brand"]?.toString(); val nm = info["Name"]?.toString()
            val display = listOfNotNull(brand, nm).joinToString(" ").ifBlank { folder }
            val def = info["default_pc"]?.toString()?.takeIf { d -> configs.any { it.name.equals(d, true) } } ?: configs.firstOrNull()?.name
            vehicles += BeamVehicleEntry("beam:$archiveId:$folder", sourceName, folder, dir, display, info, configs, def, dae, files, jb)
        }
        val nested = entries.filter { it.extension == "zip" && it.size > 100_000 }
        return BeamScan(vehicles, nested)
    }
}

class BeamPhysics(
    val vehicleName: String, val config: String,
    val nodes: List<Triple<JNode, DoubleArray, String>>, // nodo, desplazamiento acumulado, parte de origen
    val beams: List<JBeam>,
    val wheels: List<Map<String, Any?>>,
) {
    val totalMass: Double get() = nodes.sumOf { it.first.weight }
    private fun absolute(n: Triple<JNode, DoubleArray, String>) = doubleArrayOf(n.first.x + n.second[0], n.first.y + n.second[1], n.first.z + n.second[2])

    /** Posición BeamNG → espacio de exportación (GLB: Y arriba, frente +Z): (x, y, z) → (x, z, -y). */
    private fun view(p: DoubleArray) = doubleArrayOf(p[0], p[2], -p[1])

    fun com(): DoubleArray {
        val m = totalMass; if (m <= 0.0) return doubleArrayOf(0.0, 0.0, 0.0)
        val s = DoubleArray(3); for (n in nodes) { val a = view(absolute(n)); for (i in 0..2) s[i] += a[i] * n.first.weight }
        return doubleArrayOf(s[0] / m, s[1] / m, s[2] / m)
    }

    /** JSON con los nodos y vigas del esqueleto de física (coordenadas del GLB) y un resumen para montar el coche en un motor. */
    fun toJson(): String {
        fun f(d: Double) = if (d.isFinite()) "%.5f".format(java.util.Locale.US, d) else "0"
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val ids = HashMap<String, Int>(); nodes.forEachIndexed { i, n -> ids.putIfAbsent(n.first.id, i) }
        val sb = StringBuilder(1 shl 20)
        val c = com()
        sb.append("{\n\"formato\":\"BeamNG jbeam → esqueleto de física (nodos + vigas)\",\n")
        sb.append("\"coordenadas\":\"las del GLB exportado (metros, Y arriba, el frente del coche mira a +Z, +X = lado izquierdo del coche)\",\n")
        sb.append("\"vehiculo\":${q(vehicleName)},\"configuracion\":${q(config)},\n")
        sb.append("\"resumen\":{\"nodos\":${nodes.size},\"vigas\":${beams.size},\"masa_total_kg\":${f(totalMass)},\"centro_de_masa\":[${f(c[0])},${f(c[1])},${f(c[2])}]},\n")
        sb.append("\"ruedas\":[")
        sb.append(wheels.joinToString(",") { w -> "{" + w.entries.joinToString(",") { (k, v) -> q(k) + ":" + when (v) { is DoubleArray -> "[" + v.joinToString(",") { f(it) } + "]"; is Number -> f(v.toDouble()); else -> q(v.toString()) } } + "}" })
        sb.append("],\n\"nodos\":[")
        nodes.forEachIndexed { i, n -> val p = view(absolute(n)); if (i > 0) sb.append(','); sb.append("{\"id\":${q(n.first.id)},\"pos\":[${f(p[0])},${f(p[1])},${f(p[2])}],\"masa\":${f(n.first.weight)}${n.first.group?.let { ",\"grupo\":${q(it)}" } ?: ""},\"parte\":${q(n.third)}}") }
        sb.append("],\n\"vigas\":[")
        var first = true
        for (b in beams) {
            val a = ids[b.a]; val bb = ids[b.b]; if (a == null || bb == null) continue
            if (!first) sb.append(','); first = false
            sb.append("{\"a\":$a,\"b\":$bb,\"tipo\":${q(b.type.trimStart('|'))}")
            b.spring?.let { sb.append(",\"muelle\":${f(it)}") }; b.damp?.let { sb.append(",\"amortiguacion\":${f(it)}") }
            b.deform?.let { sb.append(",\"deformacion\":${f(it)}") }; b.strength?.let { sb.append(",\"resistencia\":${f(it)}") }
            sb.append('}')
        }
        sb.append("],\n\"nota\":\"BeamNG simula los daños deformando este esqueleto en tiempo real: no existen mallas dañadas en los archivos del mod. Este archivo guarda los datos para que puedas reproducir la física o derivar parámetros (VehicleBody3D: masa, ruedas, centro de masa).\"\n}\n")
        return sb.toString()
    }
}

class BeamLoadResult(
    val model: VehicleModel, val physics: BeamPhysics, val config: String, val paintPrimary: Int, val paintName: String?,
    val baseName: String,
)

object BeamNgBuilder {
    private val C = Mat4.scale3(-1f, -1f, 1f) // BeamNG (+X izquierda, +Y atrás, +Z arriba) → GTA (+X derecha, +Y delante, +Z arriba): giro de 180° sobre Z

    private fun lowerPath(p: String) = p.trim().replace('\\', '/').trimStart('/').lowercase()

    fun load(v: BeamVehicleEntry, configName: String?, maxTexture: Int = 1024, onProgress: (String) -> Unit = {}): BeamLoadResult {
        val warnings = ArrayList<String>()
        // ---- jbeam
        onProgress("Leyendo piezas (jbeam)…")
        val parts = HashMap<String, JPart>()
        for (e in v.jbeams) {
            try { for (p in Jbeam.parseFile(String(e.source.readBytes(), Charsets.UTF_8), e.fileName)) parts[p.name] = p } catch (ex: Exception) { warnings += "jbeam ${e.fileName}: ${ex.message}" }
        }
        val cfg = v.configs.firstOrNull { it.name.equals(configName ?: v.defaultConfig, true) } ?: v.configs.firstOrNull()
        val pc = cfg?.let { runCatching { asMap(LenientJson.parse(String(it.pc.source.readBytes(), Charsets.UTF_8))) }.getOrNull() } ?: emptyMap()
        val chosen = asMap(pc["parts"]).mapValues { it.value?.toString() ?: "" }
        val mainName = pc["mainPartName"]?.toString()?.takeIf { it in parts } ?: parts.values.firstOrNull { it.slotType == "main" }?.name
            ?: throw IllegalStateException("El mod no tiene parte principal (slotType \"main\") en sus jbeam")
        val (active, notes) = BeamConfigResolver.resolve(parts.getValue(mainName), parts, chosen)
        warnings += notes
        // ---- collada
        val dae = v.dae ?: throw IllegalStateException("El coche no tiene archivo .dae")
        onProgress("Leyendo la escena 3D (${dae.fileName})…")
        val scene = dae.source.openStream().use { Collada.readScene(it) }
        val nodeByName = LinkedHashMap<String, ColladaNode>(); for (n in scene.nodes) nodeByName.putIfAbsent(n.name, n)
        val wantedMeshes = LinkedHashSet<String>()
        data class Inst(val mesh: String, val act: ActivePart, val flex: JFlex?, val prop: JProp?)
        val insts = ArrayList<Inst>()
        for (a in active) { for (f in a.part.flexbodies) insts += Inst(f.mesh, a, f, null); for (p in a.part.props) insts += Inst(p.mesh, a, null, p) }
        val used = insts.filter { it.mesh in nodeByName }
        val missing = insts.map { it.mesh }.filter { it !in nodeByName }.distinct()
        if (missing.isNotEmpty()) warnings += "${missing.size} mallas de la configuración no están en el .dae (suelen ser recursos del juego base: ${missing.take(6).joinToString()}${if (missing.size > 6) "…" else ""})"
        val needGeo = used.map { nodeByName.getValue(it.mesh).geometryId }.toSet()
        onProgress("Leyendo ${needGeo.size} mallas…")
        val meshes = dae.source.openStream().use { Collada.readGeometries(it, needGeo) }
        // ---- materiales y texturas
        onProgress("Leyendo materiales…")
        val matDefs = HashMap<String, Map<String, Any?>>()
        val matFiles = v.files.values.filter { it.fileName.lowercase().endsWith("materials.json") && (lowerPath(it.path).contains("vehicles/${v.folder.lowercase()}/") || lowerPath(it.path).contains("vehicles/common/")) }
        for (e in matFiles) try { for ((k, m) in asMap(LenientJson.parse(String(e.source.readBytes(), Charsets.UTF_8)))) { val mm = asMap(m); matDefs[k] = mm; mm["mapTo"]?.toString()?.let { matDefs.putIfAbsent(it, mm) } } } catch (ex: Exception) { warnings += "materiales ${e.fileName}: ${ex.message}" }
        val texCache = HashMap<String, TextureImage?>()
        val texNotFound = LinkedHashSet<String>()
        fun texture(path: String?, opaque: Boolean): TextureImage? {
            if (path.isNullOrBlank()) return null
            val key = lowerPath(path) + if (opaque) "|o" else ""
            return texCache.getOrPut(key) {
                val lp = lowerPath(path)
                val entry = v.files[lp] ?: v.files[lp.substringBeforeLast('.') + ".dds"] ?: v.files[lp.substringBeforeLast('.') + ".png"]
                    ?: v.files["${v.dir.lowercase()}$lp"] ?: v.files.entries.firstOrNull { it.key.endsWith("/" + lp.substringAfterLast('/')) }?.value
                if (entry == null) { texNotFound += path; return@getOrPut null }
                val t = try { Images.decode(entry.source.readBytes(), entry.fileName, maxTexture) } catch (ex: Throwable) { warnings += "textura ${entry.fileName}: ${ex.message}"; null }
                if (t == null) { texNotFound += "${entry.fileName} (formato no soportado)"; return@getOrPut null }
                if (opaque) { for (i in 3 until t.rgba.size step 4) t.rgba[i] = 255.toByte() }
                TextureImage(entry.baseName, t.width, t.height, t.rgba, if (opaque) false else t.hasAlpha, t.sourceFormat)
            }
        }
        // pintura por defecto
        val paints = asMap(v.info["paints"])
        val cfgInfoPaint = cfg?.let { runCatching { asMap(LenientJson.parse(String(v.files[v.dir.lowercase() + "info_${it.name.lowercase()}.json"]!!.source.readBytes(), Charsets.UTF_8)))["defaultPaintName1"]?.toString() }.getOrNull() }
        val paintName = cfgInfoPaint?.takeIf { it in paints } ?: v.info["defaultPaintName1"]?.toString()?.takeIf { it in paints } ?: paints.keys.firstOrNull()
        val paint = asMap(paints[paintName])
        val pcol = asList(paint["baseColor"]).mapNotNull { num(it) }
        val paintRgb = if (pcol.size >= 3) ((pcol[0].coerceIn(0.0, 1.0) * 255).toInt() shl 16) or ((pcol[1].coerceIn(0.0, 1.0) * 255).toInt() shl 8) or (pcol[2].coerceIn(0.0, 1.0) * 255).toInt() else 0xC9CDD2
        val paintMetal = num(paint["metallic"])?.toFloat() ?: 0.3f; val paintRough = num(paint["roughness"])?.toFloat() ?: 0.4f

        val matCache = HashMap<String, MaterialInfo>()
        fun material(symbol: String, node: ColladaNode): MaterialInfo {
            val mid = node.materials[symbol] ?: symbol
            val name = scene.materialNames[mid] ?: mid.removeSuffix("-material")
            return matCache.getOrPut(name) {
                val def = matDefs[name]
                val stages = asList(def?.get("Stages")).map { asMap(it) }
                val st = stages.firstOrNull { it["baseColorMap"] != null || it["colorMap"] != null || it["diffuseMap"] != null } ?: stages.firstOrNull() ?: emptyMap()
                val tex = (st["baseColorMap"] ?: st["colorMap"] ?: st["diffuseMap"])?.toString()?.takeIf { it.isNotBlank() }
                val paintable = stages.any { (it["colorPaletteMap"] as? String)?.isNotBlank() == true } || st["colorPaletteMapUseUV"] != null && tex == null
                val col = asList(st["baseColorFactor"] ?: st["diffuseColor"]).mapNotNull { num(it) }
                val eff = scene.materialColors[mid]
                var r = (col.getOrNull(0) ?: eff?.get(0)?.toDouble() ?: 1.0); var g = (col.getOrNull(1) ?: eff?.get(1)?.toDouble() ?: 1.0); var b = (col.getOrNull(2) ?: eff?.get(2)?.toDouble() ?: 1.0)
                var a = col.getOrNull(3) ?: 1.0
                val lname = name.lowercase()
                val glass = "glass" in lname || "window" in lname || "windshield" in lname
                val translucent = def?.get("translucent") == true || (def?.get("alphaTest") == true) || glass || a < 1.0
                if (glass && a >= 1.0) a = 0.43
                if (paintable && tex == null) { r = ((paintRgb shr 16) and 255) / 255.0; g = ((paintRgb shr 8) and 255) / 255.0; b = (paintRgb and 255) / 255.0 }
                val img = texture(tex, opaque = !translucent)
                val metal = num(st["metallicFactor"])?.toFloat() ?: (if (paintable) paintMetal else 0f)
                val rough = num(st["roughnessFactor"])?.toFloat() ?: (if (paintable) paintRough else 0.6f)
                if (tex != null && img == null) warnings += "Material «$name»: textura «$tex» no disponible; se usa el color"
                if (def == null) warnings += "Material «$name» sin definición en materials.json; se usa el color del .dae"
                MaterialInfo("beam:$name", name, (r.coerceIn(0.0, 1.0) * 255).toInt(), (g.coerceIn(0.0, 1.0) * 255).toInt(), (b.coerceIn(0.0, 1.0) * 255).toInt(), (a.coerceIn(0.0, 1.0) * 255).toInt(),
                    if (img != null) img.name else null, img, if (paintable && img == null) 1 else 0, false, metal.coerceIn(0f, 1f), rough.coerceIn(0f, 1f), glass)
            }
        }

        // ---- piezas
        onProgress("Montando el modelo…")
        val geoIds = HashMap<String, GeometryData>(); var geoCounter = 0; var partId = 0; var subId = 0
        val usedNames = HashMap<String, Int>()
        val nodeAbs = HashMap<String, DoubleArray>()
        for (a in active) for (n in a.part.nodes) nodeAbs.putIfAbsent(n.id, doubleArrayOf(n.x + a.offset[0], n.y + a.offset[1], n.z + a.offset[2]))
        val outParts = ArrayList<MeshPart>()
        val prefix = mainName + "_"
        for (inst in used) {
            val node = nodeByName.getValue(inst.mesh)
            val mesh = meshes[node.geometryId] ?: continue
            val g = geoIds.getOrPut(mesh.id) {
                GeometryData(geoCounter++, mesh.vertexCount, mesh.positions, mesh.normals, mesh.uvs, mesh.hasNormals, mesh.hasUvs, false)
            }
            val pid = partId++
            val subs = ArrayList<SubMesh>()
            for (grp in mesh.groups) { if (grp.indices.isEmpty()) continue; subs += SubMesh(subId++, pid, g, grp.indices, material(grp.materialSymbol, node)) }
            if (subs.isEmpty()) continue
            // transformación de la instancia en coordenadas BeamNG
            val off = inst.act.offset
            var tb: FloatArray
            val flex = inst.flex
            if (flex != null) {
                tb = Mat4.translate((off[0] + flex.pos[0]).toFloat(), (off[1] + flex.pos[1]).toFloat(), (off[2] + flex.pos[2]).toFloat())
                tb = Mat4.multiply(tb, Mat4.multiply(Mat4.rotZ(flex.rot[2].toFloat()), Mat4.multiply(Mat4.rotY(flex.rot[1].toFloat()), Mat4.rotX(flex.rot[0].toFloat()))))
                tb = Mat4.multiply(tb, Mat4.scale3(flex.scale[0].toFloat(), flex.scale[1].toFloat(), flex.scale[2].toFloat()))
            } else {
                // props: mallas animadas (volante, agujas, pedales). Si la malla ya está modelada en su sitio no se mueve; si está en el origen se coloca en su nodo de referencia.
                val pr = inst.prop!!
                val ref = pr.idRef?.let { nodeAbs[it] }
                val b = bounds(mesh)
                val atOrigin = Math.abs(b[0]) < 0.25f && Math.abs(b[1]) < 0.25f && Math.abs(b[2]) < 0.25f
                tb = if (ref != null && atOrigin) Mat4.translate((ref[0] + pr.baseTranslation[0]).toFloat(), (ref[1] + pr.baseTranslation[1]).toFloat(), (ref[2] + pr.baseTranslation[2]).toFloat()) else Mat4.identity()
            }
            tb = Mat4.multiply(tb, node.world)
            val transform = Mat4.multiply(C, tb)
            var nm = inst.mesh.removePrefix(prefix)
            val grpTag = flex?.groups?.firstOrNull { it.startsWith("wheel", true) } ?: flex?.groups?.firstOrNull()
            val cnt = usedNames.merge(nm, 1, Int::plus)!!
            val wheelTag = flex?.groups?.firstOrNull { it.startsWith("wheel", true) }
            if (wheelTag != null) nm = "$nm ($wheelTag)" else if (cnt > 1) nm = if (grpTag != null) "$nm ($grpTag)" else "$nm ($cnt)"
            val lname = inst.mesh.lowercase()
            val isDamage = lname.endsWith("_dmg") || lname.endsWith("_dam") || lname.endsWith("_damaged") || lname.contains("broken") || lname.endsWith("_destroyed")
            val cat = category(inst.mesh, inst.act.part)
            val slot = wheelSlotOf(grpTag, nm)
            outParts += MeshPart(pid, nm + if (inst.prop != null) " (prop)" else "", cat, if (cat == PartCategory.WHEEL) slot else null, inst.mesh, transform, subs,
                defaultVisible = !isDamage && !inst.mesh.contains("SPOTLIGHT"), variantNote = if (isDamage) PartClassifier.DAMAGE_NOTE else null, recognized = true,
                extraSource = inst.act.part.displayName)
        }
        if (texNotFound.isNotEmpty()) warnings += "${texNotFound.size} texturas no disponibles: ${texNotFound.take(5).joinToString()}${if (texNotFound.size > 5) "…" else ""}"
        // ---- física
        val physNodes = active.flatMap { a -> a.part.nodes.map { Triple(it, a.offset, a.part.name) } }
        val physBeams = active.flatMap { it.part.beams }
        val wheels = wheelSummary(active, nodeAbs)
        val label = cfg?.label ?: "(sin configuración)"
        val cfgName = cfg?.name ?: ""
        val displayName = v.sourceName
        val model = ModelAssembler.assemble(displayName, "BEAMNG", outParts, warnings.distinct(), dffName = dae.fileName, txdName = "materials.json + texturas DDS/PNG",
            notes = listOf("BeamNG: ${v.displayName} · configuración $label", "Pintura por defecto: ${paintName ?: "-"}"), validate = true)
        return BeamLoadResult(model, BeamPhysics(v.displayName, label, physNodes, physBeams, wheels), cfgName, paintRgb, paintName, displayName)
    }

    private fun bounds(m: ColladaMesh): FloatArray {
        var sx = 0f; var sy = 0f; var sz = 0f; val n = m.vertexCount
        if (n == 0) return floatArrayOf(0f, 0f, 0f)
        for (i in 0 until n) { sx += m.positions[i * 3]; sy += m.positions[i * 3 + 1]; sz += m.positions[i * 3 + 2] }
        return floatArrayOf(sx / n, sy / n, sz / n)
    }

    private fun wheelSlotOf(group: String?, name: String): WheelSlot? {
        val s = (group ?: name).lowercase()
        return when {
            Regex("(^|_)fl($|_)").containsMatchIn(s) -> WheelSlot.FL; Regex("(^|_)fr($|_)").containsMatchIn(s) -> WheelSlot.FR
            Regex("(^|_)(rl|bl)($|_)").containsMatchIn(s) -> WheelSlot.RL; Regex("(^|_)(rr|br)($|_)").containsMatchIn(s) -> WheelSlot.RR
            else -> null
        }
    }

    private fun category(mesh: String, part: JPart): PartCategory {
        val n = mesh.lowercase()
        PartClassifier.categoryOfName(n)?.let { return it }
        return when {
            "body" in n || "chassis" in n -> PartCategory.BODY
            "bnr" in n -> PartCategory.WHEEL
            "pedal" in n || "stalk" in n || "gauge" in n || "needle" in n || "dash" in n || "visor" in n || "intmirror" in n || "carpet" in n -> PartCategory.INTERIOR
            "steer" in n -> PartCategory.STEERING
            "disc" in n || "caliper" in n || "hub" in n -> PartCategory.BRAKES
            "turbo" in n || "intercooler" in n || "radiator" in n || "pipe" in n || "transmission" in n || "oilcooler" in n -> PartCategory.ENGINE
            "head" in n || "tail" in n || "signal" in n || "fog" in n -> PartCategory.LIGHT
            "wiper" in n -> PartCategory.OTHER
            else -> PartClassifier.categoryOfName(part.displayName) ?: PartClassifier.categoryOfName(part.slotType) ?: PartCategory.OTHER
        }
    }

    /** Une las filas de ruedas de todas las partes por nombre y calcula el centro con los nodos node1/node2 (posición del GLB). */
    private fun wheelSummary(active: List<ActivePart>, nodeAbs: Map<String, DoubleArray>): List<Map<String, Any?>> {
        val byName = LinkedHashMap<String, MutableMap<String, Any?>>()
        for (a in active) for ((_, rows) in a.part.wheelRows) for (r in rows) {
            val name = r["name"]?.toString() ?: continue
            val m = byName.getOrPut(name) { LinkedHashMap() }
            for (k in listOf("radius", "hubRadius", "tireWidth", "hubWidth", "numRays", "node1", "node2", "wheelDir")) r[k]?.let { if (m[k] == null || k == "radius" || k == "tireWidth") m[k] = if (it is Double) it else it.toString().toDoubleOrNull() ?: it.toString() }
            m["parte"] = a.part.name
        }
        return byName.map { (name, m) ->
            val out = LinkedHashMap<String, Any?>(); out["nombre"] = name
            val n1 = nodeAbs[m["node1"]?.toString()]; val n2 = nodeAbs[m["node2"]?.toString()]
            if (n1 != null && n2 != null) { val c = doubleArrayOf((n1[0] + n2[0]) / 2, (n1[1] + n2[1]) / 2, (n1[2] + n2[2]) / 2); out["centro"] = doubleArrayOf(c[0], c[2], -c[1]) }
            (m["radius"] as? Double ?: m["hubRadius"] as? Double)?.let { out["radio"] = it }
            (m["tireWidth"] as? Double ?: m["hubWidth"] as? Double)?.let { out["ancho"] = it }
            out["nodos"] = "${m["node1"]},${m["node2"]}"
            out
        }
    }
}
