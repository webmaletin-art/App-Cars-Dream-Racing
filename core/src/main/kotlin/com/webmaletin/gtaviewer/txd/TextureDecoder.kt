package com.webmaletin.gtaviewer.txd

/** Decodificadores de formatos de raster (DXT1/3/5, 8888, 888, 565, 1555, 4444, LUM8, paletas) a RGBA8888. */
object TextureDecoder {

    // ------------------------------------------------------------- DXT

    private fun c565(c: Int, out: IntArray, o: Int) {
        val r = (c shr 11) and 31; val g = (c shr 5) and 63; val b = c and 31
        out[o] = (r shl 3) or (r shr 2)
        out[o + 1] = (g shl 2) or (g shr 4)
        out[o + 2] = (b shl 3) or (b shr 2)
    }

    private fun colorBlock(src: ByteArray, off: Int, dxt1: Boolean): IntArray {
        val c0 = (src[off].toInt() and 0xFF) or ((src[off + 1].toInt() and 0xFF) shl 8)
        val c1 = (src[off + 2].toInt() and 0xFF) or ((src[off + 3].toInt() and 0xFF) shl 8)
        val pal = IntArray(16) // 4 colores * RGBA
        c565(c0, pal, 0); pal[3] = 255
        c565(c1, pal, 4); pal[7] = 255
        if (c0 > c1 || !dxt1) {
            for (i in 0..2) {
                pal[8 + i] = (2 * pal[i] + pal[4 + i]) / 3
                pal[12 + i] = (pal[i] + 2 * pal[4 + i]) / 3
            }
            pal[11] = 255; pal[15] = 255
        } else {
            for (i in 0..2) pal[8 + i] = (pal[i] + pal[4 + i]) / 2
            pal[11] = 255
            pal[12] = 0; pal[13] = 0; pal[14] = 0; pal[15] = 0
        }
        return pal
    }

    fun decodeDxt(src: ByteArray, srcOff: Int, w: Int, h: Int, kind: Int): ByteArray {
        // kind: 1 = DXT1, 3 = DXT3, 5 = DXT5
        val out = ByteArray(w * h * 4)
        val bw = (w + 3) / 4; val bh = (h + 3) / 4
        val blockSize = if (kind == 1) 8 else 16
        if (srcOff + bw.toLong() * bh * blockSize > src.size) throw IllegalArgumentException("Datos DXT insuficientes")
        var p = srcOff
        val alpha = IntArray(16)
        for (by in 0 until bh) for (bx in 0 until bw) {
            val colorOff: Int
            when (kind) {
                1 -> { colorOff = p; java.util.Arrays.fill(alpha, 255) }
                3 -> {
                    for (i in 0 until 8) {
                        val v = src[p + i].toInt() and 0xFF
                        alpha[i * 2] = (v and 15) * 17
                        alpha[i * 2 + 1] = (v shr 4) * 17
                    }
                    colorOff = p + 8
                }
                else -> {
                    val a0 = src[p].toInt() and 0xFF; val a1 = src[p + 1].toInt() and 0xFF
                    val tbl = IntArray(8)
                    tbl[0] = a0; tbl[1] = a1
                    if (a0 > a1) for (i in 1..6) tbl[i + 1] = ((7 - i) * a0 + i * a1) / 7
                    else { for (i in 1..4) tbl[i + 1] = ((5 - i) * a0 + i * a1) / 5; tbl[6] = 0; tbl[7] = 255 }
                    var bits = 0L
                    for (i in 0 until 6) bits = bits or ((src[p + 2 + i].toLong() and 0xFF) shl (8 * i))
                    for (i in 0 until 16) alpha[i] = tbl[((bits shr (3 * i)) and 7).toInt()]
                    colorOff = p + 8
                }
            }
            val pal = colorBlock(src, colorOff, kind == 1)
            val idxBits = (src[colorOff + 4].toInt() and 0xFF) or ((src[colorOff + 5].toInt() and 0xFF) shl 8) or
                ((src[colorOff + 6].toInt() and 0xFF) shl 16) or ((src[colorOff + 7].toInt() and 0xFF) shl 24)
            for (py in 0 until 4) for (px in 0 until 4) {
                val x = bx * 4 + px; val y = by * 4 + py
                if (x >= w || y >= h) continue
                val ci = (idxBits ushr (2 * (py * 4 + px))) and 3
                val o = (y * w + x) * 4
                out[o] = pal[ci * 4].toByte()
                out[o + 1] = pal[ci * 4 + 1].toByte()
                out[o + 2] = pal[ci * 4 + 2].toByte()
                out[o + 3] = (if (kind == 1) pal[ci * 4 + 3] else alpha[py * 4 + px]).toByte()
            }
            p += blockSize
        }
        return out
    }

    // ------------------------------------------------------------- píxeles sin comprimir

    fun decode32(src: ByteArray, off: Int, w: Int, h: Int, bgra: Boolean, useAlpha: Boolean): ByteArray {
        val n = w * h
        if (off + n.toLong() * 4 > src.size) throw IllegalArgumentException("Datos 32 bpp insuficientes")
        val out = ByteArray(n * 4)
        for (i in 0 until n) {
            val s = off + i * 4
            if (bgra) { out[i * 4] = src[s + 2]; out[i * 4 + 1] = src[s + 1]; out[i * 4 + 2] = src[s] }
            else { out[i * 4] = src[s]; out[i * 4 + 1] = src[s + 1]; out[i * 4 + 2] = src[s + 2] }
            out[i * 4 + 3] = if (useAlpha) src[s + 3] else 255.toByte()
        }
        return out
    }

    fun decode24(src: ByteArray, off: Int, w: Int, h: Int): ByteArray {
        val n = w * h
        if (off + n.toLong() * 3 > src.size) throw IllegalArgumentException("Datos 24 bpp insuficientes")
        val out = ByteArray(n * 4)
        for (i in 0 until n) {
            val s = off + i * 3
            out[i * 4] = src[s + 2]; out[i * 4 + 1] = src[s + 1]; out[i * 4 + 2] = src[s]; out[i * 4 + 3] = 255.toByte()
        }
        return out
    }

    /** @param fmt 1=1555, 2=565, 3=4444, 10=555 */
    fun decode16(src: ByteArray, off: Int, w: Int, h: Int, fmt: Int): ByteArray {
        val n = w * h
        if (off + n.toLong() * 2 > src.size) throw IllegalArgumentException("Datos 16 bpp insuficientes")
        val out = ByteArray(n * 4)
        for (i in 0 until n) {
            val v = (src[off + i * 2].toInt() and 0xFF) or ((src[off + i * 2 + 1].toInt() and 0xFF) shl 8)
            var r: Int; var g: Int; var b: Int; var a = 255
            when (fmt) {
                2 -> { r = ((v shr 11) and 31) * 255 / 31; g = ((v shr 5) and 63) * 255 / 63; b = (v and 31) * 255 / 31 }
                3 -> { a = ((v shr 12) and 15) * 17; r = ((v shr 8) and 15) * 17; g = ((v shr 4) and 15) * 17; b = (v and 15) * 17 }
                10 -> { r = ((v shr 10) and 31) * 255 / 31; g = ((v shr 5) and 31) * 255 / 31; b = (v and 31) * 255 / 31 }
                else -> { a = if (v and 0x8000 != 0) 255 else 0; r = ((v shr 10) and 31) * 255 / 31; g = ((v shr 5) and 31) * 255 / 31; b = (v and 31) * 255 / 31 }
            }
            out[i * 4] = r.toByte(); out[i * 4 + 1] = g.toByte(); out[i * 4 + 2] = b.toByte(); out[i * 4 + 3] = a.toByte()
        }
        return out
    }

    fun decodeLum8(src: ByteArray, off: Int, w: Int, h: Int): ByteArray {
        val n = w * h
        if (off + n > src.size) throw IllegalArgumentException("Datos LUM8 insuficientes")
        val out = ByteArray(n * 4)
        for (i in 0 until n) { val l = src[off + i]; out[i * 4] = l; out[i * 4 + 1] = l; out[i * 4 + 2] = l; out[i * 4 + 3] = 255.toByte() }
        return out
    }

    /** Paleta RGBA (4 bytes por entrada) + índices de 8 o 4 bits. */
    fun decodePaletted(src: ByteArray, palOff: Int, palCount: Int, idxOff: Int, w: Int, h: Int, fourBit: Boolean): ByteArray {
        val n = w * h
        val need = if (fourBit) (n + 1) / 2 else n
        if (idxOff + need > src.size) throw IllegalArgumentException("Índices de paleta insuficientes")
        val out = ByteArray(n * 4)
        for (i in 0 until n) {
            val idx = if (fourBit) {
                val b = src[idxOff + i / 2].toInt() and 0xFF
                if (i % 2 == 0) b and 15 else b shr 4
            } else src[idxOff + i].toInt() and 0xFF
            val pi = minOf(idx, palCount - 1)
            out[i * 4] = src[palOff + pi * 4]; out[i * 4 + 1] = src[palOff + pi * 4 + 1]
            out[i * 4 + 2] = src[palOff + pi * 4 + 2]; out[i * 4 + 3] = src[palOff + pi * 4 + 3]
        }
        return out
    }

    fun hasTransparency(rgba: ByteArray): Boolean {
        var i = 3
        while (i < rgba.size) { if (rgba[i] != 255.toByte()) return true; i += 4 }
        return false
    }
}
