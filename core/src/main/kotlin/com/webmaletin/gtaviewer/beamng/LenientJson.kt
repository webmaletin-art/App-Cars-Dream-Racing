package com.webmaletin.gtaviewer.beamng

/**
 * JSON tolerante para .jbeam / .pc / .materials.json de BeamNG: admite comentarios (`//`, `/* */`), comas finales y,
 * sobre todo, FILAS SIN COMA entre elementos de un array o miembros de un objeto (lo permite el formato jbeam).
 * Resultado: Map<String, Any?> / List<Any?> / Double / String / Boolean / null.
 */
class LenientJson private constructor(private val s: String) {
    private var i = 0

    class JsonError(msg: String) : Exception(msg)

    private fun ws() {
        while (i < s.length) {
            val c = s[i]
            if (c <= ' ' || c == '﻿') i++
            else if (c == '/' && i + 1 < s.length && s[i + 1] == '/') { while (i < s.length && s[i] != '\n') i++ }
            else if (c == '/' && i + 1 < s.length && s[i + 1] == '*') { val e = s.indexOf("*/", i + 2); i = if (e < 0) s.length else e + 2 }
            else break
        }
    }

    private fun value(): Any? {
        ws()
        if (i >= s.length) throw JsonError("fin inesperado")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            else -> if (c == '-' || c == '+' || c == '.' || c.isDigit()) num() else word()
        }
    }

    private fun obj(): Map<String, Any?> {
        i++ // {
        val m = LinkedHashMap<String, Any?>()
        while (true) {
            ws()
            if (i >= s.length) throw JsonError("objeto sin cerrar")
            val c = s[i]
            if (c == '}') { i++; return m }
            if (c == ',') { i++; continue }
            val k = if (c == '"') str() else word().toString()
            ws()
            if (i < s.length && s[i] == ':') i++ else throw JsonError("falta ':' tras \"$k\" en posición $i")
            m[k] = value()
        }
    }

    private fun arr(): List<Any?> {
        i++ // [
        val l = ArrayList<Any?>()
        while (true) {
            ws()
            if (i >= s.length) throw JsonError("array sin cerrar")
            val c = s[i]
            if (c == ']') { i++; return l }
            if (c == ',') { i++; continue }
            l += value()
        }
    }

    private fun str(): String {
        i++ // "
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> { if (i >= s.length) break; when (val e = s[i++]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'u' -> { if (i + 4 <= s.length) { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 } }; else -> sb.append(e) } }
                else -> sb.append(c)
            }
        }
        throw JsonError("cadena sin cerrar")
    }

    private fun num(): Double {
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(st, i).toDoubleOrNull() ?: throw JsonError("número inválido '${s.substring(st, i)}'")
    }

    private fun word(): Any? {
        val st = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$' || s[i] == '-' || s[i] == '.')) i++
        if (st == i) throw JsonError("carácter inesperado '${s[i]}' en $i")
        return when (val w = s.substring(st, i)) { "true" -> true; "false" -> false; "null" -> null; else -> w }
    }

    companion object {
        fun parse(text: String): Any? { val p = LenientJson(text); val v = p.value(); return v }
        @Suppress("UNCHECKED_CAST") fun asMap(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()
        @Suppress("UNCHECKED_CAST") fun asList(v: Any?): List<Any?> = (v as? List<Any?>) ?: emptyList()
        fun num(v: Any?): Double? = when (v) { is Double -> v; is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
    }
}
