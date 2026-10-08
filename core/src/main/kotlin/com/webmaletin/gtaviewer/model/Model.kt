package com.webmaletin.gtaviewer.model

import com.webmaletin.gtaviewer.txd.TextureImage

enum class PartCategory(val label: String, val order: Int) {
    BODY("Carrocería", 0), INTERIOR("Interior", 1), WHEEL("Ruedas", 2), DOOR("Puertas", 3), HOOD("Capó", 4),
    TRUNK("Baúl", 5), ENGINE("Motor", 6), EXHAUST("Escape", 7), LIGHT("Luces", 8), WINDOW("Cristales", 9),
    BUMPER("Paragolpes", 10), SPOILER("Spoiler", 11), MIRROR("Espejos", 12), FENDER("Guardabarros", 13),
    BRAKES("Frenos", 14), SUSPENSION("Suspensión", 15), OTHER("Otros", 16),
}

enum class WheelSlot(val label: String, val short: String) {
    FL("Rueda delantera izquierda", "FL"), FR("Rueda delantera derecha", "FR"),
    RL("Rueda trasera izquierda", "RL"), RR("Rueda trasera derecha", "RR"),
    ML("Rueda central izquierda", "ML"), MR("Rueda central derecha", "MR"),
}

/** Geometría compartida (puede instanciarse varias veces, p. ej. las 4 ruedas). Coordenadas locales GTA. */
class GeometryData(
    val id: Int,
    val vertexCount: Int,
    val positions: FloatArray,
    val normals: FloatArray,
    /** 2 floats por vértice (origen arriba-izquierda, como RenderWare/D3D/glTF). */
    val uvs: FloatArray,
    val hasOriginalNormals: Boolean,
    val hasOriginalUvs: Boolean,
    val hasVertexColors: Boolean,
)

class MaterialInfo(
    val key: String,
    val name: String,
    val r: Int, val g: Int, val b: Int, val a: Int,
    val textureName: String?,
    val texture: TextureImage?,
    /** 0 = ninguno; 1 = color primario; 2 = color secundario (marcadores de pintura de SA). */
    val paintSlot: Int,
    val hasEnvMap: Boolean,
) {
    val textureMissing: Boolean get() = textureName != null && texture == null
    val transparent: Boolean get() = a < 255 || texture?.hasAlpha == true
}

/** Parte de un mesh con un único material: unidad mínima de selección/edición. */
class SubMesh(
    val id: Int,
    val partId: Int,
    val geometry: GeometryData,
    val indices: IntArray,
    val material: MaterialInfo,
) {
    val triangleCount: Int get() = indices.size / 3
    /** Número de vértices distintos usados. */
    val vertexCount: Int by lazy { indices.toSet().size }
}

class MeshPart(
    val id: Int,
    val name: String,
    val category: PartCategory,
    val wheelSlot: WheelSlot?,
    val frameName: String?,
    /** Transformación mundo en espacio GTA. */
    val transform: FloatArray,
    val subMeshes: List<SubMesh>,
    val defaultVisible: Boolean,
    /** Texto si es una variante (dañado / LOD / no renderizado). */
    val variantNote: String?,
    /** true si el nombre/categoría viene de nombres de frame reconocidos (no adivinado). */
    val recognized: Boolean,
    val extraSource: String? = null,
)

class ModelDiagnostics(
    val dffFileName: String,
    val txdFileName: String?,
    val dffSize: Long,
    val txdSize: Long,
    val dffParser: String,
    val txdParser: String?,
    val rwVersion: String,
    val dffValid: Boolean,
    val txdValid: Boolean,
    val geometryCount: Int,
    val atomicCount: Int,
    val frameCount: Int,
    val materialCount: Int,
    val texturesInTxd: Int,
    val texturesFound: List<String>,
    val texturesMissing: List<String>,
    val textureErrors: List<String>,
    val chunkIssues: List<String>,
    val notes: List<String>,
) {
    fun toText(): String = buildString {
        appendLine("DFF válido: ${if (dffValid) "sí" else "no"}  ($dffFileName, $dffSize bytes)")
        appendLine("TXD válido: ${if (txdFileName == null) "sin TXD" else if (txdValid) "sí  ($txdFileName, $txdSize bytes)" else "no ($txdFileName)"}")
        appendLine("Parser DFF: $dffParser; versión RW $rwVersion")
        if (txdParser != null) appendLine("Parser TXD: $txdParser")
        appendLine("Frames: $frameCount; Atomics: $atomicCount; Geometrías: $geometryCount")
        appendLine("Materiales encontrados: $materialCount")
        appendLine("Texturas en el TXD: $texturesInTxd; resueltas: ${texturesFound.size}${if (texturesFound.isNotEmpty()) " (" + texturesFound.joinToString() + ")" else ""}")
        appendLine("Texturas faltantes: ${if (texturesMissing.isEmpty()) "ninguna" else texturesMissing.joinToString()}")
        if (textureErrors.isNotEmpty()) { appendLine("Errores de textura:"); textureErrors.forEach { appendLine("  - $it") } }
        appendLine("Errores de chunks: ${if (chunkIssues.isEmpty()) "ninguno" else ""}")
        chunkIssues.forEach { appendLine("  - $it") }
        notes.forEach { appendLine("Nota: $it") }
    }
}

class ValidationReport(
    val needsReview: Boolean,
    val reasons: List<String>,
    /** Límites robustos (ignoran piezas muy alejadas), en espacio de visor. null si no hay geometría. */
    val robustBounds: Bounds?,
    /** Factor para llevar la longitud mayor a ~4.5 m (solo se aplica si el usuario lo pide). */
    val suggestedScale: Float,
)

class VehicleModel(
    val name: String,
    val moduleId: String,
    val parts: List<MeshPart>,
    val materials: List<MaterialInfo>,
    val textures: Map<String, TextureImage>,
    val diagnostics: ModelDiagnostics,
    val validation: ValidationReport,
    val warnings: List<String>,
) {
    val subMeshes: List<SubMesh> by lazy { parts.flatMap { it.subMeshes } }
    private val subToPart: Map<Int, MeshPart> by lazy { parts.flatMap { p -> p.subMeshes.map { it.id to p } }.toMap() }
    fun partOf(subMeshId: Int): MeshPart? = subToPart[subMeshId]
    fun part(id: Int): MeshPart? = parts.firstOrNull { it.id == id }
    fun subMesh(id: Int): SubMesh? = subMeshes.firstOrNull { it.id == id }

    val totalVertices: Int by lazy { parts.sumOf { p -> p.subMeshes.map { it.geometry }.distinctBy { it.id }.sumOf { it.vertexCount } } }
    val totalTriangles: Int by lazy { subMeshes.sumOf { it.triangleCount } }

    /** Transformación a espacio de visor (ya con conversión de coordenadas) de una parte. */
    fun viewMatrix(part: MeshPart): FloatArray = Mat4.multiply(Mat4.GTA_TO_VIEW, part.transform)

    private val boundsCache = HashMap<Int, Bounds>()

    /** Caja de una parte en espacio de visor (exacta, desde los vértices usados). */
    @Synchronized
    fun partBounds(part: MeshPart): Bounds = boundsCache.getOrPut(part.id) {
        val b = Bounds.empty()
        val m = viewMatrix(part)
        val tmp = FloatArray(3)
        for (sm in part.subMeshes) {
            val pos = sm.geometry.positions
            val idx = sm.indices
            for (i in idx) {
                Mat4.transformPoint(m, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2], tmp)
                b.expand(tmp[0], tmp[1], tmp[2])
            }
        }
        b
    }

    fun boundsOf(partIds: Collection<Int>): Bounds? {
        val b = Bounds.empty()
        var any = false
        for (p in parts) if (p.id in partIds) { val pb = partBounds(p); if (pb.isValid) { b.expand(pb); any = true } }
        return if (any) b else null
    }

    fun fullBounds(): Bounds? = boundsOf(parts.map { it.id })

    fun materialByKey(key: String): List<SubMesh> = subMeshes.filter { it.material.key == key }
}
