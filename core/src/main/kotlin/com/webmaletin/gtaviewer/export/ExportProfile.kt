package com.webmaletin.gtaviewer.export

import kotlinx.serialization.Serializable

/**
 * Perfil de exportación (p. ej. Godot). Las unidades de origen son metros; el visor/GLB usan Y arriba y el frente del
 * vehículo hacia +Z (convención glTF). Godot usa -Z como "adelante" para VehicleBody3D, por eso existe FRONT_MINUS_Z.
 */
@Serializable data class ExportProfile(
    val name: String = "Predeterminado (glTF)",
    val scale: Float = 1f,
    /** "PLUS_Z" (glTF) o "MINUS_Z" (Godot VehicleBody3D: el frente apunta a -Z). */
    val frontAxis: String = "PLUS_Z",
    /** "ORIGINAL" o "GROUND_CENTER" (origen en el suelo, centrado en X/Z: ideal para físicas de juego). */
    val originMode: String = "ORIGINAL",
    /** Una sola malla por material (en vez de una por pieza). Las piezas mecánicas (ruedas) pueden seguir separadas. */
    val combineMeshes: Boolean = false,
    val keepWheelsSeparate: Boolean = true,
) {
    fun describe(): String = "$name · escala ×$scale · frente ${if (frontAxis == "MINUS_Z") "-Z (Godot)" else "+Z (glTF)"} · origen ${if (originMode == "GROUND_CENTER") "suelo/centro" else "original"} · " +
        (if (combineMeshes) "malla combinada" else "piezas separadas") + " · unidades: metros"

    companion object {
        val DEFAULT = ExportProfile()
        val GODOT = ExportProfile("Godot 4 (VehicleBody3D)", 1f, "MINUS_Z", "GROUND_CENTER", false, true)
        val BUILTIN = listOf(DEFAULT, GODOT)
    }
}

/** Aplica un perfil a una escena de exportación ya construida (nuevas matrices de vértices; no modifica la original). */
object ProfileApplier {
    fun apply(scene: ExportScene, p: ExportProfile): ExportScene {
        if (p == ExportProfile.DEFAULT) return scene
        // 1) caja de lo exportado para el origen
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (m in scene.meshes) for (v in 0 until m.vertexCount) {
            val x = m.positions[v * 3]; val y = m.positions[v * 3 + 1]; val z = m.positions[v * 3 + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
        val any = minX <= maxX
        val ox = if (p.originMode == "GROUND_CENTER" && any) (minX + maxX) / 2 else 0f
        val oy = if (p.originMode == "GROUND_CENTER" && any) minY else 0f
        val oz = if (p.originMode == "GROUND_CENTER" && any) (minZ + maxZ) / 2 else 0f
        val flip = p.frontAxis == "MINUS_Z"
        val meshes = scene.meshes.map { m ->
            val pos = FloatArray(m.positions.size); val nor = FloatArray(m.normals.size)
            for (v in 0 until m.vertexCount) {
                var x = (m.positions[v * 3] - ox); val y = (m.positions[v * 3 + 1] - oy); var z = (m.positions[v * 3 + 2] - oz)
                var nx = m.normals[v * 3]; val ny = m.normals[v * 3 + 1]; var nz = m.normals[v * 3 + 2]
                if (flip) { x = -x; z = -z; nx = -nx; nz = -nz }
                pos[v * 3] = x * p.scale; pos[v * 3 + 1] = y * p.scale; pos[v * 3 + 2] = z * p.scale
                nor[v * 3] = nx; nor[v * 3 + 1] = ny; nor[v * 3 + 2] = nz
            }
            ExportMesh(m.partName, m.name, m.material, pos, nor, m.uvs, m.indices)
        }
        val finalMeshes = if (p.combineMeshes) combine(meshes, p.keepWheelsSeparate) else meshes
        return ExportScene(scene.name, finalMeshes, scene.materials, scene.textures, scene.warnings + "Perfil: ${p.describe()}")
    }

    /** Una malla por material. Las ruedas (partes cuyo nombre contiene "wheel"/"rueda") se mantienen sueltas si [keepWheels]. */
    private fun combine(meshes: List<ExportMesh>, keepWheels: Boolean): List<ExportMesh> {
        fun isWheel(m: ExportMesh) = m.partName.lowercase().let { "wheel" in it || "rueda" in it || "tyre" in it || "tire" in it }
        val keep = if (keepWheels) meshes.filter(::isWheel) else emptyList()
        val rest = meshes.filter { it !in keep }
        val out = ArrayList<ExportMesh>(keep)
        for ((mat, list) in rest.groupBy { it.material }) {
            val nv = list.sumOf { it.vertexCount }
            val pos = FloatArray(nv * 3); val nor = FloatArray(nv * 3); val uv = FloatArray(nv * 2)
            val idx = ArrayList<Int>(); var base = 0
            for (m in list) {
                System.arraycopy(m.positions, 0, pos, base * 3, m.positions.size); System.arraycopy(m.normals, 0, nor, base * 3, m.normals.size); System.arraycopy(m.uvs, 0, uv, base * 2, m.uvs.size)
                m.indices.forEach { idx += it + base }; base += m.vertexCount
            }
            out += ExportMesh("Carroceria", "Carroceria_${mat.name}", mat, pos, nor, uv, idx.toIntArray())
        }
        return out
    }
}
