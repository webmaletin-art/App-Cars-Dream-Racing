package com.webmaletin.gtaviewer.image

import com.webmaletin.gtaviewer.txd.TextureImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.abs

/** Decodificador PNG sin dependencias (no entrelazado, 8 bits: gris, gris+alfa, RGB, RGBA, paleta). Para JPG se usa el decodificador del sistema. */
object PngDecoder {
    fun isPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    fun decode(b: ByteArray, name: String = "image"): TextureImage {
        require(isPng(b)) { "No es un PNG" }
        var pos = 8
        var w = 0; var h = 0; var depth = 0; var ctype = 0; var interlace = 0
        val idat = ByteArrayOutputStream(); var plte: ByteArray? = null; var trns: ByteArray? = null
        while (pos + 8 <= b.size) {
            val len = ((b[pos].toInt() and 0xFF) shl 24) or ((b[pos + 1].toInt() and 0xFF) shl 16) or ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            val type = String(b, pos + 4, 4, Charsets.US_ASCII)
            val d = pos + 8
            when (type) {
                "IHDR" -> { w = i32(b, d); h = i32(b, d + 4); depth = b[d + 8].toInt(); ctype = b[d + 9].toInt(); interlace = b[d + 12].toInt() }
                "PLTE" -> plte = b.copyOfRange(d, d + len)
                "tRNS" -> trns = b.copyOfRange(d, d + len)
                "IDAT" -> idat.write(b, d, len)
                "IEND" -> pos = b.size
            }
            pos = d + len + 4
        }
        require(depth == 8 && interlace == 0) { "PNG no soportado (profundidad $depth, entrelazado $interlace)" }
        val ch = when (ctype) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> throw IllegalArgumentException("Tipo de color PNG $ctype no soportado") }
        val raw = InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).use { it.readBytes() }
        val stride = w * ch
        val img = ByteArray(h * stride)
        var prev = ByteArray(stride)
        for (y in 0 until h) {
            val f = raw[y * (stride + 1)].toInt()
            val line = raw.copyOfRange(y * (stride + 1) + 1, (y + 1) * (stride + 1))
            for (x in 0 until stride) {
                val a = if (x >= ch) line[x - ch].toInt() and 0xFF else 0
                val up = prev[x].toInt() and 0xFF
                val c = if (x >= ch) prev[x - ch].toInt() and 0xFF else 0
                val v = line[x].toInt() and 0xFF
                line[x] = (when (f) {
                    0 -> v; 1 -> v + a; 2 -> v + up; 3 -> v + (a + up) / 2
                    4 -> { val p = a + up - c; val pa = abs(p - a); val pb = abs(p - up); val pc = abs(p - c); v + (if (pa <= pb && pa <= pc) a else if (pb <= pc) up else c) }
                    else -> throw IllegalArgumentException("Filtro PNG $f")
                } and 0xFF).toByte()
            }
            System.arraycopy(line, 0, img, y * stride, stride); prev = line
        }
        val rgba = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            when (ctype) {
                0 -> { val g = img[i]; rgba[i * 4] = g; rgba[i * 4 + 1] = g; rgba[i * 4 + 2] = g; rgba[i * 4 + 3] = 255.toByte() }
                2 -> { rgba[i * 4] = img[i * 3]; rgba[i * 4 + 1] = img[i * 3 + 1]; rgba[i * 4 + 2] = img[i * 3 + 2]; rgba[i * 4 + 3] = 255.toByte() }
                3 -> { val idx = img[i].toInt() and 0xFF; val p = plte!!; rgba[i * 4] = p[idx * 3]; rgba[i * 4 + 1] = p[idx * 3 + 1]; rgba[i * 4 + 2] = p[idx * 3 + 2]; rgba[i * 4 + 3] = (trns?.getOrNull(idx) ?: 255.toByte()) }
                4 -> { val g = img[i * 2]; rgba[i * 4] = g; rgba[i * 4 + 1] = g; rgba[i * 4 + 2] = g; rgba[i * 4 + 3] = img[i * 2 + 1] }
                6 -> { for (k in 0..3) rgba[i * 4 + k] = img[i * 4 + k] }
            }
        }
        return TextureImage(name, w, h, rgba, com.webmaletin.gtaviewer.txd.TextureDecoder.hasTransparency(rgba), "PNG")
    }

    private fun i32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}
