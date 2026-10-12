package com.webmaletin.gtaviewer.mesh

import com.webmaletin.gtaviewer.model.MaterialInfo

/**
 * Operaciones de unión. Tres niveles distintos (no son lo mismo):
 *  1. escena: varias partes en el mismo proyecto (no toca la geometría);
 *  2. combinar: concatenar mallas en una sola (sigue habiendo costuras);
 *  3. fusionar superficies: soldar vértices coincidentes (misma posición y UV) y unificar normales en la costura; además
 *     [stitch] acerca bordes cercanos entre dos piezas. NO es una booleana de volumen: no recorta ni rellena interiores.
 */
object MeshFusion {
    class FuseResult(val mesh: FlatMesh, val weldedVertices: Int, val seamNormals: Int, val note: String)

    /** Combina varias mallas YA en el mismo espacio en una sola. */
    fun combine(list: List<FlatMesh>): FlatMesh = FlatMesh.combine(list)

    /**
     * Fusiona superficies de [m]: vértices con la misma posición (±[tol]) y UV (±[uvTol]) pasan a ser UNO solo (se reduce la malla y
     * se cierran costuras topológicas); en las costuras donde la UV difiere se promedian las normales para que el sombreado sea continuo.
     */
    fun fuse(m: FlatMesh, tol: Float = 5e-4f, uvTol: Float = 1e-3f): FuseResult {
        val w = MeshAnalysis.weldMap(m.pos, tol)
        // grupo (posición soldada) → vértices
        val groups = HashMap<Int, MutableList<Int>>()
        for (v in 0 until m.vertexCount) groups.getOrPut(w[v]) { ArrayList() } += v
        val canon = IntArray(m.vertexCount) { it }
        var welded = 0; var seam = 0
        val out = m.copy()
        for ((_, g) in groups) {
            if (g.size < 2) continue
            // unificar normales del grupo (continuidad de sombreado)
            var nx = 0f; var ny = 0f; var nz = 0f
            for (v in g) { nx += m.nor[v * 3]; ny += m.nor[v * 3 + 1]; nz += m.nor[v * 3 + 2] }
            val l = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
            val smooth = l > 1e-6f && g.all { v -> (m.nor[v * 3] * nx + m.nor[v * 3 + 1] * ny + m.nor[v * 3 + 2] * nz) / l > 0.5f }
            // soldar los que comparten UV
            val reps = ArrayList<Int>()
            for (v in g) {
                val r = reps.firstOrNull { u -> Math.abs(m.uv[u * 2] - m.uv[v * 2]) <= uvTol && Math.abs(m.uv[u * 2 + 1] - m.uv[v * 2 + 1]) <= uvTol }
                if (r == null) reps += v else { canon[v] = r; welded++ }
            }
            if (smooth && g.size > 1) { for (v in g) { out.nor[v * 3] = nx / l; out.nor[v * 3 + 1] = ny / l; out.nor[v * 3 + 2] = nz / l }; seam += g.size }
        }
        val remapped = out.copy()
        for (i in remapped.tris.indices) remapped.tris[i] = canon[remapped.tris[i]]
        // eliminar degeneradas generadas por la soldadura y compactar
        val keep = (0 until remapped.triCount).filter { t -> val a = remapped.tris[t * 3]; val b = remapped.tris[t * 3 + 1]; val c = remapped.tris[t * 3 + 2]; a != b && b != c && a != c }
        val res = remapped.subset(keep)
        return FuseResult(res, welded, seam, "soldados $welded vértices coincidentes; normales unificadas en $seam vértices de costura (no es una booleana de volumen)")
    }

    /**
     * Cose [piece] a [base] (mismo espacio): los vértices de BORDE de la pieza a menos de [tol] de algún vértice de la base se
     * mueven a ese vértice. Cierra holguras pequeñas entre bordes que ya casan; no altera el interior de la pieza.
     */
    fun stitch(base: FlatMesh, piece: FlatMesh, tol: Float = 0.02f): Pair<FlatMesh, Int> {
        val out = piece.copy()
        // vértices de borde de la pieza (aristas con una sola cara)
        val w = MeshAnalysis.weldMap(piece.pos, 1e-4f)
        val cnt = HashMap<Long, Int>()
        fun ek(a: Int, b: Int) = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
        for (t in 0 until piece.triCount) { val vs = intArrayOf(w[piece.tris[t * 3]], w[piece.tris[t * 3 + 1]], w[piece.tris[t * 3 + 2]]); for (k in 0..2) cnt.merge(ek(vs[k], vs[(k + 1) % 3]), 1, Int::plus) }
        val border = HashSet<Int>()
        for ((k, c) in cnt) if (c == 1) { border += (k shr 32).toInt(); border += (k and 0xFFFFFFFFL).toInt() }
        // rejilla espacial de vértices de la base
        val cell = tol
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(x: Int, y: Int, z: Int) = (x.toLong() and 0x1FFFFF) or ((y.toLong() and 0x1FFFFF) shl 21) or ((z.toLong() and 0x1FFFFF) shl 42)
        val usedB = BooleanArray(base.vertexCount); base.tris.forEach { usedB[it] = true }
        for (v in 0 until base.vertexCount) if (usedB[v]) grid.getOrPut(key(Math.floor((base.pos[v * 3] / cell).toDouble()).toInt(), Math.floor((base.pos[v * 3 + 1] / cell).toDouble()).toInt(), Math.floor((base.pos[v * 3 + 2] / cell).toDouble()).toInt())) { ArrayList() } += v
        var moved = 0
        for (v in 0 until piece.vertexCount) {
            if (w[v] !in border) continue
            val px = piece.pos[v * 3]; val py = piece.pos[v * 3 + 1]; val pz = piece.pos[v * 3 + 2]
            val cx = Math.floor((px / cell).toDouble()).toInt(); val cy = Math.floor((py / cell).toDouble()).toInt(); val cz = Math.floor((pz / cell).toDouble()).toInt()
            var best = -1; var bd = tol * tol
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) for (b in grid[key(cx + dx, cy + dy, cz + dz)].orEmpty()) {
                val d = (base.pos[b * 3] - px).let { it * it } + (base.pos[b * 3 + 1] - py).let { it * it } + (base.pos[b * 3 + 2] - pz).let { it * it }
                if (d < bd) { bd = d; best = b }
            }
            if (best >= 0 && bd > 0f) { out.pos[v * 3] = base.pos[best * 3]; out.pos[v * 3 + 1] = base.pos[best * 3 + 1]; out.pos[v * 3 + 2] = base.pos[best * 3 + 2]; moved++ }
        }
        return out to moved
    }
}
