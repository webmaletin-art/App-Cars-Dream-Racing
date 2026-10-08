package com.webmaletin.gtaviewer.txd

import com.webmaletin.gtaviewer.renderware.*

/**
 * Lee TXD de GTA SA / VC / III (plataformas D3D8 y D3D9). Plataformas de consola (PS2/Xbox) se informan como
 * no soportadas en lugar de producir una imagen incorrecta. Solo se decodifica el mipmap base.
 */
class GtaSaTxdParser : TxdParser {
    override val name = "GTA_SA_TXD_Parser (RenderWare 3.x, D3D8/D3D9)"

    override fun parse(data: ByteArray): TxdDocument {
        val tree = RenderWareParser.parse(data)
        val issues = ArrayList<String>(tree.issues)
        val dict = tree.roots.firstOrNull { it.type == RwId.TEXTURE_DICTIONARY }
            ?: throw RwException(
                tree.roots.firstOrNull()?.let { "No es un TXD: el primer chunk es ${RwId.name(it.type)}" }
                    ?: "Archivo vacío o sin cabecera RenderWare",
            )
        val declared = dict.child(RwId.STRUCT)?.let { runCatching { tree.stream(it).u16() }.getOrNull() }
        val natives = dict.childrenOf(RwId.TEXTURE_NATIVE)
        if (declared != null && declared != natives.size) issues += "TXD declara $declared texturas, se encontraron ${natives.size}"
        val textures = natives.mapIndexed { i, n ->
            try {
                readTexture(tree, n)
            } catch (e: Exception) {
                issues += "Textura $i ilegible: ${e.message}"
                TxdTexture("texture_$i", "", 0, 0, "?", null, e.message ?: "error")
            }
        }
        return TxdDocument(dict.version, textures, issues, data.size, name)
    }

    private fun readTexture(tree: RwParseResult, node: RwNode): TxdTexture {
        val st = node.child(RwId.STRUCT) ?: throw RwException("TextureNative sin Struct")
        val s = tree.stream(st)
        val platform = s.i32()
        s.i32() // filtro / direccionamiento
        val name = s.cString(32)
        val mask = s.cString(32)

        if (platform != 8 && platform != 9) {
            val label = when (platform) {
                0x00325350 -> "PS2"
                5 -> "Xbox"
                else -> "plataforma 0x" + Integer.toHexString(platform)
            }
            return TxdTexture(name, mask, 0, 0, label, null, "Plataforma no soportada ($label); solo D3D8/D3D9 (PC)")
        }

        val rasterFormat = s.i32()
        var d3dFormat = 0
        var hasAlphaFlag = false
        var compression = 0
        if (platform == 9) d3dFormat = s.i32() else hasAlphaFlag = s.i32() != 0
        val width = s.u16(); val height = s.u16()
        val depth = s.u8(); val levels = s.u8()
        s.u8() // raster type
        val flags = s.u8()
        if (platform == 8) compression = flags else hasAlphaFlag = flags and 1 != 0

        if (width <= 0 || height <= 0 || width > 8192 || height > 8192)
            return TxdTexture(name, mask, width, height, "?", null, "Dimensiones inválidas ${width}x$height")
        if (flags and 2 != 0 && platform == 9) return TxdTexture(name, mask, width, height, "cubemap", null, "Cubemap no soportado")

        val fmt = (rasterFormat shr 8) and 0xF
        val pal8 = rasterFormat and 0x2000 != 0
        val pal4 = rasterFormat and 0x4000 != 0

        // Clasificar el formato
        val dxt = when {
            platform == 9 && d3dFormat == FOURCC_DXT1 -> 1
            platform == 9 && (d3dFormat == FOURCC_DXT3 || d3dFormat == FOURCC_DXT2) -> 3
            platform == 9 && (d3dFormat == FOURCC_DXT5 || d3dFormat == FOURCC_DXT4) -> 5
            platform == 8 && compression == 1 -> 1
            platform == 8 && (compression == 2 || compression == 3) -> 3
            platform == 8 && (compression == 4 || compression == 5) -> 5
            else -> 0
        }

        var palOff = -1; var palCount = 0
        if (pal8 || pal4) {
            palCount = if (pal8) 256 else 32
            if (s.remaining() < palCount * 4) throw RwException("Paleta truncada")
            palOff = s.pos; s.skip(palCount * 4)
        }
        if (levels == 0) throw RwException("Textura sin niveles de mipmap")
        val dataSize = s.u32().toInt()
        if (dataSize < 0 || dataSize > s.remaining()) throw RwException("Datos del mipmap base truncados ($dataSize > ${s.remaining()})")
        val off = s.pos

        val formatLabel: String
        val rgba: ByteArray = when {
            dxt != 0 -> { formatLabel = "DXT$dxt"; TextureDecoder.decodeDxt(tree.data, off, width, height, dxt) }
            pal8 || pal4 -> { formatLabel = if (pal8) "PAL8" else "PAL4"; TextureDecoder.decodePaletted(tree.data, palOff, palCount, off, width, height, pal4 && !pal8) }
            platform == 9 && d3dFormat == D3DFMT_A8B8G8R8 -> { formatLabel = "RGBA8888"; TextureDecoder.decode32(tree.data, off, width, height, bgra = false, useAlpha = true) }
            depth == 32 || (platform == 9 && (d3dFormat == D3DFMT_A8R8G8B8 || d3dFormat == D3DFMT_X8R8G8B8)) -> {
                val alpha = fmt == 5 || (platform == 9 && d3dFormat == D3DFMT_A8R8G8B8) || hasAlphaFlag
                formatLabel = if (alpha) "BGRA8888" else "BGRX8888"
                TextureDecoder.decode32(tree.data, off, width, height, bgra = true, useAlpha = alpha)
            }
            depth == 24 || fmt == 6 || fmt == 8 -> { formatLabel = "BGR888"; TextureDecoder.decode24(tree.data, off, width, height) }
            depth == 16 || fmt == 1 || fmt == 2 || fmt == 3 || fmt == 10 -> {
                val f = if (platform == 9 && d3dFormat == D3DFMT_R5G6B5) 2 else if (platform == 9 && d3dFormat == D3DFMT_A4R4G4B4) 3
                else if (platform == 9 && d3dFormat == D3DFMT_A1R5G5B5) 1 else if (fmt in listOf(1, 2, 3, 10)) fmt else 1
                formatLabel = when (f) { 2 -> "RGB565"; 3 -> "RGBA4444"; 10 -> "RGB555"; else -> "RGBA1555" }
                TextureDecoder.decode16(tree.data, off, width, height, f)
            }
            depth == 8 || fmt == 4 -> { formatLabel = "LUM8"; TextureDecoder.decodeLum8(tree.data, off, width, height) }
            else -> return TxdTexture(name, mask, width, height, "raster=0x" + Integer.toHexString(rasterFormat), null, "Formato de raster no reconocido (depth=$depth, d3d=0x${Integer.toHexString(d3dFormat)})")
        }
        val alpha = TextureDecoder.hasTransparency(rgba)
        return TxdTexture(name, mask, width, height, formatLabel, TextureImage(name, width, height, rgba, alpha, formatLabel), null)
    }

    companion object {
        const val FOURCC_DXT1 = 0x31545844
        const val FOURCC_DXT2 = 0x32545844
        const val FOURCC_DXT3 = 0x33545844
        const val FOURCC_DXT4 = 0x34545844
        const val FOURCC_DXT5 = 0x35545844
        const val D3DFMT_A8R8G8B8 = 21
        const val D3DFMT_X8R8G8B8 = 22
        const val D3DFMT_R5G6B5 = 23
        const val D3DFMT_A1R5G5B5 = 25
        const val D3DFMT_A4R4G4B4 = 26
        const val D3DFMT_A8B8G8R8 = 32
    }
}
