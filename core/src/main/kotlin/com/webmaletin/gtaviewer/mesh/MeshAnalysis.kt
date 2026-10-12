package com.webmaletin.gtaviewer.mesh

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Resultado del análisis de una malla (todo medido sobre posiciones soldadas, no sobre índices). */
class MeshReport(
    val triangles: Int,
    val degenerate: Int,
    val duplicateFaces: Int,
    /** Aristas con una sola cara (malla abierta). */
    val openEdges: Int,
    val nonManifoldEdges: Int,
    /** Caras cuyo winding contradice a la cara vecina (superficie inconsistente). */
    val inconsistentFaces: Int,
    /** Caras cuya normal geométrica contradice a las normales de vértice (probablemente invertidas). */
    val flippedVsNormals: Int,
    val components: Int,
    /** Bucles de arista abierta (agujeros). */
    val holes: Int,
    val unusedVertices: Int,
) {
    val isClean get() = degenerate == 0 && duplicateFaces == 0 && inconsistentFaces == 0 && flippedVsNormals == 0 && nonManifoldEdges == 0
    val isWatertight get() = openEdges == 0
    fun summary() = "tri=$triangles, degeneradas=$degenerate, duplicadas=$duplicateFaces, aristas abiertas=$openEdges, no-manifold=$nonManifoldEdges, " +
        "inconsistentes=$inconsistentFaces, invertidas(vs normales)=$flippedVsNormals, componentes=$components, agujeros=$holes"
}

class RepairResult(val mesh: FlatMesh, val before: MeshReport, val after: MeshReport, val actions: List<String>, val changed: Boolean) {
    /** Solo se declara "reparado" si las métricas mejoran y no aparece ningún problema nuevo (validado re-analizando). */
    val validated: Boolean get() = changed && after.degenerate <= before.degenerate && after.duplicateFaces <= before.duplicateFaces &&
        after.inconsistentFaces <= before.inconsistentFaces && after.flippedVsNormals <= before.flippedVsNormals &&
        after.nonManifoldEdges <= before.nonManifoldEdges && after.openEdges <= before.openEdges + 0 &&
        (after.degenerate < before.degenerate || after.duplicateFaces < before.duplicateFaces || after.inconsistentFaces < before.inconsistentFaces ||
            after.flippedVsNormals < before.flippedVsNormals || after.holes < before.holes || after.nonManifoldEdges < before.nonManifoldEdges)
}

private data class Cell(val x: Long, val y: Long, val z: Long)

object MeshAnalysis {
    /** Mapa de soldadura: índice de vértice → representante canónico (posiciones iguales dentro de [tol]). */
    fun weldMap(pos: FloatArray, tol: Float = 1e-4f): IntArray {
        val n = pos.size / 3
        val map = IntArray(n)
        val grid = HashMap<Cell, Int>(n * 2)
        val inv = 1f / tol
        for (v in 0 until n) {
            val qx = Math.round(pos[v * 3] * inv).toLong(); val qy = Math.round(pos[v * 3 + 1] * inv).toLong(); val qz = Math.round(pos[v * 3 + 2] * inv).toLong()
            map[v] = grid.getOrPut(Cell(qx, qy, qz)) { v }
        }
        return map
    }

    private fun edgeKey(a: Int, b: Int): Long = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()

    private fun area2(m: FlatMesh, t: Int): Float {
        val a = m.tris[t * 3] * 3; val b = m.tris[t * 3 + 1] * 3; val c = m.tris[t * 3 + 2] * 3
        val ux = m.pos[b] - m.pos[a]; val uy = m.pos[b + 1] - m.pos[a + 1]; val uz = m.pos[b + 2] - m.pos[a + 2]
        val vx = m.pos[c] - m.pos[a]; val vy = m.pos[c + 1] - m.pos[a + 1]; val vz = m.pos[c + 2] - m.pos[a + 2]
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        return sqrt(nx * nx + ny * ny + nz * nz)
    }

    fun analyze(m: FlatMesh, tol: Float = 1e-4f): MeshReport {
        val w = weldMap(m.pos, tol)
        val nT = m.triCount
        var degenerate = 0; var dup = 0
        val seenFaces = HashSet<Triple<Int, Int, Int>>()
        val edgeFaces = HashMap<Long, MutableList<Int>>()
        val valid = BooleanArray(nT)
        for (t in 0 until nT) {
            val a = w[m.tris[t * 3]]; val b = w[m.tris[t * 3 + 1]]; val c = w[m.tris[t * 3 + 2]]
            if (a == b || b == c || a == c || area2(m, t) < 1e-10f) { degenerate++; continue }
            val s = listOf(a, b, c).sorted()
            if (!seenFaces.add(Triple(s[0], s[1], s[2]))) { dup++; continue }
            valid[t] = true
            for ((x, y) in listOf(a to b, b to c, c to a)) edgeFaces.getOrPut(edgeKey(x, y)) { ArrayList() } += t
        }
        var open = 0; var nonManifold = 0
        for (l in edgeFaces.values) if (l.size == 1) open++ else if (l.size > 2) nonManifold++
        // consistencia de winding: dos caras que comparten arista deben recorrerla en sentidos opuestos
        var inconsistent = 0
        for ((_, l) in edgeFaces) if (l.size == 2) {
            fun dirOf(t: Int, key: Long): Int {
                val vs = intArrayOf(w[m.tris[t * 3]], w[m.tris[t * 3 + 1]], w[m.tris[t * 3 + 2]])
                for (k in 0..2) { val a = vs[k]; val b = vs[(k + 1) % 3]; if (edgeKey(a, b) == key) return if (a < b) 1 else -1 }
                return 0
            }
        }
        // (el chequeo anterior se hace por pares de forma explícita más abajo para poder contar caras)
        val key2faces = edgeFaces.filter { it.value.size == 2 }
        val bad = HashSet<Int>()
        for ((key, l) in key2faces) {
            fun dir(t: Int): Int {
                val vs = intArrayOf(w[m.tris[t * 3]], w[m.tris[t * 3 + 1]], w[m.tris[t * 3 + 2]])
                for (k in 0..2) { val a = vs[k]; val b = vs[(k + 1) % 3]; if (edgeKey(a, b) == key) return if (a < b) 1 else -1 }
                return 0
            }
            if (dir(l[0]) == dir(l[1])) { bad += l[1] }
        }
        inconsistent = bad.size
        // contra normales de vértice
        var flipped = 0
        for (t in 0 until nT) if (valid[t]) {
            val a = m.tris[t * 3] * 3; val b = m.tris[t * 3 + 1] * 3; val c = m.tris[t * 3 + 2] * 3
            val ux = m.pos[b] - m.pos[a]; val uy = m.pos[b + 1] - m.pos[a + 1]; val uz = m.pos[b + 2] - m.pos[a + 2]
            val vx = m.pos[c] - m.pos[a]; val vy = m.pos[c + 1] - m.pos[a + 1]; val vz = m.pos[c + 2] - m.pos[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val d = nx * (m.nor[a] + m.nor[b] + m.nor[c]) + ny * (m.nor[a + 1] + m.nor[b + 1] + m.nor[c + 1]) + nz * (m.nor[a + 2] + m.nor[b + 2] + m.nor[c + 2])
            if (d < 0f) flipped++
        }
        val comps = connectedComponents(m, tol).size
        val holes = countHoleLoops(edgeFaces, w, m)
        val used = BooleanArray(m.vertexCount); m.tris.forEach { used[it] = true }
        return MeshReport(nT, degenerate, dup, open, nonManifold, inconsistent, flipped, comps, holes, used.count { !it })
    }

    private fun countHoleLoops(edgeFaces: Map<Long, List<Int>>, w: IntArray, m: FlatMesh): Int {
        // grafo de aristas abiertas por vértice soldado
        val adj = HashMap<Int, MutableList<Int>>()
        for ((k, l) in edgeFaces) if (l.size == 1) { val a = (k shr 32).toInt(); val b = (k and 0xFFFFFFFFL).toInt(); adj.getOrPut(a) { ArrayList() } += b; adj.getOrPut(b) { ArrayList() } += a }
        val seen = HashSet<Int>(); var loops = 0
        for (s in adj.keys) if (s !in seen) {
            loops++; val stack = ArrayDeque<Int>(); stack += s; seen += s
            while (stack.isNotEmpty()) { val v = stack.removeLast(); for (n in adj[v].orEmpty()) if (seen.add(n)) stack += n }
        }
        return loops
    }

    /** Componentes conexos por vértices soldados: lista de índices de triángulo por componente (mayor primero). */
    fun connectedComponents(m: FlatMesh, tol: Float = 1e-4f): List<IntArray> {
        val w = weldMap(m.pos, tol)
        val parent = IntArray(m.vertexCount) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
        for (t in 0 until m.triCount) { val a = w[m.tris[t * 3]]; union(a, w[m.tris[t * 3 + 1]]); union(a, w[m.tris[t * 3 + 2]]) }
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (t in 0 until m.triCount) groups.getOrPut(find(w[m.tris[t * 3]])) { ArrayList() } += t
        return groups.values.map { it.toIntArray() }.sortedByDescending { it.size }
    }

    /**
     * Selecciona un "parche" alrededor de [seed]: caras conectadas por arista cuyo ángulo con la vecina sea < [maxAngleDeg]
     * (y, si [sameMaterial], del mismo material). Sirve para elegir un emblema o un panel dentro de una malla grande.
     */
    fun growRegion(m: FlatMesh, seed: Int, maxAngleDeg: Float, sameMaterial: Boolean = true, tol: Float = 1e-4f, limit: Int = 200_000): Set<Int> {
        val w = weldMap(m.pos, tol)
        val edge = HashMap<Long, MutableList<Int>>()
        val normals = FloatArray(m.triCount * 3)
        for (t in 0 until m.triCount) {
            val a = m.tris[t * 3] * 3; val b = m.tris[t * 3 + 1] * 3; val c = m.tris[t * 3 + 2] * 3
            val ux = m.pos[b] - m.pos[a]; val uy = m.pos[b + 1] - m.pos[a + 1]; val uz = m.pos[b + 2] - m.pos[a + 2]
            val vx = m.pos[c] - m.pos[a]; val vy = m.pos[c + 1] - m.pos[a + 1]; val vz = m.pos[c + 2] - m.pos[a + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz).let { if (it < 1e-12f) 1f else it }
            normals[t * 3] = nx / l; normals[t * 3 + 1] = ny / l; normals[t * 3 + 2] = nz / l
            val vs = intArrayOf(w[m.tris[t * 3]], w[m.tris[t * 3 + 1]], w[m.tris[t * 3 + 2]])
            for (k in 0..2) edge.getOrPut(edgeKey(vs[k], vs[(k + 1) % 3])) { ArrayList() } += t
        }
        val cosMin = Math.cos(Math.toRadians(maxAngleDeg.toDouble())).toFloat()
        val out = HashSet<Int>(); val q = ArrayDeque<Int>(); q += seed; out += seed
        while (q.isNotEmpty() && out.size < limit) {
            val t = q.removeFirst()
            val vs = intArrayOf(w[m.tris[t * 3]], w[m.tris[t * 3 + 1]], w[m.tris[t * 3 + 2]])
            for (k in 0..2) for (n in edge[edgeKey(vs[k], vs[(k + 1) % 3])].orEmpty()) {
                if (n in out) continue
                if (sameMaterial && m.triMat[n] != m.triMat[seed]) continue
                val d = normals[t * 3] * normals[n * 3] + normals[t * 3 + 1] * normals[n * 3 + 1] + normals[t * 3 + 2] * normals[n * 3 + 2]
                if (d >= cosMin) { out += n; q += n }
            }
        }
        return out
    }

    /**
     * Repara de forma conservadora: elimina degeneradas y duplicadas, hace consistente el winding, orienta según las normales
     * (o el volumen) y cierra agujeros pequeños. Devuelve una COPIA y vuelve a analizar para validar.
     */
    fun repair(src: FlatMesh, tol: Float = 1e-4f, maxHoleEdges: Int = 12): RepairResult {
        val before = analyze(src, tol)
        val m = src.copy()
        val actions = ArrayList<String>()
        val w = weldMap(m.pos, tol)

        // 1) degeneradas y duplicadas
        val drop = HashSet<Int>(); val seen = HashSet<Triple<Int, Int, Int>>()
        for (t in 0 until m.triCount) {
            val a = w[m.tris[t * 3]]; val b = w[m.tris[t * 3 + 1]]; val c = w[m.tris[t * 3 + 2]]
            if (a == b || b == c || a == c || area2(m, t) < 1e-10f) { drop += t; continue }
            val s = listOf(a, b, c).sorted()
            if (!seen.add(Triple(s[0], s[1], s[2]))) drop += t
        }
        var cur = m
        if (drop.isNotEmpty()) { cur = m.withoutTriangles(drop); actions += "eliminadas ${drop.size} caras degeneradas/duplicadas" }

        // 2) winding consistente por componente (propagación por aristas) y orientación global
        val w2 = weldMap(cur.pos, tol)
        val edgeTris = HashMap<Long, MutableList<Int>>()
        for (t in 0 until cur.triCount) { val vs = intArrayOf(w2[cur.tris[t * 3]], w2[cur.tris[t * 3 + 1]], w2[cur.tris[t * 3 + 2]]); for (k in 0..2) edgeTris.getOrPut(edgeKey(vs[k], vs[(k + 1) % 3])) { ArrayList() } += t }
        val flip = BooleanArray(cur.triCount); val visited = BooleanArray(cur.triCount)
        fun dirOf(t: Int, key: Long, flipped: Boolean): Int {
            val vs = intArrayOf(w2[cur.tris[t * 3]], w2[cur.tris[t * 3 + 1]], w2[cur.tris[t * 3 + 2]])
            if (flipped) { val x = vs[1]; vs[1] = vs[2]; vs[2] = x }
            for (k in 0..2) { val a = vs[k]; val b = vs[(k + 1) % 3]; if (edgeKey(a, b) == key) return if (a < b) 1 else -1 }
            return 0
        }
        var flippedCount = 0
        for (s in 0 until cur.triCount) if (!visited[s]) {
            val comp = ArrayList<Int>(); val q = ArrayDeque<Int>(); q += s; visited[s] = true
            while (q.isNotEmpty()) {
                val t = q.removeFirst(); comp += t
                val vs = intArrayOf(w2[cur.tris[t * 3]], w2[cur.tris[t * 3 + 1]], w2[cur.tris[t * 3 + 2]])
                for (k in 0..2) {
                    val key = edgeKey(vs[k], vs[(k + 1) % 3])
                    val l = edgeTris[key] ?: continue
                    if (l.size != 2) continue
                    val n = if (l[0] == t) l[1] else l[0]
                    if (visited[n]) continue
                    visited[n] = true
                    if (dirOf(n, key, flip[n]) == dirOf(t, key, flip[t])) flip[n] = true
                    q += n
                }
            }
            // orientación del componente: mayoría según normales de vértice; si no hay, signo del volumen
            var vote = 0
            for (t in comp) {
                val a = cur.tris[t * 3] * 3; val b = cur.tris[t * 3 + 1] * 3; val c = cur.tris[t * 3 + 2] * 3
                val ux = cur.pos[b] - cur.pos[a]; val uy = cur.pos[b + 1] - cur.pos[a + 1]; val uz = cur.pos[b + 2] - cur.pos[a + 2]
                val vx = cur.pos[c] - cur.pos[a]; val vy = cur.pos[c + 1] - cur.pos[a + 1]; val vz = cur.pos[c + 2] - cur.pos[a + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                var d = nx * (cur.nor[a] + cur.nor[b] + cur.nor[c]) + ny * (cur.nor[a + 1] + cur.nor[b + 1] + cur.nor[c + 1]) + nz * (cur.nor[a + 2] + cur.nor[b + 2] + cur.nor[c + 2])
                if (flip[t]) d = -d
                vote += if (d > 0) 1 else if (d < 0) -1 else 0
            }
            if (vote < 0) for (t in comp) flip[t] = !flip[t]
        }
        val toFlip = (0 until cur.triCount).filter { flip[it] }
        if (toFlip.isNotEmpty()) {
            for (t in toFlip) { val x = cur.tris[t * 3 + 1]; cur.tris[t * 3 + 1] = cur.tris[t * 3 + 2]; cur.tris[t * 3 + 2] = x }
            actions += "corregido el winding de ${toFlip.size} caras"
        }

        // 3) agujeros pequeños: relleno en abanico
        val filled = fillSmallHoles(cur, tol, maxHoleEdges)
        if (filled > 0) { cur = filled.let { cur }; actions += "cerrados $filled agujeros pequeños (≤$maxHoleEdges aristas)" }

        val after = analyze(cur, tol)
        val changed = actions.isNotEmpty()
        return RepairResult(cur, before, after, actions, changed)
    }

    /** Cierra bucles de aristas abiertas de hasta [maxEdges] aristas con un abanico de triángulos. Devuelve cuántos cerró. */
    private fun fillSmallHoles(m: FlatMesh, tol: Float, maxEdges: Int): Int {
        val w = weldMap(m.pos, tol)
        val count = HashMap<Long, Int>(); val dirEdge = HashMap<Long, Pair<Int, Int>>()
        for (t in 0 until m.triCount) {
            val vs = intArrayOf(w[m.tris[t * 3]], w[m.tris[t * 3 + 1]], w[m.tris[t * 3 + 2]])
            for (k in 0..2) { val a = vs[k]; val b = vs[(k + 1) % 3]; val key = edgeKey(a, b); count.merge(key, 1, Int::plus); dirEdge[key] = a to b }
        }
        // aristas abiertas dirigidas (a→b tal como aparecen en su única cara); el agujero se recorre en sentido contrario (b→a)
        val next = HashMap<Int, MutableList<Int>>()
        for ((k, c) in count) if (c == 1) { val (a, b) = dirEdge[k]!!; next.getOrPut(b) { ArrayList() } += a }
        val rep = HashMap<Int, Int>() // vértice soldado → un índice real
        for (v in 0 until m.vertexCount) rep.putIfAbsent(w[v], v)
        val newTris = ArrayList<Int>(); val newMat = ArrayList<Int>()
        val used = HashSet<Int>(); var filled = 0
        for (start in next.keys.toList()) {
            if (start in used) continue
            val loop = ArrayList<Int>(); var cur = start; var ok = true
            while (true) {
                loop += cur; used += cur
                val n = next[cur]
                if (n == null || n.size != 1) { ok = false; break }
                cur = n[0]
                if (cur == start) break
                if (loop.size > maxEdges) { ok = false; break }
            }
            if (!ok || loop.size < 3) continue
            for (i in 1 until loop.size - 1) { newTris += rep[loop[0]]!!; newTris += rep[loop[i]]!!; newTris += rep[loop[i + 1]]!!; newMat += 0 }
            filled++
        }
        if (filled > 0) {
            m.tris = m.tris + newTris.toIntArray(); m.triMat = m.triMat + newMat.toIntArray()
        }
        return filled
    }

    // ------------------------------------------------------------------ intersecciones y salientes entre piezas

    class Overlap(val sharedVoxels: Int, val ratioOfSmaller: Float, val protrusion: Float)

    /**
     * Mide qué tanto se solapan dos mallas (en el mismo espacio) con una rejilla de vóxeles de [cell] metros: proporción de vóxeles
     * ocupados por ambas respecto a la menor, y fracción de vértices de [piece] fuera de la caja ampliada de [base].
     */
    fun overlap(base: FlatMesh, piece: FlatMesh, cell: Float = 0.08f): Overlap {
        fun voxels(m: FlatMesh): HashSet<Long> {
            val s = HashSet<Long>()
            for (t in 0 until m.triCount) {
                val a = m.tris[t * 3] * 3; val b = m.tris[t * 3 + 1] * 3; val c = m.tris[t * 3 + 2] * 3
                val steps = 3
                for (i in 0..steps) for (j in 0..(steps - i)) {
                    val u = i / steps.toFloat(); val v = j / steps.toFloat(); val k = 1f - u - v
                    val x = m.pos[a] * u + m.pos[b] * v + m.pos[c] * k
                    val y = m.pos[a + 1] * u + m.pos[b + 1] * v + m.pos[c + 1] * k
                    val z = m.pos[a + 2] * u + m.pos[b + 2] * v + m.pos[c + 2] * k
                    s += ((Math.floor((x / cell).toDouble()).toLong() and 0x1FFFFF) shl 42) or ((Math.floor((y / cell).toDouble()).toLong() and 0x1FFFFF) shl 21) or (Math.floor((z / cell).toDouble()).toLong() and 0x1FFFFF)
                }
            }
            return s
        }
        val vb = voxels(base); val vp = voxels(piece)
        val shared = vp.count { it in vb }
        val bb = base.bounds(); val pad = cell * 2
        var outside = 0; var total = 0
        val used = BooleanArray(piece.vertexCount); piece.tris.forEach { used[it] = true }
        for (v in 0 until piece.vertexCount) if (used[v]) {
            total++
            val x = piece.pos[v * 3]; val y = piece.pos[v * 3 + 1]; val z = piece.pos[v * 3 + 2]
            if (x < bb.min[0] - pad || x > bb.max[0] + pad || y < bb.min[1] - pad || y > bb.max[1] + pad || z < bb.min[2] - pad || z > bb.max[2] + pad) outside++
        }
        return Overlap(shared, if (min(vb.size, vp.size) == 0) 0f else shared.toFloat() / min(vb.size, vp.size), if (total == 0) 0f else outside.toFloat() / total)
    }
}
