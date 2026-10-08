package com.webmaletin.gtaviewer.txd

/** Textura decodificada a RGBA8888 (4 bytes por píxel, filas de arriba a abajo). */
class TextureImage(
    val name: String,
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
    val hasAlpha: Boolean,
    val sourceFormat: String,
)

class TxdTexture(
    val name: String,
    val maskName: String,
    val width: Int,
    val height: Int,
    val sourceFormat: String,
    /** null si no se pudo decodificar; ver [error]. */
    val image: TextureImage?,
    val error: String?,
)

class TxdDocument(
    val rwVersion: Int,
    val textures: List<TxdTexture>,
    val issues: List<String>,
    val fileSize: Int,
    val parserName: String,
) {
    fun find(name: String): TxdTexture? = textures.firstOrNull { norm(it.name) == norm(name) }

    companion object {
        fun norm(n: String): String = n.trim().lowercase().substringBeforeLast('.', n.trim().lowercase()).let {
            // solo se recorta la extensión si es de imagen conocida
            val lower = n.trim().lowercase()
            if (lower.endsWith(".png") || lower.endsWith(".dds") || lower.endsWith(".tga") || lower.endsWith(".bmp")) it else lower
        }
    }
}

interface TxdParser {
    val name: String
    fun parse(data: ByteArray): TxdDocument
}
