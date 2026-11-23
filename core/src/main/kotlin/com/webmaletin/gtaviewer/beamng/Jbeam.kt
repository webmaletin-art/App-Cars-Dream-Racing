package com.webmaletin.gtaviewer.beamng

import com.webmaletin.gtaviewer.beamng.LenientJson.Companion.asList
import com.webmaletin.gtaviewer.beamng.LenientJson.Companion.asMap
import com.webmaletin.gtaviewer.beamng.LenientJson.Companion.num

class JSlot(val key: String, val allowTypes: List<String>, val default: String, val description: String, val nodeOffset: DoubleArray)
class JFlex(val mesh: String, val groups: List<String>, val pos: DoubleArray, val rot: DoubleArray, val scale: DoubleArray)
class JProp(val func: String, val mesh: String, val idRef: String?, val idX: String?, val idY: String?, val baseTranslation: DoubleArray, val baseRotation: DoubleArray)
class JNode(val id: String, val x: Double, val y: Double, val z: Double, val weight: Double, val group: String?)
class JBeam(val a: String, val b: String, val type: String, val spring: Double?, val damp: Double?, val deform: Double?, val strength: Double?)

/** Una parte de un .jbeam ("gngr32_hood", "gngr32_wheel__F"…): lo necesario para ver el coche, sus piezas y su física. */
class JPart(
    val name: String, val slotType: String, val displayName: String, val file: String,
    val slots: List<JSlot>, val flexbodies: List<JFlex>, val props: List<JProp>,
    val nodes: List<JNode>, val beams: List<JBeam>, val wheelRows: Map<String, List<Map<String, Any?>>>,
    val info: Map<String, Any?>,
)

object Jbeam {
    private fun vec(v: Any?, def: Double = 0.0): DoubleArray {
        val m = asMap(v); return doubleArrayOf(num(m["x"]) ?: def, num(m["y"]) ?: def, num(m["z"]) ?: def)
    }

    /**
     * Tabla jbeam: primera fila = cabecera (nombres, los ':' finales se ignoran); cada diccionario suelto son MODIFICADORES que valen para
     * las filas siguientes; una fila puede terminar con un diccionario de opciones propias. Devuelve filas ya combinadas.
     */
    fun table(list: Any?): List<Map<String, Any?>> {
        val l = asList(list); if (l.isEmpty()) return emptyList()
        val header = asList(l[0]).map { (it as? String ?: "").trimEnd(':') }
        if (header.isEmpty() || asList(l[0]).any { it !is String }) return emptyList()
        val mods = LinkedHashMap<String, Any?>(); val rows = ArrayList<Map<String, Any?>>()
        for (e in l.drop(1)) {
            when (e) {
                is Map<*, *> -> for ((k, v) in e) mods[k.toString()] = v
                is List<*> -> {
                    val row = LinkedHashMap<String, Any?>(mods)
                    for ((i, h) in header.withIndex()) if (i < e.size && e[i] !is Map<*, *>) row[h] = e[i]
                    for (x in e.drop(header.size.coerceAtMost(e.size))) if (x is Map<*, *>) for ((k, v) in x) row[k.toString()] = v
                    // diccionario de opciones en cualquier posición de la fila (p. ej. columna de opciones de una pieza)
                    for ((i, x) in e.withIndex()) if (i < header.size && x is Map<*, *>) for ((k, v) in x) row[k.toString()] = v
                    rows += row
                }
            }
        }
        return rows
    }

    fun parseFile(text: String, fileName: String): List<JPart> {
        val root = asMap(LenientJson.parse(text))
        return root.mapNotNull { (name, v) -> val m = asMap(v); if (m.isEmpty() && v !is Map<*, *>) null else part(name, m, fileName) }
    }

    private fun part(name: String, m: Map<String, Any?>, file: String): JPart {
        val info = asMap(m["information"])
        val slots = ArrayList<JSlot>()
        for (r in table(m["slots"])) {
            val type = r["type"]?.toString() ?: continue
            slots += JSlot(type, listOf(type), r["default"]?.toString() ?: "", r["description"]?.toString() ?: "", vec(r["nodeOffset"]))
        }
        for (r in table(m["slots2"])) {
            val key = r["name"]?.toString() ?: continue
            val allow = asList(r["allowTypes"]).map { it.toString() }.ifEmpty { listOf(key) }
            slots += JSlot(key, allow, r["default"]?.toString() ?: "", r["description"]?.toString() ?: "", vec(r["nodeOffset"]))
        }
        val flex = table(m["flexbodies"]).mapNotNull { r ->
            val mesh = r["mesh"]?.toString() ?: return@mapNotNull null
            JFlex(mesh, asList(r["[group]"]).map { it.toString() }, vec(r["pos"]), vec(r["rot"]), vec(r["scale"], 1.0))
        }
        val props = table(m["props"]).mapNotNull { r ->
            val mesh = r["mesh"]?.toString() ?: return@mapNotNull null
            JProp(r["func"]?.toString() ?: "", mesh, r["idRef"]?.toString(), r["idX"]?.toString(), r["idY"]?.toString(), vec(r["baseTranslation"]), vec(r["baseRotation"]))
        }
        val nodes = table(m["nodes"]).mapNotNull { r ->
            val id = r["id"]?.toString() ?: return@mapNotNull null
            val x = num(r["posX"]) ?: return@mapNotNull null
            JNode(id, x, num(r["posY"]) ?: 0.0, num(r["posZ"]) ?: 0.0, num(r["nodeWeight"]) ?: 25.0, (r["group"] as? String)?.ifEmpty { null } ?: asList(r["group"]).firstOrNull()?.toString())
        }
        val beams = table(m["beams"]).mapNotNull { r ->
            val a = r["id1"]?.toString() ?: return@mapNotNull null; val b = r["id2"]?.toString() ?: return@mapNotNull null
            JBeam(a, b, r["beamType"]?.toString() ?: "|NORMAL", num(r["beamSpring"]), num(r["beamDamp"]), num(r["beamDeform"]), num(r["beamStrength"]))
        }
        val wheels = LinkedHashMap<String, List<Map<String, Any?>>>()
        for (k in listOf("pressureWheels", "hubWheels", "wheels", "mixedWheels", "rotators")) { val t = table(m[k]); if (t.isNotEmpty()) wheels[k] = t }
        return JPart(name, m["slotType"]?.toString() ?: "", info["name"]?.toString() ?: name, file, slots, flex, props, nodes, beams, wheels, info)
    }
}

/** Parte activa de una configuración, con el desplazamiento acumulado de sus ranuras (`nodeOffset`) en coordenadas BeamNG. */
class ActivePart(val part: JPart, val offset: DoubleArray, val slotPath: String)

object BeamConfigResolver {
    /**
     * Resuelve qué partes están puestas: desde la parte principal, cada ranura elige lo que diga la configuración (`parts` del .pc)
     * o, si no dice nada, su valor por defecto. Se acumulan los `nodeOffset` (así se colocan las ruedas, que se modelan en el origen).
     */
    fun resolve(main: JPart, all: Map<String, JPart>, chosen: Map<String, String>, maxDepth: Int = 12): Pair<List<ActivePart>, List<String>> {
        val out = ArrayList<ActivePart>(); val notes = ArrayList<String>(); val seen = HashSet<String>()
        fun visit(p: ActivePart, depth: Int) {
            out += p
            if (depth > maxDepth) { notes += "Profundidad de ranuras excedida en ${p.part.name}"; return }
            for (slot in p.part.slots) {
                val pick = chosen[slot.key] ?: slot.default
                if (pick.isEmpty()) continue
                val child = all[pick]
                if (child == null) { notes += "Ranura «${slot.key}»: la parte «$pick» no existe en el mod"; continue }
                if (child.slotType !in slot.allowTypes && slot.allowTypes.none { it == child.slotType }) { notes += "Ranura «${slot.key}»: «$pick» es de tipo ${child.slotType}, no encaja; se omite"; continue }
                if (!seen.add(p.slotPath + "/" + slot.key + "=" + pick)) continue
                visit(ActivePart(child, doubleArrayOf(p.offset[0] + slot.nodeOffset[0], p.offset[1] + slot.nodeOffset[1], p.offset[2] + slot.nodeOffset[2]), p.slotPath + "/" + slot.key), depth + 1)
            }
        }
        visit(ActivePart(main, doubleArrayOf(0.0, 0.0, 0.0), main.name), 0)
        return out to notes
    }
}
