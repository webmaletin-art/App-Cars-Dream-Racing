package com.webmaletin.gtaviewer.export

/** Formateo decimal rápido y determinista (sin notación científica, independiente del locale). */
object Num {
    fun f(v: Float, decimals: Int = 6): String {
        if (!v.isFinite()) return "0"
        val scale = POW10[decimals]
        val scaled = Math.round(v.toDouble() * scale)
        if (scaled == 0L) return "0"
        val neg = scaled < 0
        val abs = Math.abs(scaled)
        val ip = abs / scale
        val fp = abs % scale
        val sb = StringBuilder(16)
        if (neg) sb.append('-')
        sb.append(ip)
        if (fp != 0L) {
            var frac = fp.toString().padStart(decimals, '0').trimEnd('0')
            sb.append('.').append(frac)
        }
        return sb.toString()
    }

    private val POW10 = LongArray(10).also { var p = 1L; for (i in it.indices) { it[i] = p; p *= 10 } }
}
