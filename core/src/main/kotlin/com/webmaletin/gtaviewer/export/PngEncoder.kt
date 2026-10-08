package com.webmaletin.gtaviewer.export

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Codificador PNG RGBA 8 bits sin dependencias (java.util.zip), válido en JVM y Android. */
object PngEncoder {
    fun encode(width: Int, height: Int, rgba: ByteArray): ByteArray {
        require(rgba.size >= width * height * 4) { "Buffer RGBA insuficiente" }
        val raw = ByteArray((width * 4 + 1) * height)
        for (y in 0 until height) {
            raw[y * (width * 4 + 1)] = 0 // filtro None
            System.arraycopy(rgba, y * width * 4, raw, y * (width * 4 + 1) + 1, width * 4)
        }
        val deflater = Deflater(3)
        deflater.setInput(raw); deflater.finish()
        val z = ByteArrayOutputStream(raw.size / 2 + 64)
        val buf = ByteArray(65536)
        while (!deflater.finished()) { val n = deflater.deflate(buf); z.write(buf, 0, n) }
        deflater.end()

        val out = ByteArrayOutputStream(z.size() + 128)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, width); putInt(ihdr, 4, height)
        ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4); putInt(len, 0, data.size)
        out.write(len)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        val c = ByteArray(4); putInt(c, 0, crc.value.toInt())
        out.write(c)
    }
}
