package com.webmaletin.gtaviewer.gta

import com.webmaletin.gtaviewer.catalog.*
import com.webmaletin.gtaviewer.zip.ArchiveEntry

class TxdMatch(val txd: ArchiveEntry, val tier: Int, val note: String?)

/**
 * Emparejamiento por niveles:
 *  1. mismo nombre exacto (sin distinguir mayúsculas);
 *  2. mismo nombre normalizado (ignora espacios, guiones, guiones bajos, símbolos);
 *  3. con varios candidatos gana el TXD de la misma carpeta;
 *  4. si no, la carpeta más cercana en el árbol (padres/hijas);
 *  5. último recurso (solo vehículos): único DFF + único TXD en la misma carpeta con nombres distintos (se avisa).
 */
object TxdMatcher {
    private class Cand(val dff: ArchiveEntry, val txd: ArchiveEntry, val tier: Int, val dist: Int)

    fun match(
        dffs: List<ArchiveEntry>, txds: List<ArchiveEntry>,
        isVehicle: Map<ArchiveEntry, Boolean> = emptyMap(),
    ): Map<ArchiveEntry, TxdMatch> {
        val cands = ArrayList<Cand>()
        val txdByNorm = txds.groupBy { Names.normalize(it.baseName) }
        for (d in dffs) {
            val dn = Names.normalize(d.baseName)
            for (t in txdByNorm[dn].orEmpty()) {
                val tier = if (t.baseName.equals(d.baseName, ignoreCase = true)) 1 else 2
                cands += Cand(d, t, tier, Paths.distance(d.dir, t.dir))
            }
        }
        // Misma carpeta primero, luego nivel de coincidencia, luego cercanía y, por último, orden estable por ruta.
        cands.sortWith(
            compareBy<Cand>({ if (it.dist == 0) 0 else 1 }, { it.tier }, { it.dist }, { it.txd.path.length }, { it.txd.path.lowercase() }, { it.dff.path.lowercase() }),
        )

        val result = LinkedHashMap<ArchiveEntry, TxdMatch>()
        val owner = HashMap<ArchiveEntry, ArchiveEntry>() // txd -> dff que lo reclamó
        for (c in cands) {
            if (c.dff in result) continue
            val prev = owner[c.txd]
            // Un TXD no puede ser compartido por dos DFF con el mismo nombre (p. ej. dos "audia3.dff" en carpetas distintas).
            if (prev != null && Names.normalize(prev.baseName) == Names.normalize(c.dff.baseName)) continue
            val ambiguous = cands.count { it.dff === c.dff && it.dist == c.dist && it.tier == c.tier } > 1
            val note = when {
                c.tier == 2 -> "TXD emparejado por nombre normalizado (${c.txd.fileName})"
                ambiguous -> "Varios TXD con el mismo nombre a igual distancia; se eligió ${c.txd.path}"
                else -> null
            }
            result[c.dff] = TxdMatch(c.txd, c.tier, note)
            owner[c.txd] = c.dff
        }

        // Nivel 5
        val claimed = result.values.map { it.txd }.toSet()
        for (d in dffs) {
            if (d in result || isVehicle[d] == false) continue
            val sameDirDffs = dffs.filter { it.dir.equals(d.dir, true) && isVehicle[it] != false }
            val sameDirTxds = txds.filter { it.dir.equals(d.dir, true) && it !in claimed }
            if (sameDirDffs.size == 1 && sameDirTxds.size == 1) {
                result[d] = TxdMatch(sameDirTxds[0], 5, "TXD emparejado solo por carpeta (nombre distinto: ${sameDirTxds[0].fileName})")
            }
        }
        return result
    }
}

/** Decide si un DFF es un vehículo completo o una pieza (ruedas, puertas, tuning...). */
object DffClassifier {
    private val TUNING_FILE = Regex("^(exh|fbmp|rbmp|spl|rf|wg|bnt|bnts|lgt|nto|bbb|fbb|rbb|sskirt|bmp)(_[a-z0-9]+)+$")

    fun classify(entry: ArchiveEntry, summary: com.webmaletin.gtaviewer.dff.DffSummary?): DffClassification {
        var vehicle = 0; var part = 0
        val reasons = ArrayList<String>()
        val frames = summary?.frameNames?.map { it.lowercase() }.orEmpty()
        if (frames.any { it.startsWith("chassis") }) { vehicle += 5; reasons += "frame 'chassis'" }
        val dummies = frames.count { f ->
            f.startsWith("wheel_") || f.startsWith("door_") || f == "bonnet_dummy" || f == "boot_dummy" ||
                f.startsWith("bump_") || f.startsWith("wing_") || f == "windscreen_dummy"
        }
        if (dummies >= 2) { vehicle += 3; reasons += "$dummies frames de vehículo (ruedas/puertas...)" }
        if ((summary?.geometryCount ?: 0) >= 3) vehicle += 1

        // Convención de las piezas de tuning de GTA SA: fbmp_/rbmp_/exh_/spl_/rf_/wg_l|r_/bnt_… (p. ej. "fbmp_a_vwg.dff")
        if (TUNING_FILE.containsMatchIn(entry.baseName.lowercase())) { part += 4; reasons += "nombre de pieza de tuning de GTA SA (${entry.baseName})" }
        val fileKw = Names.partKeyword(entry.baseName)
        if (fileKw != null) { part += 3; reasons += "nombre de archivo contiene '$fileKw'" }
        val folderKw = if (entry.dir.isEmpty()) null else Names.partKeyword(entry.dir.substringAfterLast('/'))
        if (folderKw != null) { part += if (folderKw in setOf("parts", "part", "tuning")) 2 else 1; reasons += "carpeta contiene '$folderKw'" }
        if (summary != null && summary.valid && summary.geometryCount == 1 && frames.none { it.startsWith("chassis") }) part += 1

        val kind = if (vehicle >= 5) DffKind.VEHICLE else if (part >= 3) DffKind.PART else DffKind.VEHICLE
        return DffClassification(kind, vehicle, part, reasons)
    }
}

object PartAttacher {
    class Owner(val vehicle: ArchiveEntry?, val reason: String)

    fun findOwner(part: ArchiveEntry, vehicles: List<ArchiveEntry>): Owner {
        if (vehicles.isEmpty()) return Owner(null, "no hay vehículos en el ZIP")
        val dists = vehicles.map { it to Paths.distance(part.dir, it.dir) }
        val min = dists.minOf { it.second }
        if (min > 3) return Owner(null, "ningún vehículo cerca (distancia $min)")
        val nearest = dists.filter { it.second == min }.map { it.first }
        if (nearest.size == 1) return Owner(nearest[0], if (min == 0) "misma carpeta" else "carpeta cercana (distancia $min)")
        val pn = Names.normalize(part.baseName) + Names.normalize(part.dir.substringAfterLast('/'))
        val byName = nearest.filter { v -> Names.normalize(v.baseName).let { it.length >= 3 && pn.contains(it) } }
        if (byName.size == 1) return Owner(byName[0], "nombre de la pieza contiene '${byName[0].baseName}'")
        return Owner(null, "${nearest.size} vehículos igual de cercanos, ambiguo")
    }
}

object Dedupe {
    class Item<T>(val payload: T, val dff: ArchiveEntry, val txd: ArchiveEntry?)
    class Group<T>(val primary: T, val duplicates: List<ArchiveEntry>)

    private val BAD_FOLDERS = listOf("backup", "bak", "old", "copy", "copia", "tmp", "temp", "unused")

    private fun penalty(e: ArchiveEntry): Int =
        (if (BAD_FOLDERS.any { b -> e.path.lowercase().split('/').any { it.contains(b) } }) 100 else 0) + e.path.count { it == '/' }

    fun <T> collapse(items: List<Item<T>>): List<Group<T>> {
        fun sameDff(a: ArchiveEntry, b: ArchiveEntry) = a.size == b.size && a.crc == b.crc && a.crc != 0L &&
            Names.normalize(a.baseName) == Names.normalize(b.baseName)
        fun sameTxd(a: ArchiveEntry?, b: ArchiveEntry?) = (a == null && b == null) || (a != null && b != null && a.size == b.size && a.crc == b.crc)

        val ordered = items.sortedWith(compareBy({ penalty(it.dff) }, { it.dff.path.lowercase() }))
        // Fase 1: idénticos en DFF y TXD.
        val groups = ArrayList<MutableList<Item<T>>>()
        for (item in ordered) {
            val g = groups.firstOrNull { sameDff(it.first().dff, item.dff) && sameTxd(it.first().txd, item.txd) }
            if (g == null) groups += mutableListOf(item) else g += item
        }
        // Fase 2: un DFF idéntico sin TXD se pliega al grupo con TXD (si hay uno solo; con varios variantes no se adivina).
        val result = ArrayList<MutableList<Item<T>>>()
        for (g in groups) {
            if (g.first().txd == null) {
                val targets = groups.filter { it.first().txd != null && sameDff(it.first().dff, g.first().dff) }
                if (targets.size == 1) { targets[0] += g; continue }
            }
            result += g
        }
        return result.map { g ->
            val primary = g.sortedWith(compareBy({ if (it.txd == null) 1 else 0 }, { penalty(it.dff) }, { it.dff.path.lowercase() })).first()
            Group(primary.payload, g.filter { it !== primary }.map { it.dff })
        }
    }
}
