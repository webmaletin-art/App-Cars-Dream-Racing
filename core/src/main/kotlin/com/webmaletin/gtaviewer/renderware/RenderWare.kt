package com.webmaletin.gtaviewer.renderware

/** Identificadores de chunk RenderWare (RW 3.x) usados por GTA III / VC / SA. */
object RwId {
    const val STRUCT = 0x01
    const val STRING = 0x02
    const val EXTENSION = 0x03
    const val TEXTURE = 0x06
    const val MATERIAL = 0x07
    const val MATERIAL_LIST = 0x08
    const val FRAME_LIST = 0x0E
    const val GEOMETRY = 0x0F
    const val CLUMP = 0x10
    const val ATOMIC = 0x14
    const val TEXTURE_NATIVE = 0x15
    const val TEXTURE_DICTIONARY = 0x16
    const val GEOMETRY_LIST = 0x1A

    const val SKIN_PLG = 0x116
    const val HANIM_PLG = 0x11E
    const val MATFX = 0x120
    const val BIN_MESH_PLG = 0x50E
    const val SPECULAR_MATERIAL = 0x253F2F6
    const val EFFECT_2D = 0x253F2F8
    const val REFLECTION_MATERIAL = 0x253F2FC
    const val BREAKABLE = 0x253F2FD
    const val FRAME_NAME = 0x253F2FE

    /** Chunks cuyo contenido son otros chunks. */
    val CONTAINERS = setOf(
        EXTENSION, TEXTURE, MATERIAL, MATERIAL_LIST, FRAME_LIST, GEOMETRY, CLUMP,
        ATOMIC, TEXTURE_NATIVE, TEXTURE_DICTIONARY, GEOMETRY_LIST,
    )

    fun name(type: Int): String = when (type) {
        STRUCT -> "Struct"; STRING -> "String"; EXTENSION -> "Extension"; TEXTURE -> "Texture"
        MATERIAL -> "Material"; MATERIAL_LIST -> "MaterialList"; FRAME_LIST -> "FrameList"
        GEOMETRY -> "Geometry"; CLUMP -> "Clump"; ATOMIC -> "Atomic"; TEXTURE_NATIVE -> "TextureNative"
        TEXTURE_DICTIONARY -> "TextureDictionary"; GEOMETRY_LIST -> "GeometryList"
        SKIN_PLG -> "Skin"; HANIM_PLG -> "HAnim"; MATFX -> "MaterialEffects"; BIN_MESH_PLG -> "BinMesh"
        SPECULAR_MATERIAL -> "SpecularMaterial"; EFFECT_2D -> "2dfx"; REFLECTION_MATERIAL -> "ReflectionMaterial"
        BREAKABLE -> "Breakable"; FRAME_NAME -> "FrameName"
        else -> "0x" + type.toString(16).uppercase()
    }
}

class RwException(message: String) : Exception(message)

/** Decodifica el campo "libraryID" de la cabecera a una versión RW tipo 0x34003. */
object RwVersion {
    fun decode(raw: Int): Int =
        if (raw and 0xFFFF0000.toInt() != 0) {
            ((raw shr 14) and 0x3FF00) or ((raw shr 16) and 0x3F) or 0x30000
        } else {
            raw shl 8
        }

    fun format(version: Int): String =
        "${(version shr 16) and 0xF}.${(version shr 12) and 0xF}.${(version shr 8) and 0xF}.${version and 0xFF}"
}

/** Lector little-endian con comprobación de límites. */
class RwStream(val data: ByteArray, var pos: Int = 0, val limit: Int = data.size) {
    private fun need(n: Int) {
        if (n < 0 || pos + n > limit) throw RwException("Lectura fuera de rango en offset $pos (+$n, límite $limit)")
    }

    fun remaining() = limit - pos
    fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }
    fun u16(): Int { need(2); val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8); pos += 2; return v }
    fun i32(): Int {
        need(4)
        val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16) or ((data[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }
    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())
    fun skip(n: Int) { need(n); pos += n }
    fun bytes(n: Int): ByteArray { need(n); val r = data.copyOfRange(pos, pos + n); pos += n; return r }
    fun cString(maxLen: Int): String {
        need(maxLen)
        var end = pos
        while (end < pos + maxLen && data[end].toInt() != 0) end++
        val s = String(data, pos, end - pos, Charsets.ISO_8859_1)
        pos += maxLen
        return s
    }
}

/** Nodo del árbol de chunks. [offset] apunta al inicio de los datos (tras la cabecera de 12 bytes). */
class RwNode(
    val type: Int,
    val size: Int,
    val rawVersion: Int,
    val offset: Int,
    val children: List<RwNode>,
) {
    val version: Int get() = RwVersion.decode(rawVersion)
    val end: Int get() = offset + size
    fun child(type: Int): RwNode? = children.firstOrNull { it.type == type }
    fun childrenOf(type: Int): List<RwNode> = children.filter { it.type == type }
    override fun toString() = "${RwId.name(type)}[size=$size @ $offset]"
}

class RwParseResult(val data: ByteArray, val roots: List<RwNode>, val issues: List<String>) {
    fun stream(node: RwNode) = RwStream(data, node.offset, minOf(node.end, data.size))
}

/**
 * Capa genérica de lectura de RenderWare Binary Stream: construye el árbol de chunks de forma
 * tolerante (chunks truncados o desconocidos no abortan la lectura; se registran como incidencias).
 */
object RenderWareParser {
    private const val HEADER = 12

    /**
     * @param skipChildrenOf tipos de contenedor cuyos hijos no se analizan (modo resumen rápido).
     */
    fun parse(data: ByteArray, skipChildrenOf: Set<Int> = emptySet()): RwParseResult {
        val issues = ArrayList<String>()
        val roots = parseRange(data, 0, data.size, 0, skipChildrenOf, issues)
        return RwParseResult(data, roots, issues)
    }

    private fun parseRange(
        data: ByteArray, start: Int, end: Int, depth: Int,
        skip: Set<Int>, issues: MutableList<String>,
    ): List<RwNode> {
        val out = ArrayList<RwNode>()
        var pos = start
        while (pos + HEADER <= end) {
            val s = RwStream(data, pos, end)
            val type = s.i32()
            var size = s.i32()
            val rawVersion = s.i32()
            val dataStart = pos + HEADER
            if (size < 0 || dataStart + size.toLong() > end) {
                issues += "Chunk ${RwId.name(type)} en $pos declara tamaño $size pero solo quedan ${end - dataStart} bytes (truncado)"
                size = (end - dataStart).coerceAtLeast(0)
            }
            if (depth > 24) { issues += "Anidamiento excesivo en $pos"; break }
            val children = if (type in RwId.CONTAINERS && type !in skip) {
                try {
                    parseRange(data, dataStart, dataStart + size, depth + 1, skip, issues)
                } catch (e: Exception) {
                    issues += "Error leyendo hijos de ${RwId.name(type)} en $pos: ${e.message}"
                    emptyList()
                }
            } else emptyList()
            out += RwNode(type, size, rawVersion, dataStart, children)
            pos = dataStart + size
        }
        return out
    }
}
