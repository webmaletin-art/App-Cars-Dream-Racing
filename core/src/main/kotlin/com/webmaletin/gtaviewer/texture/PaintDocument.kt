package com.webmaletin.gtaviewer.texture

import com.webmaletin.gtaviewer.txd.TextureImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Capa RGBA (alfa recto, 8 bits). */
class Layer(var name: String, val width: Int, val height: Int, var rgba: ByteArray = ByteArray(width * height * 4), var visible: Boolean = true, var opacity: Float = 1f) {
    fun copy() = Layer(name, width, height, rgba.copyOf(), visible, opacity)
}

enum class BrushMode { PAINT, ERASE, RESTORE }

/**
 * Documento de pintura sobre una textura: capa 0 = textura original (se conserva una copia para restaurar), capas extra
 * para diseños. Todas las operaciones se registran en un historial de deshacer/rehacer por teselas (memoria proporcional a lo
 * tocado, no a la textura entera). [commitTo] escribe el resultado aplanado en la imagen de textura (cambios guardados en píxeles).
 */
class PaintDocument(val width: Int, val height: Int, base: ByteArray, val name: String) {
    val layers = ArrayList<Layer>()
    val original: ByteArray = base.copyOf()
    var active = 0

    init { layers += Layer("Original", width, height, base.copyOf()) }

    // ------------------------------------------------------------------ historial por teselas

    private class TileSnap(val layer: Int, val tx: Int, val ty: Int, val before: ByteArray, var after: ByteArray? = null)
    private class Entry(val label: String, val tiles: List<TileSnap>, val structural: (() -> Unit)? = null, val structuralUndo: (() -> Unit)? = null)

    private val undoStack = ArrayDeque<Entry>(); private val redoStack = ArrayDeque<Entry>()
    private var openTiles: LinkedHashMap<Long, TileSnap>? = null
    private var openLabel = ""
    var revision = 0; private set
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    private val T = 64
    private fun tilesX() = (width + T - 1) / T

    fun begin(label: String) { openTiles = LinkedHashMap(); openLabel = label }

    private fun touch(layer: Int, x: Int, y: Int) {
        val ot = openTiles ?: return
        val tx = x / T; val ty = y / T
        val key = (layer.toLong() shl 40) or (ty.toLong() shl 20) or tx.toLong()
        if (key in ot) return
        val l = layers[layer]
        val x0 = tx * T; val y0 = ty * T; val w = min(T, width - x0); val h = min(T, height - y0)
        val buf = ByteArray(w * h * 4)
        for (r in 0 until h) System.arraycopy(l.rgba, ((y0 + r) * width + x0) * 4, buf, r * w * 4, w * 4)
        ot[key] = TileSnap(layer, tx, ty, buf)
    }

    fun end() {
        val ot = openTiles ?: return
        openTiles = null
        if (ot.isEmpty()) return
        for (s in ot.values) {
            val l = layers[s.layer]; val x0 = s.tx * T; val y0 = s.ty * T; val w = min(T, width - x0); val h = min(T, height - y0)
            val buf = ByteArray(w * h * 4)
            for (r in 0 until h) System.arraycopy(l.rgba, ((y0 + r) * width + x0) * 4, buf, r * w * 4, w * 4)
            s.after = buf
        }
        undoStack.addLast(Entry(openLabel, ot.values.toList())); if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear(); revision++
    }

    private fun applyTiles(tiles: List<TileSnap>, useAfter: Boolean) {
        for (s in tiles) {
            val l = layers[s.layer]; val x0 = s.tx * T; val y0 = s.ty * T; val w = min(T, width - x0); val h = min(T, height - y0)
            val src = if (useAfter) s.after!! else s.before
            for (r in 0 until h) System.arraycopy(src, r * w * 4, l.rgba, ((y0 + r) * width + x0) * 4, w * 4)
        }
    }

    fun undo(): Boolean {
        val e = undoStack.removeLastOrNull() ?: return false
        if (e.structuralUndo != null) e.structuralUndo.invoke() else applyTiles(e.tiles, false)
        redoStack.addLast(e); revision++; return true
    }

    fun redo(): Boolean {
        val e = redoStack.removeLastOrNull() ?: return false
        if (e.structural != null) e.structural.invoke() else applyTiles(e.tiles, true)
        undoStack.addLast(e); revision++; return true
    }

    // ------------------------------------------------------------------ capas

    fun addLayer(name: String): Int {
        val l = Layer(name, width, height); layers += l; active = layers.size - 1
        val idx = active
        undoStack.addLast(Entry("capa", emptyList(), { layers.add(idx.coerceAtMost(layers.size), l); active = idx }, { layers.remove(l); active = (layers.size - 1).coerceAtLeast(0) }))
        redoStack.clear(); revision++
        return idx
    }

    fun removeLayer(i: Int) {
        if (i <= 0 || i >= layers.size) return
        val l = layers.removeAt(i); active = active.coerceAtMost(layers.size - 1)
        undoStack.addLast(Entry("quitar capa", emptyList(), { layers.remove(l); active = active.coerceAtMost(layers.size - 1) }, { layers.add(i, l); active = i }))
        redoStack.clear(); revision++
    }

    fun setVisible(i: Int, v: Boolean) { layers[i].visible = v; revision++ }
    fun setOpacity(i: Int, o: Float) { layers[i].opacity = o.coerceIn(0f, 1f); revision++ }

    // ------------------------------------------------------------------ composición

    /** Aplana todas las capas visibles (alfa recto, "sobre"). */
    fun flatten(): ByteArray = flattenRect(0, 0, width, height).let { out ->
        val full = ByteArray(width * height * 4)
        for (r in 0 until height) System.arraycopy(out, r * width * 4, full, r * width * 4, width * 4)
        full
    }

    fun flattenRect(x0: Int, y0: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(width * height * 4)
        val xe = min(width, x0 + w); val ye = min(height, y0 + h)
        for (y in max(0, y0) until ye) for (x in max(0, x0) until xe) {
            val o = (y * width + x) * 4
            var r = 0f; var g = 0f; var b = 0f; var a = 0f
            for (l in layers) {
                if (!l.visible) continue
                val sa = ((l.rgba[o + 3].toInt() and 0xFF) / 255f) * l.opacity
                if (sa <= 0f) continue
                val sr = (l.rgba[o].toInt() and 0xFF) / 255f; val sg = (l.rgba[o + 1].toInt() and 0xFF) / 255f; val sb = (l.rgba[o + 2].toInt() and 0xFF) / 255f
                val oa = sa + a * (1 - sa)
                if (oa > 0f) { r = (sr * sa + r * a * (1 - sa)) / oa; g = (sg * sa + g * a * (1 - sa)) / oa; b = (sb * sa + b * a * (1 - sa)) / oa }
                a = oa
            }
            out[o] = (r * 255f + 0.5f).toInt().toByte(); out[o + 1] = (g * 255f + 0.5f).toInt().toByte(); out[o + 2] = (b * 255f + 0.5f).toInt().toByte(); out[o + 3] = (a * 255f + 0.5f).toInt().toByte()
        }
        return out
    }

    /** Escribe el resultado aplanado en la textura (la textura original del modelo importado nunca se toca: se edita su copia). */
    fun commitTo(t: TextureImage) {
        require(t.width == width && t.height == height) { "Tamaño de textura distinto" }
        System.arraycopy(flatten(), 0, t.rgba, 0, t.rgba.size); t.markChanged()
    }

    fun restoreOriginal() {
        begin("restaurar original")
        val l = layers[0]
        for (ty in 0 until (height + T - 1) / T) for (tx in 0 until tilesX()) touch(0, tx * T, ty * T)
        System.arraycopy(original, 0, l.rgba, 0, original.size)
        for (i in layers.size - 1 downTo 1) { layers.removeAt(i) }
        active = 0; end()
    }

    // ------------------------------------------------------------------ pintura

    private fun blend(l: Layer, x: Int, y: Int, r: Int, g: Int, b: Int, a: Float, mode: BrushMode) {
        if (x < 0 || y < 0 || x >= width || y >= height || a <= 0f) return
        val o = (y * width + x) * 4
        touch(layers.indexOf(l), x, y)
        when (mode) {
            BrushMode.ERASE -> { val na = ((l.rgba[o + 3].toInt() and 0xFF) * (1f - a)); l.rgba[o + 3] = (na + 0.5f).toInt().toByte() }
            BrushMode.RESTORE -> { // vuelve hacia los píxeles de la textura original
                for (c in 0..3) { val dst = l.rgba[o + c].toInt() and 0xFF; val src = original[o + c].toInt() and 0xFF; l.rgba[o + c] = (dst + (src - dst) * a + 0.5f).toInt().toByte() }
            }
            BrushMode.PAINT -> {
                val da = (l.rgba[o + 3].toInt() and 0xFF) / 255f
                val oa = a + da * (1 - a)
                if (oa <= 0f) return
                val dr = l.rgba[o].toInt() and 0xFF; val dg = l.rgba[o + 1].toInt() and 0xFF; val db = l.rgba[o + 2].toInt() and 0xFF
                l.rgba[o] = ((r * a + dr * da * (1 - a)) / oa + 0.5f).toInt().toByte()
                l.rgba[o + 1] = ((g * a + dg * da * (1 - a)) / oa + 0.5f).toInt().toByte()
                l.rgba[o + 2] = ((b * a + db * da * (1 - a)) / oa + 0.5f).toInt().toByte()
                l.rgba[o + 3] = (oa * 255f + 0.5f).toInt().toByte()
            }
        }
    }

    /** Sello circular con borde suave (hardness 1 = duro). Una pasada de trazo NO acumula opacidad sobre sí misma. */
    fun dab(cx: Float, cy: Float, radius: Float, rgb: Int, opacity: Float, hardness: Float, mode: BrushMode = BrushMode.PAINT, stroke: BooleanArray? = null) {
        val l = layers[active]
        val r = (rgb shr 16) and 255; val g = (rgb shr 8) and 255; val b = rgb and 255
        val x0 = floor(cx - radius).toInt(); val x1 = ceil(cx + radius).toInt(); val y0 = floor(cy - radius).toInt(); val y1 = ceil(cy + radius).toInt()
        for (y in y0..y1) for (x in x0..x1) {
            if (x < 0 || y < 0 || x >= width || y >= height) continue
            val d = sqrt((x + 0.5f - cx).let { it * it } + (y + 0.5f - cy).let { it * it })
            if (d > radius) continue
            val edge = if (hardness >= 0.999f) 1f else (1f - ((d / radius) - hardness).coerceAtLeast(0f) / (1f - hardness)).coerceIn(0f, 1f)
            var a = opacity * edge
            if (stroke != null) { val k = y * width + x; if (stroke[k]) continue; if (edge >= 0.999f) stroke[k] = true }
            blend(l, x, y, r, g, b, a, mode)
        }
    }

    /** Trazo continuo entre dos puntos (interpola sellos cada ~radio/3 píxeles). */
    fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, rgb: Int, opacity: Float, hardness: Float, mode: BrushMode = BrushMode.PAINT) {
        val dist = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
        val step = max(1f, radius / 3f); val n = max(1, (dist / step).toInt())
        val mask = BooleanArray(width * height)
        for (i in 0..n) { val t = i / n.toFloat(); dab(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, radius, rgb, opacity, hardness, mode, mask) }
    }

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, thickness: Float, rgb: Int, opacity: Float) =
        stroke(x0, y0, x1, y1, thickness / 2f, rgb, opacity, 1f)

    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, rgb: Int, opacity: Float, filled: Boolean = true, thickness: Int = 2) {
        val l = layers[active]; val r = (rgb shr 16) and 255; val g = (rgb shr 8) and 255; val b = rgb and 255
        for (y in min(y0, y1)..max(y0, y1)) for (x in min(x0, x1)..max(x0, x1)) {
            val inner = x - min(x0, x1) >= thickness && max(x0, x1) - x >= thickness && y - min(y0, y1) >= thickness && max(y0, y1) - y >= thickness
            if (filled || !inner) blend(l, x, y, r, g, b, opacity, BrushMode.PAINT)
        }
    }

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, rgb: Int, opacity: Float, filled: Boolean = true, thickness: Float = 2f) {
        val l = layers[active]; val r = (rgb shr 16) and 255; val g = (rgb shr 8) and 255; val b = rgb and 255
        for (y in floor(cy - ry).toInt()..ceil(cy + ry).toInt()) for (x in floor(cx - rx).toInt()..ceil(cx + rx).toInt()) {
            val dx = (x + 0.5f - cx) / rx; val dy = (y + 0.5f - cy) / ry; val d = dx * dx + dy * dy
            if (d > 1f) continue
            if (filled || d >= ((1f - thickness / min(rx, ry)).coerceAtLeast(0f)).let { it * it }) blend(l, x, y, r, g, b, opacity, BrushMode.PAINT)
        }
    }

    /** Relleno por inundación con tolerancia (sobre la capa activa). */
    fun fill(x: Int, y: Int, rgb: Int, opacity: Float, tolerance: Int = 24): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        val l = layers[active]; val o0 = (y * width + x) * 4
        val tr = l.rgba[o0].toInt() and 0xFF; val tg = l.rgba[o0 + 1].toInt() and 0xFF; val tb = l.rgba[o0 + 2].toInt() and 0xFF; val ta = l.rgba[o0 + 3].toInt() and 0xFF
        val seen = BooleanArray(width * height); val q = ArrayDeque<Int>(); q += y * width + x; seen[y * width + x] = true
        var n = 0
        val r = (rgb shr 16) and 255; val g = (rgb shr 8) and 255; val b = rgb and 255
        while (q.isNotEmpty()) {
            val p = q.removeLast(); val px = p % width; val py = p / width
            val o = p * 4
            if (abs((l.rgba[o].toInt() and 0xFF) - tr) > tolerance || abs((l.rgba[o + 1].toInt() and 0xFF) - tg) > tolerance ||
                abs((l.rgba[o + 2].toInt() and 0xFF) - tb) > tolerance || abs((l.rgba[o + 3].toInt() and 0xFF) - ta) > tolerance) continue
            blend(l, px, py, r, g, b, opacity, BrushMode.PAINT); n++
            if (px > 0 && !seen[p - 1]) { seen[p - 1] = true; q += p - 1 }
            if (px < width - 1 && !seen[p + 1]) { seen[p + 1] = true; q += p + 1 }
            if (py > 0 && !seen[p - width]) { seen[p - width] = true; q += p - width }
            if (py < height - 1 && !seen[p + width]) { seen[p + width] = true; q += p + width }
        }
        return n
    }

    /**
     * Estampa una imagen (logotipo, texto renderizado, diseño) con traslación, rotación y escala en la capa activa. Bilineal.
     * [cx],[cy] = centro destino en píxeles de textura; [scale] relativa al tamaño original.
     */
    fun stamp(src: ByteArray, sw: Int, sh: Int, cx: Float, cy: Float, scale: Float, rotationDeg: Float, opacity: Float) {
        val l = layers[active]
        val rad = Math.toRadians(rotationDeg.toDouble()); val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
        val hw = sw * scale / 2f; val hh = sh * scale / 2f
        val ext = sqrt(hw * hw + hh * hh)
        for (y in floor(cy - ext).toInt()..ceil(cy + ext).toInt()) for (x in floor(cx - ext).toInt()..ceil(cx + ext).toInt()) {
            if (x < 0 || y < 0 || x >= width || y >= height) continue
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            val u = ( c * dx + s * dy) / scale + sw / 2f; val v = (-s * dx + c * dy) / scale + sh / 2f
            if (u < 0f || v < 0f || u >= sw - 1 || v >= sh - 1) continue
            val x0 = u.toInt(); val y0 = v.toInt(); val fx = u - x0; val fy = v - y0
            fun px(ix: Int, iy: Int, ch: Int) = (src[(iy * sw + ix) * 4 + ch].toInt() and 0xFF).toFloat()
            fun bil(ch: Int) = (px(x0, y0, ch) * (1 - fx) + px(x0 + 1, y0, ch) * fx) * (1 - fy) + (px(x0, y0 + 1, ch) * (1 - fx) + px(x0 + 1, y0 + 1, ch) * fx) * fy
            val a = bil(3) / 255f * opacity
            if (a <= 0f) continue
            blend(l, x, y, bil(0).toInt(), bil(1).toInt(), bil(2).toInt(), a, BrushMode.PAINT)
        }
    }

    /**
     * Quita un logotipo pintado: rellena la región [mask] (rectángulo [rx],[ry],[rw],[rh]) difundiendo el color de su alrededor
     * (relleno por difusión). Solo modifica la región; el resto del diseño se conserva.
     */
    fun inpaint(rx: Int, ry: Int, rw: Int, rh: Int, mask: BooleanArray? = null, iterations: Int = 80) {
        val l = layers[active]
        val x0 = max(0, rx); val y0 = max(0, ry); val x1 = min(width, rx + rw); val y1 = min(height, ry + rh)
        if (x1 <= x0 || y1 <= y0) return
        val w = x1 - x0; val h = y1 - y0
        val known = BooleanArray(w * h) { i -> mask?.getOrNull(i)?.not() ?: false }
        val buf = FloatArray(w * h * 4)
        for (yy in 0 until h) for (xx in 0 until w) for (c in 0..3) buf[(yy * w + xx) * 4 + c] = (l.rgba[((y0 + yy) * width + x0 + xx) * 4 + c].toInt() and 0xFF).toFloat()
        fun outside(nx: Int, ny: Int, c: Int): Float {
            val gx = x0 + nx; val gy = y0 + ny
            return if (gx in 0 until width && gy in 0 until height) (l.rgba[(gy * width + gx) * 4 + c].toInt() and 0xFF).toFloat() else Float.NaN
        }
        val dirs = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        // 1) relleno desde el borde hacia dentro: solo se promedian vecinos YA válidos (los píxeles a rellenar no cuentan)
        val filled = known.copyOf()
        var guard = w + h + 2
        while (guard-- > 0 && filled.any { !it }) {
            val nb = buf.copyOf(); val nf = filled.copyOf()
            for (yy in 0 until h) for (xx in 0 until w) {
                val idx = yy * w + xx
                if (filled[idx]) continue
                for (c in 0..3) {
                    var sum = 0f; var n = 0
                    for ((dx, dy) in dirs) {
                        val nx = xx + dx; val ny = yy + dy
                        val inside = nx in 0 until w && ny in 0 until h
                        val v = if (inside) { if (filled[ny * w + nx]) buf[(ny * w + nx) * 4 + c] else Float.NaN } else outside(nx, ny, c)
                        if (!v.isNaN()) { sum += v; n++ }
                    }
                    if (n > 0) { nb[idx * 4 + c] = sum / n; nf[idx] = true }
                }
            }
            System.arraycopy(nb, 0, buf, 0, buf.size); System.arraycopy(nf, 0, filled, 0, filled.size)
        }
        // 2) suavizado por difusión (Jacobi) para que no queden bandas
        for (it in 0 until iterations) {
            val nb = buf.copyOf()
            for (yy in 0 until h) for (xx in 0 until w) {
                val idx = yy * w + xx
                if (known[idx]) continue
                for (c in 0..3) {
                    var sum = 0f; var n = 0
                    for ((dx, dy) in dirs) {
                        val nx = xx + dx; val ny = yy + dy
                        val v = if (nx in 0 until w && ny in 0 until h) buf[(ny * w + nx) * 4 + c] else outside(nx, ny, c)
                        if (!v.isNaN()) { sum += v; n++ }
                    }
                    if (n > 0) nb[idx * 4 + c] = sum / n
                }
            }
            System.arraycopy(nb, 0, buf, 0, buf.size)
        }
        for (yy in 0 until h) for (xx in 0 until w) {
            if (known[yy * w + xx]) continue
            touch(active, x0 + xx, y0 + yy)
            for (c in 0..3) l.rgba[((y0 + yy) * width + x0 + xx) * 4 + c] = (buf[(yy * w + xx) * 4 + c] + 0.5f).toInt().toByte()
        }
    }

    // ------------------------------------------------------------------ persistencia de capas

    fun layerBytes(i: Int): ByteArray = layers[i].rgba.copyOf()
}
