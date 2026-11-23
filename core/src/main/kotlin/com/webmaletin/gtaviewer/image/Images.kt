package com.webmaletin.gtaviewer.image

import com.webmaletin.gtaviewer.txd.TextureImage

/** Decodificación de imágenes por extensión. JPG lo aporta la plataforma (Android: BitmapFactory; tests JVM: ImageIO). */
object Images {
    @Volatile var jpegDecoder: ((ByteArray, String) -> TextureImage?)? = null

    /** Reduce por promedio 2×2 hasta que ningún lado supere [maxSize]. */
    fun downscale(t: TextureImage, maxSize: Int): TextureImage {
        var w = t.width; var h = t.height; var d = t.rgba
        if (maxOf(w, h) <= maxSize) return t
        while (maxOf(w, h) > maxSize && w > 1 && h > 1) {
            val nw = maxOf(1, w / 2); val nh = maxOf(1, h / 2); val o = ByteArray(nw * nh * 4)
            for (y in 0 until nh) for (x in 0 until nw) for (c in 0..3) {
                var s = 0
                for (dy in 0..1) for (dx in 0..1) s += d[(minOf(h - 1, y * 2 + dy) * w + minOf(w - 1, x * 2 + dx)) * 4 + c].toInt() and 0xFF
                o[(y * nw + x) * 4 + c] = (s / 4).toByte()
            }
            d = o; w = nw; h = nh
        }
        return TextureImage(t.name, w, h, d, t.hasAlpha, t.sourceFormat + " ↓")
    }

    /** @return null si el formato no se puede decodificar aquí (el llamador avisa y usa el color del material). */
    fun decode(bytes: ByteArray, fileName: String, maxSize: Int): TextureImage? {
        val name = fileName.substringAfterLast('/').substringBeforeLast('.')
        return try {
            when {
                DdsDecoder.isDds(bytes) -> DdsDecoder.decode(bytes, name, maxSize)
                PngDecoder.isPng(bytes) -> downscale(PngDecoder.decode(bytes, name), maxSize)
                bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> jpegDecoder?.invoke(bytes, name)?.let { downscale(it, maxSize) }
                else -> null
            }
        } catch (e: Exception) { null }
    }
}
