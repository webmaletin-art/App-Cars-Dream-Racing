package com.webmaletin.gtaviewer.dff

/** Frame de la FrameList. [rotation] son 9 floats (right, up, at); [position] 3 floats. */
class DffFrame(
    val index: Int,
    val name: String?,
    val rotation: FloatArray,
    val position: FloatArray,
    val parent: Int,
    val hanimBoneId: Int?,
)

class DffTextureRef(val name: String, val maskName: String)

class DffMaterial(
    val index: Int,
    val r: Int, val g: Int, val b: Int, val a: Int,
    val texture: DffTextureRef?,
    val ambient: Float, val diffuse: Float, val specular: Float,
    val hasEnvMap: Boolean,
    val hasSpecular: Boolean,
)

class BinMeshGroup(val materialIndex: Int, val indices: IntArray)

class DffGeometry(
    val index: Int,
    val flags: Int,
    val isNative: Boolean,
    val isTriStrip: Boolean,
    val vertexCount: Int,
    val positions: FloatArray,
    val normals: FloatArray?,
    val uvSets: List<FloatArray>,
    val colors: ByteArray?,
    /** 3 índices por triángulo (orden v1,v2,v3 del stream RW). */
    val triangles: IntArray,
    val triangleMaterials: IntArray,
    val binMesh: List<BinMeshGroup>?,
    val binMeshIsStrip: Boolean,
    val materials: List<DffMaterial>,
    val hasSkin: Boolean,
    val skinBones: Int,
    val boundingSphere: FloatArray?,
)

class DffAtomic(val index: Int, val frameIndex: Int, val geometryIndex: Int, val flags: Int) {
    val isRendered: Boolean get() = flags and 0x04 != 0
}

class DffDocument(
    val rwVersion: Int,
    val frames: List<DffFrame>,
    val geometries: List<DffGeometry?>,
    val atomics: List<DffAtomic>,
    val fileSize: Int,
    /** Problemas de estructura encontrados (chunks truncados, geometrías ilegibles...). */
    val issues: List<String>,
    val parserName: String,
)

/** Resumen ligero (solo cabecera) usado por el escáner para clasificar sin cargar la geometría. */
class DffSummary(
    val valid: Boolean,
    val rwVersion: Int,
    val frameNames: List<String>,
    val frameCount: Int,
    val geometryCount: Int,
    val issues: List<String>,
)

interface DffParser {
    val name: String
    fun parse(data: ByteArray): DffDocument
    fun summarize(prefix: ByteArray): DffSummary
}
