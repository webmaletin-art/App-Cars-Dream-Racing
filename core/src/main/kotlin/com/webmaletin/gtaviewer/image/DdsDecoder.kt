package com.webmaletin.gtaviewer.image

import com.webmaletin.gtaviewer.txd.TextureDecoder
import com.webmaletin.gtaviewer.txd.TextureImage

/**
 * Decodificador DDS: DXT1/3/5, BC4, BC5, BC7 (DX10), y RGBA/BGRA de 32 bits. Decodifica directamente el nivel de mipmap más
 * pequeño que aún cumpla [maxSize], así una textura de 4096² no se descomprime entera cuando basta con 1024².
 * El decodificador BC7 es un port de la lógica de referencia de Pillow (BcnDecode.c, licencia MIT-CMU) y de la especificación BPTC.
 */
object DdsDecoder {
    fun isDds(b: ByteArray) = b.size > 128 && b[0] == 'D'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == 'S'.code.toByte() && b[3] == ' '.code.toByte()

    private fun i32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private enum class Fmt(val block: Int) { BC1(8), BC2(16), BC3(16), BC4(8), BC5(16), BC7(16), RGBA32(0) }

    class Info(val width: Int, val height: Int, val mips: Int, val format: String)

    fun info(b: ByteArray): Info {
        require(isDds(b)) { "No es un DDS" }
        val (f, _) = format(b)
        return Info(i32(b, 16), i32(b, 12), maxOf(1, i32(b, 28)), f.name)
    }

    private class Layout(val rMask: Int, val gMask: Int, val bMask: Int, val aMask: Int, val bits: Int)

    private fun format(b: ByteArray): Pair<Fmt, Layout?> {
        val flags = i32(b, 80); val fcc = String(b, 84, 4, Charsets.ISO_8859_1)
        if (flags and 4 != 0) { // FOURCC
            return when (fcc) {
                "DXT1" -> Fmt.BC1 to null
                "DXT2", "DXT3" -> Fmt.BC2 to null
                "DXT4", "DXT5" -> Fmt.BC3 to null
                "BC4U", "ATI1", "BC4S" -> Fmt.BC4 to null
                "BC5U", "ATI2", "BC5S" -> Fmt.BC5 to null
                "DX10" -> when (val dxgi = i32(b, 128)) {
                    70, 71, 72 -> Fmt.BC1 to null
                    73, 74, 75 -> Fmt.BC2 to null
                    76, 77, 78 -> Fmt.BC3 to null
                    79, 80, 81 -> Fmt.BC4 to null
                    82, 83, 84 -> Fmt.BC5 to null
                    97, 98, 99 -> Fmt.BC7 to null
                    28, 29 -> Fmt.RGBA32 to Layout(0xFF, 0xFF00, 0xFF0000, -16777216, 32)
                    87, 91 -> Fmt.RGBA32 to Layout(0xFF0000, 0xFF00, 0xFF, -16777216, 32)
                    else -> throw IllegalArgumentException("DDS DX10: formato DXGI $dxgi no soportado")
                }
                else -> throw IllegalArgumentException("DDS: FourCC '$fcc' no soportado")
            }
        }
        val bits = i32(b, 88)
        if (bits == 32 || bits == 24) return Fmt.RGBA32 to Layout(i32(b, 92), i32(b, 96), i32(b, 100), if (flags and 1 != 0) i32(b, 104) else 0, bits)
        throw IllegalArgumentException("DDS sin comprimir de $bits bpp no soportado")
    }

    fun decode(b: ByteArray, name: String, maxSize: Int = 2048): TextureImage {
        require(isDds(b)) { "No es un DDS" }
        val h0 = i32(b, 12); val w0 = i32(b, 16); val mips = maxOf(1, i32(b, 28))
        require(w0 in 1..16384 && h0 in 1..16384) { "Tamaño DDS inválido ${w0}x$h0" }
        val (fmt, lay) = format(b)
        var off = 128 + (if (String(b, 84, 4, Charsets.ISO_8859_1) == "DX10") 20 else 0)
        fun levelSize(w: Int, h: Int): Long = if (fmt == Fmt.RGBA32) w.toLong() * h * (lay!!.bits / 8) else maxOf(1, (w + 3) / 4).toLong() * maxOf(1, (h + 3) / 4) * fmt.block
        var w = w0; var h = h0; var level = 0
        while (maxOf(w, h) > maxSize && level < mips - 1) {
            off += levelSize(w, h).toInt(); w = maxOf(1, w / 2); h = maxOf(1, h / 2); level++
        }
        if (off + levelSize(w, h) > b.size) throw IllegalArgumentException("DDS truncado")
        val rgba: ByteArray = when (fmt) {
            Fmt.BC1 -> TextureDecoder.decodeDxt(b, off, w, h, 1)
            Fmt.BC2 -> TextureDecoder.decodeDxt(b, off, w, h, 3)
            Fmt.BC3 -> TextureDecoder.decodeDxt(b, off, w, h, 5)
            Fmt.BC4 -> decodeBc4(b, off, w, h)
            Fmt.BC5 -> decodeBc5(b, off, w, h)
            Fmt.BC7 -> decodeBc7(b, off, w, h)
            Fmt.RGBA32 -> decodeRaw(b, off, w, h, lay!!)
        }
        return TextureImage(name, w, h, rgba, TextureDecoder.hasTransparency(rgba), "DDS " + fmt.name + (if (level > 0) " (mip $level de ${w0}x$h0)" else ""))
    }

    // ------------------------------------------------------------------------------------------------ sin comprimir

    private fun shiftOf(mask: Int): Int = if (mask == 0) 0 else Integer.numberOfTrailingZeros(mask)
    private fun bitsOf(mask: Int): Int = if (mask == 0) 0 else Integer.bitCount(mask)

    private fun decodeRaw(b: ByteArray, off: Int, w: Int, h: Int, l: Layout): ByteArray {
        val out = ByteArray(w * h * 4); val bpp = l.bits / 8
        val rs = shiftOf(l.rMask); val gs = shiftOf(l.gMask); val bs = shiftOf(l.bMask); val asft = shiftOf(l.aMask)
        fun ch(v: Int, shift: Int, mask: Int): Int { val bits = bitsOf(mask); if (bits == 0) return 255; val x = (v ushr shift) and ((1 shl bits) - 1); return if (bits == 8) x else x * 255 / ((1 shl bits) - 1) }
        for (i in 0 until w * h) {
            var v = 0; for (k in 0 until bpp) v = v or ((b[off + i * bpp + k].toInt() and 0xFF) shl (8 * k))
            out[i * 4] = ch(v, rs, l.rMask).toByte(); out[i * 4 + 1] = ch(v, gs, l.gMask).toByte(); out[i * 4 + 2] = ch(v, bs, l.bMask).toByte()
            out[i * 4 + 3] = (if (l.aMask == 0) 255 else ch(v, asft, l.aMask)).toByte()
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------ BC4 / BC5

    private fun bc4Block(src: ByteArray, p: Int, out: IntArray) {
        val a0 = src[p].toInt() and 0xFF; val a1 = src[p + 1].toInt() and 0xFF
        val t = IntArray(8); t[0] = a0; t[1] = a1
        if (a0 > a1) for (i in 1..6) t[i + 1] = ((7 - i) * a0 + i * a1) / 7
        else { for (i in 1..4) t[i + 1] = ((5 - i) * a0 + i * a1) / 5; t[6] = 0; t[7] = 255 }
        var bits = 0L; for (i in 0 until 6) bits = bits or ((src[p + 2 + i].toLong() and 0xFF) shl (8 * i))
        for (i in 0 until 16) out[i] = t[((bits shr (3 * i)) and 7).toInt()]
    }

    private fun decodeBc4(src: ByteArray, off: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 4); val v = IntArray(16); var p = off
        for (by in 0 until (h + 3) / 4) for (bx in 0 until (w + 3) / 4) {
            bc4Block(src, p, v); p += 8
            for (py in 0 until 4) for (px in 0 until 4) {
                val x = bx * 4 + px; val y = by * 4 + py; if (x >= w || y >= h) continue
                val o = (y * w + x) * 4; val g = v[py * 4 + px].toByte()
                out[o] = g; out[o + 1] = g; out[o + 2] = g; out[o + 3] = 255.toByte()
            }
        }
        return out
    }

    /** BC5 = mapa de normales (X,Y); Z se reconstruye. Solo sirve de vista previa: el GLB exportado usa mapas de color. */
    private fun decodeBc5(src: ByteArray, off: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 4); val r = IntArray(16); val g = IntArray(16); var p = off
        for (by in 0 until (h + 3) / 4) for (bx in 0 until (w + 3) / 4) {
            bc4Block(src, p, r); bc4Block(src, p + 8, g); p += 16
            for (py in 0 until 4) for (px in 0 until 4) {
                val x = bx * 4 + px; val y = by * 4 + py; if (x >= w || y >= h) continue
                val o = (y * w + x) * 4; val nx = r[py * 4 + px] / 127.5f - 1f; val ny = g[py * 4 + px] / 127.5f - 1f
                val nz = Math.sqrt(maxOf(0.0, 1.0 - nx * nx - ny * ny)).toFloat()
                out[o] = r[py * 4 + px].toByte(); out[o + 1] = g[py * 4 + px].toByte(); out[o + 2] = ((nz * 0.5f + 0.5f) * 255f).toInt().toByte(); out[o + 3] = 255.toByte()
            }
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------ BC7

    private class Mode(val ns: Int, val pb: Int, val rb: Int, val isb: Int, val cb: Int, val ab: Int, val epb: Int, val spb: Int, val ib: Int, val ib2: Int)

    private val modes = arrayOf(
        Mode(3, 4, 0, 0, 4, 0, 1, 0, 3, 0), Mode(2, 6, 0, 0, 6, 0, 0, 1, 3, 0), Mode(3, 6, 0, 0, 5, 0, 0, 0, 2, 0), Mode(2, 6, 0, 0, 7, 0, 1, 0, 2, 0),
        Mode(1, 0, 2, 1, 5, 6, 0, 0, 2, 3), Mode(1, 0, 2, 0, 7, 8, 0, 0, 2, 2), Mode(1, 0, 0, 0, 7, 7, 1, 0, 4, 0), Mode(2, 6, 0, 0, 5, 5, 1, 0, 2, 0),
    )

    private val si2 = intArrayOf(
        0xcccc, 0x8888, 0xeeee, 0xecc8, 0xc880, 0xfeec, 0xfec8, 0xec80, 0xc800, 0xffec, 0xfe80, 0xe800, 0xffe8, 0xff00, 0xfff0, 0xf000, 0xf710, 0x008e, 0x7100, 0x08ce,
        0x008c, 0x7310, 0x3100, 0x8cce, 0x088c, 0x3110, 0x6666, 0x366c, 0x17e8, 0x0ff0, 0x718e, 0x399c, 0xaaaa, 0xf0f0, 0x5a5a, 0x33cc, 0x3c3c, 0x55aa, 0x9696, 0xa55a,
        0x73ce, 0x13c8, 0x324c, 0x3bdc, 0x6996, 0xc33c, 0x9966, 0x0660, 0x0272, 0x04e4, 0x4e40, 0x2720, 0xc936, 0x936c, 0x39c6, 0x639c, 0x9336, 0x9cc6, 0x817e, 0xe718,
        0xccf0, 0x0fcc, 0x7744, 0xee22,
    )
    private val si3 = intArrayOf(
        0xaa685050.toInt(), 0x6a5a5040, 0x5a5a4200, 0x5450a0a8, 0xa5a50000.toInt(), 0xa0a05050.toInt(), 0x5555a0a0, 0x5a5a5050, 0xaa550000.toInt(), 0xaa555500.toInt(), 0xaaaa5500.toInt(),
        0x90909090.toInt(), 0x94949494.toInt(), 0xa4a4a4a4.toInt(), 0xa9a59450.toInt(), 0x2a0a4250, 0xa5945040.toInt(), 0x0a425054, 0xa5a5a500.toInt(), 0x55a0a0a0, 0xa8a85454.toInt(),
        0x6a6a4040, 0xa4a45000.toInt(), 0x1a1a0500, 0x0050a4a4, 0xaaa59090.toInt(), 0x14696914, 0x69691400, 0xa08585a0.toInt(), 0xaa821414.toInt(), 0x50a4a450, 0x6a5a0200,
        0xa9a58000.toInt(), 0x5090a0a8, 0xa8a09050.toInt(), 0x24242424, 0x00aa5500, 0x24924924, 0x24499224, 0x50a50a50, 0x500aa550, 0xaaaa4444.toInt(), 0x66660000, 0xa5a0a5a0.toInt(),
        0x50a050a0, 0x69286928, 0x44aaaa44, 0x66666600, 0xaa444444.toInt(), 0x54a854a8, 0x95809580.toInt(), 0x96969600.toInt(), 0xa85454a8.toInt(), 0x80959580.toInt(), 0xaa141414.toInt(),
        0x96960000.toInt(), 0xaaaa1414.toInt(), 0xa05050a0.toInt(), 0xa0a5a5a0.toInt(), 0x96000000.toInt(), 0x40804080, 0xa9a8a9a8.toInt(), 0xaaaaaa44.toInt(), 0x2a4a5254,
    )
    private val ai0 = intArrayOf(
        15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 2, 8, 2, 2, 8, 8, 15, 2, 8, 2, 2, 8, 8, 2, 2, 15, 15, 6, 8, 2, 8, 15, 15, 2, 8, 2, 2, 2, 15, 15, 6, 6, 2, 6, 8,
        15, 15, 2, 2, 15, 15, 15, 15, 15, 2, 2, 15,
    )
    private val ai1 = intArrayOf(
        3, 3, 15, 15, 8, 3, 15, 15, 8, 8, 6, 6, 6, 5, 3, 3, 3, 3, 8, 15, 3, 3, 6, 10, 5, 8, 8, 6, 8, 5, 15, 15, 8, 15, 3, 5, 6, 10, 8, 15, 15, 3, 15, 5, 15, 15, 15, 15, 3, 15, 5, 5,
        5, 8, 5, 10, 5, 10, 8, 13, 15, 12, 3, 3,
    )
    private val ai2 = intArrayOf(
        15, 8, 8, 3, 15, 15, 3, 8, 15, 15, 15, 15, 15, 15, 15, 8, 15, 8, 15, 3, 15, 8, 15, 8, 3, 15, 6, 10, 15, 15, 10, 8, 15, 3, 15, 10, 10, 8, 9, 10, 6, 15, 8, 15, 3, 6, 6, 8, 15, 3, 15, 15,
        15, 15, 15, 15, 15, 15, 15, 15, 3, 15, 15, 8,
    )
    private val w2 = intArrayOf(0, 21, 43, 64)
    private val w3 = intArrayOf(0, 9, 18, 27, 37, 46, 55, 64)
    private val w4 = intArrayOf(0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64)
    private fun weights(n: Int) = when (n) { 2 -> w2; 3 -> w3; else -> w4 }

    private fun bits(src: ByteArray, p: Int, bit: Int, count: Int): Int {
        if (count == 0) return 0
        val by = p + (bit shr 3); val sh = bit and 7
        val x = (src[by].toInt() and 0xFF) or (if (by + 1 < src.size) (src[by + 1].toInt() and 0xFF) shl 8 else 0)
        return (x shr sh) and ((1 shl count) - 1)
    }

    private fun subset(ns: Int, partition: Int, n: Int) = when (ns) { 2 -> 1 and (si2[partition] shr n); 3 -> 3 and (si3[partition] shr (2 * n)); else -> 0 }
    private fun expand(v: Int, b: Int): Int { val x = (v shl (8 - b)) and 0xFF; return x or (x shr b) }

    /** Decodifica un bloque BC7 a 16 píxeles RGBA (arrays [out] de 16*4 enteros). */
    private fun block(src: ByteArray, p: Int, out: IntArray) {
        val mode0 = src[p].toInt() and 0xFF
        if (mode0 == 0) { for (i in 0 until 16) { out[i * 4] = 0; out[i * 4 + 1] = 0; out[i * 4 + 2] = 0; out[i * 4 + 3] = 255 }; return }
        var bit = 0; while (mode0 and (1 shl bit++) == 0) { }
        val info = modes[bit - 1]
        var cb = info.cb; var ab = info.ab
        val cw = weights(info.ib); val aw = weights(if (ab != 0 && info.ib2 != 0) info.ib2 else info.ib)
        fun load(n: Int): Int { val v = bits(src, p, bit, n); bit += n; return v }
        val partition = load(info.pb); val rotation = load(info.rb); val indexSel = load(info.isb)
        val numep = info.ns shl 1
        val ep = Array(6) { IntArray(4) }
        for (c in 0..2) for (i in 0 until numep) ep[i][c] = load(cb)
        for (i in 0 until numep) ep[i][3] = if (ab != 0) load(ab) else 255
        if (info.epb != 0) {
            cb++; if (ab != 0) ab++
            for (i in 0 until numep) { val v = load(1); for (c in 0..2) ep[i][c] = (ep[i][c] shl 1) or v; if (ab != 0) ep[i][3] = (ep[i][3] shl 1) or v }
        }
        if (info.spb != 0) {
            cb++; if (ab != 0) ab++
            var i = 0
            while (i < numep) { val v = load(1); for (j in 0..1) { for (c in 0..2) ep[i + j][c] = (ep[i + j][c] shl 1) or v; if (ab != 0) ep[i + j][3] = (ep[i + j][3] shl 1) or v }; i += 2 }
        }
        for (i in 0 until numep) { for (c in 0..2) ep[i][c] = expand(ep[i][c], cb); if (ab != 0) ep[i][3] = expand(ep[i][3], ab) }
        var cibit = bit
        var aibit = cibit + 16 * info.ib - info.ns
        for (i in 0 until 16) {
            val s = subset(info.ns, partition, i) shl 1
            var ib = info.ib
            if (i == 0) ib-- else if (info.ns == 2) { if (i == ai0[partition]) ib-- } else if (info.ns == 3) { if (i == ai1[partition] || i == ai2[partition]) ib-- }
            val i0 = bits(src, p, cibit, ib); cibit += ib
            var s0 = cw[i0]; var s1 = cw[i0]
            if (ab != 0 && info.ib2 != 0) {
                var ib2 = info.ib2; if (i == 0) ib2--
                val i1 = bits(src, p, aibit, ib2); aibit += ib2
                if (indexSel != 0) { s0 = aw[i1]; s1 = cw[i0] } else { s0 = cw[i0]; s1 = aw[i1] }
            }
            val e0 = ep[s]; val e1 = ep[s + 1]
            val t0 = 64 - s0; val t1 = 64 - s1
            var r = (t0 * e0[0] + s0 * e1[0] + 32) shr 6; var g = (t0 * e0[1] + s0 * e1[1] + 32) shr 6
            var b = (t0 * e0[2] + s0 * e1[2] + 32) shr 6; var a = (t1 * e0[3] + s1 * e1[3] + 32) shr 6
            when (rotation) { 1 -> { val t = r; r = a; a = t }; 2 -> { val t = g; g = a; a = t }; 3 -> { val t = b; b = a; a = t } }
            out[i * 4] = r and 255; out[i * 4 + 1] = g and 255; out[i * 4 + 2] = b and 255; out[i * 4 + 3] = a and 255
        }
    }

    fun decodeBc7(src: ByteArray, off: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 4); val px = IntArray(64); var p = off
        for (by in 0 until (h + 3) / 4) for (bx in 0 until (w + 3) / 4) {
            block(src, p, px); p += 16
            for (py in 0 until 4) for (qx in 0 until 4) {
                val x = bx * 4 + qx; val y = by * 4 + py; if (x >= w || y >= h) continue
                val o = (y * w + x) * 4; val k = (py * 4 + qx) * 4
                out[o] = px[k].toByte(); out[o + 1] = px[k + 1].toByte(); out[o + 2] = px[k + 2].toByte(); out[o + 3] = px[k + 3].toByte()
            }
        }
        return out
    }
}
