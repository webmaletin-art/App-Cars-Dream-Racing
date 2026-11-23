package com.webmaletin.gtaviewer.beamng

import java.io.InputStream

/**
 * Lector XML en streaming, mínimo y rápido, pensado para Collada de cientos de MB: nunca construye el documento ni lee textos
 * enormes como String (los arrays numéricos se interpretan al vuelo con [readFloats]/[readInts]).
 */
class XmlPull(private val ins: InputStream) {
    private val buf = ByteArray(1 shl 16)
    private var len = 0
    private var pos = 0
    private var eof = false

    var name = ""; private set
    var isEnd = false; private set
    var selfClosing = false; private set
    private val attrKeys = ArrayList<String>(); private val attrVals = ArrayList<String>()

    fun attr(k: String): String? { val i = attrKeys.indexOf(k); return if (i >= 0) attrVals[i] else null }

    private fun fill(): Boolean {
        if (eof) return false
        len = ins.read(buf, 0, buf.size); pos = 0
        if (len <= 0) { eof = true; len = 0; return false }
        return true
    }

    private fun peek(): Int { if (pos >= len && !fill()) return -1; return buf[pos].toInt() and 0xFF }
    private fun next(): Int { if (pos >= len && !fill()) return -1; return buf[pos++].toInt() and 0xFF }

    /** Avanza hasta la siguiente etiqueta de inicio/fin (ignora texto, comentarios, CDATA y declaraciones). false al final del documento. */
    fun nextTag(): Boolean {
        while (true) {
            // saltar texto hasta '<'
            while (true) {
                if (pos >= len && !fill()) return false
                var i = pos
                while (i < len && buf[i] != '<'.code.toByte()) i++
                if (i < len) { pos = i + 1; break }
                pos = len
            }
            val c = peek()
            when (c) {
                '!'.code -> { skipBang(); continue }
                '?'.code -> { skipUntil("?>"); continue }
                '/'.code -> { pos++; name = readName(); skipUntilGt(); isEnd = true; selfClosing = false; attrKeys.clear(); attrVals.clear(); return true }
                -1 -> return false
            }
            name = readName(); isEnd = false; selfClosing = false; attrKeys.clear(); attrVals.clear()
            readAttrs()
            return true
        }
    }

    private fun skipBang() {
        pos++ // '!'
        if (peek() == '-'.code) { skipUntil("-->") }
        else if (peek() == '['.code) { skipUntil("]]>") }
        else { var depth = 1; while (depth > 0) { val c = next(); if (c < 0) return; if (c == '<'.code) depth++ else if (c == '>'.code) depth-- } }
    }

    private fun skipUntil(end: String) {
        var m = 0
        while (true) {
            val c = next(); if (c < 0) return
            if (c == end[m].code) { m++; if (m == end.length) return } else m = if (c == end[0].code) 1 else 0
        }
    }

    private fun skipUntilGt() { while (true) { val c = next(); if (c < 0 || c == '>'.code) return } }

    private fun isNameChar(c: Int) = c > 32 && c != '='.code && c != '>'.code && c != '/'.code && c != '"'.code && c != '\''.code

    private fun readName(): String {
        val sb = StringBuilder()
        while (true) { val c = peek(); if (c < 0 || !isNameChar(c)) break; sb.append(c.toChar()); pos++ }
        val s = sb.toString(); val i = s.indexOf(':')
        return if (i >= 0) s.substring(i + 1) else s // sin prefijo de espacio de nombres
    }

    private fun skipWs() { while (true) { val c = peek(); if (c < 0 || c > 32) return; pos++ } }

    private fun readAttrs() {
        while (true) {
            skipWs()
            val c = peek()
            if (c < 0) return
            if (c == '>'.code) { pos++; return }
            if (c == '/'.code) { pos++; selfClosing = true; skipUntilGt(); return }
            val k = readName()
            if (k.isEmpty()) { pos++; continue }
            skipWs()
            if (peek() == '='.code) {
                pos++; skipWs()
                val q = next()
                val bytes = java.io.ByteArrayOutputStream()
                if (q == '"'.code || q == '\''.code) { while (true) { val d = next(); if (d < 0 || d == q) break; bytes.write(d) } }
                attrKeys += k; attrVals += unescape(String(bytes.toByteArray(), Charsets.UTF_8))
            } else { attrKeys += k; attrVals += "" }
        }
    }

    private fun unescape(s: String) = if ('&' !in s) s else s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Texto corto hasta la siguiente '<' (se deja la '<' sin consumir). */
    fun readText(): String {
        val sb = java.io.ByteArrayOutputStream()
        while (true) { val c = peek(); if (c < 0 || c == '<'.code) break; sb.write(c); pos++ }
        return unescape(String(sb.toByteArray(), Charsets.UTF_8)).trim()
    }

    /** Lista de floats separados por espacios hasta la siguiente '<'. [expected] solo reserva memoria. */
    fun readFloats(expected: Int = 16): FloatArray {
        var out = FloatArray(maxOf(expected, 4)); var n = 0
        while (true) {
            skipWs(); val c = peek()
            if (c < 0 || c == '<'.code) break
            if (n == out.size) out = out.copyOf(out.size * 2)
            out[n++] = readNumber().toFloat()
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    fun readInts(expected: Int = 16): IntArray {
        var out = IntArray(maxOf(expected, 4)); var n = 0
        while (true) {
            skipWs(); val c = peek()
            if (c < 0 || c == '<'.code) break
            if (n == out.size) out = out.copyOf(out.size * 2)
            out[n++] = readNumber().toInt()
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    private val pow10 = DoubleArray(23) { Math.pow(10.0, it.toDouble()) }

    private fun readNumber(): Double {
        var neg = false
        var c = peek()
        if (c == '-'.code) { neg = true; pos++; c = peek() } else if (c == '+'.code) { pos++; c = peek() }
        var mant = 0L; var digits = 0; var scale = 0; var seenDot = false
        while (true) {
            c = peek()
            if (c in '0'.code..'9'.code) {
                if (digits < 18) { mant = mant * 10 + (c - '0'.code); digits++; if (seenDot) scale++ } else if (!seenDot) scale-- // dígitos de más: se pierden (no importa a 18 cifras)
                pos++
            } else if (c == '.'.code && !seenDot) { seenDot = true; pos++ } else break
        }
        var exp = 0
        if (c == 'e'.code || c == 'E'.code) {
            pos++; var eneg = false; c = peek()
            if (c == '-'.code) { eneg = true; pos++ } else if (c == '+'.code) pos++
            while (true) { c = peek(); if (c in '0'.code..'9'.code) { exp = exp * 10 + (c - '0'.code); pos++ } else break }
            if (eneg) exp = -exp
        } else if (c > 32 && c != '<'.code && !(c in '0'.code..'9'.code)) {
            // token no numérico (nan, inf…): consumir y devolver 0
            while (true) { val d = peek(); if (d <= 32 || d == '<'.code) break; pos++ }
            return 0.0
        }
        val e = exp - scale
        val v = if (e >= 0) mant * (if (e < pow10.size) pow10[e] else Math.pow(10.0, e.toDouble())) else mant / (if (-e < pow10.size) pow10[-e] else Math.pow(10.0, (-e).toDouble()))
        return if (neg) -v else v
    }

    /** Salta hasta la etiqueta de cierre de [tag] (el elemento recién abierto), sin interpretar nada. */
    fun skipElement() {
        if (selfClosing) return
        var depth = 1
        while (depth > 0) { if (!nextTag()) return; if (isEnd) depth-- else if (!selfClosing) depth++ }
    }
}
