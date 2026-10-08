package com.webmaletin.gtaviewer.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.webmaletin.gtaviewer.camera.OrbitCamera
import com.webmaletin.gtaviewer.export.PaintScheme
import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.txd.TextureImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Estado que la UI entrega al renderer (inmutable; se reemplaza atómicamente). */
class RenderState(
    val model: VehicleModel?,
    val visible: Set<Int>,
    val selection: Set<Int>,
    val paint: PaintScheme,
    val sceneScale: Float,
)

/**
 * Renderer OpenGL ES 3.0. Solo mantiene en GPU el vehículo actual: al cambiar de modelo (o perder el contexto)
 * libera TODOS los buffers y texturas anteriores (VBO, IBO, texturas).
 */
class VehicleRenderer(private val camera: OrbitCamera) : GLSurfaceView.Renderer {
    @Volatile var state = RenderState(null, emptySet(), emptySet(), PaintScheme(), 1f)
    @Volatile var onFrameRequest: (() -> Unit)? = null
    @Volatile var onSurfaceSize: ((Int, Int) -> Unit)? = null
    @Volatile var gpuError: String? = null

    private var width = 1
    private var height = 1

    private var meshProg = 0
    private var lineProg = 0
    private var gpuModel: VehicleModel? = null
    private val geoVbo = HashMap<Int, Int>()
    private val subIbo = HashMap<Int, Int>()
    private val texIds = HashMap<TextureImage, Int>()
    private var lineVbo = 0
    private var groundY = 0f
    private var maxTexSize = 2048

    // uniforms
    private var uMVP = 0; private var uModel = 0; private var uTex = 0; private var uColor = 0
    private var uUseTex = 0; private var uCamPos = 0; private var uHighlight = 0; private var uHighlightColor = 0
    private var lMVP = 0; private var lColor = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // El contexto GL es nuevo: los ids anteriores ya no son válidos.
        geoVbo.clear(); subIbo.clear(); texIds.clear(); gpuModel = null; lineVbo = 0
        GLES30.glClearColor(0.058f, 0.067f, 0.082f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        try {
            meshProg = link(ViewerShaders.MESH_VERTEX, ViewerShaders.MESH_FRAGMENT)
            lineProg = link(ViewerShaders.LINE_VERTEX, ViewerShaders.LINE_FRAGMENT)
            uMVP = GLES30.glGetUniformLocation(meshProg, "uMVP"); uModel = GLES30.glGetUniformLocation(meshProg, "uModel")
            uTex = GLES30.glGetUniformLocation(meshProg, "uTex"); uColor = GLES30.glGetUniformLocation(meshProg, "uColor")
            uUseTex = GLES30.glGetUniformLocation(meshProg, "uUseTex"); uCamPos = GLES30.glGetUniformLocation(meshProg, "uCamPos")
            uHighlight = GLES30.glGetUniformLocation(meshProg, "uHighlight"); uHighlightColor = GLES30.glGetUniformLocation(meshProg, "uHighlightColor")
            lMVP = GLES30.glGetUniformLocation(lineProg, "uMVP"); lColor = GLES30.glGetUniformLocation(lineProg, "uColor")
            val sz = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, sz, 0); maxTexSize = sz[0].coerceAtLeast(1024)
            val buf = IntArray(1); GLES30.glGenBuffers(1, buf, 0); lineVbo = buf[0]
            gpuError = null
        } catch (e: Exception) {
            gpuError = "Error de shaders OpenGL: ${e.message}"
            Log.e(TAG, gpuError!!, e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
        onSurfaceSize?.invoke(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (meshProg == 0) return
        val st = state
        if (st.model !== gpuModel) switchModel(st.model)
        val model = gpuModel ?: return

        val aspect = width.toFloat() / height
        val (view, proj, camPos) = synchronized(camera) { Triple(camera.viewMatrix(), camera.projectionMatrix(aspect), camera.position()) }
        val vp = Mat4.multiply(proj, view)
        val scaleM = Mat4.scale(st.sceneScale)

        drawGround(vp, st.sceneScale)

        GLES30.glUseProgram(meshProg)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glUniform3f(uCamPos, camPos[0], camPos[1], camPos[2])
        GLES30.glUniform3f(uHighlightColor, 1f, 0.62f, 0.05f)

        for (pass in 0..1) {
            if (pass == 1) { GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA); GLES30.glDepthMask(false) }
            for (part in model.parts) {
                val m = Mat4.multiply(scaleM, model.viewMatrix(part))
                var uploadedMatrix = false
                for (sm in part.subMeshes) {
                    if (sm.id !in st.visible) continue
                    val mat = sm.material
                    if (mat.transparent != (pass == 1)) continue
                    if (!uploadedMatrix) {
                        GLES30.glUniformMatrix4fv(uModel, 1, false, m, 0)
                        GLES30.glUniformMatrix4fv(uMVP, 1, false, Mat4.multiply(vp, m), 0)
                        uploadedMatrix = true
                    }
                    drawSub(sm, st)
                }
            }
        }
        GLES30.glDepthMask(true); GLES30.glDisable(GLES30.GL_BLEND)
        drawSelectionBoxes(model, st, vp)
    }

    private fun drawSub(sm: SubMesh, st: RenderState) {
        val vbo = geoVbo[sm.geometry.id] ?: return
        val ibo = subIbo[sm.id] ?: return
        val mat = sm.material
        val painted = st.paint.colorFor(mat.paintSlot)
        val r = (painted?.let { (it shr 16) and 255 } ?: mat.r) / 255f
        val g = (painted?.let { (it shr 8) and 255 } ?: mat.g) / 255f
        val b = (painted?.let { it and 255 } ?: mat.b) / 255f
        GLES30.glUniform4f(uColor, r, g, b, mat.a / 255f)
        val tex = mat.texture?.let { texIds[it] }
        if (tex != null) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glUniform1i(uUseTex, 1)
        } else GLES30.glUniform1i(uUseTex, 0)
        GLES30.glUniform1f(uHighlight, if (sm.id in st.selection) 0.55f else 0f)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, STRIDE, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, STRIDE, 24)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, sm.indices.size, GLES30.GL_UNSIGNED_INT, 0)
    }

    private val lineData = java.nio.ByteBuffer.allocateDirect(4 * 3 * 2 * 400).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun drawLines(verts: FloatArray, count: Int, mvp: FloatArray, r: Float, g: Float, b: Float, a: Float) {
        if (count == 0 || lineVbo == 0) return
        GLES30.glUseProgram(lineProg)
        GLES30.glUniformMatrix4fv(lMVP, 1, false, mvp, 0)
        GLES30.glUniform4f(lColor, r, g, b, a)
        lineData.clear(); lineData.put(verts, 0, count * 3); lineData.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * 12, lineData, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glDisableVertexAttribArray(1); GLES30.glDisableVertexAttribArray(2)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, count)
    }

    private fun drawGround(vp: FloatArray, scale: Float) {
        val half = 6
        val step = (gridStep * scale)
        val v = FloatArray((half * 2 + 1) * 4 * 3)
        var n = 0
        val y = groundY * scale
        val ext = half * step
        for (i in -half..half) {
            v[n++] = i * step; v[n++] = y; v[n++] = -ext; v[n++] = i * step; v[n++] = y; v[n++] = ext
            v[n++] = -ext; v[n++] = y; v[n++] = i * step; v[n++] = ext; v[n++] = y; v[n++] = i * step
        }
        drawLines(v, n / 3, vp, 0.35f, 0.38f, 0.45f, 0.5f)
    }

    private var gridStep = 1f

    private fun drawSelectionBoxes(model: VehicleModel, st: RenderState, vp: FloatArray) {
        if (st.selection.isEmpty()) return
        val partIds = st.selection.mapNotNull { model.partOf(it)?.id }.toSet()
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        val v = FloatArray(24 * 3)
        for (pid in partIds.take(16)) {
            val part = model.part(pid) ?: continue
            val b = model.partBounds(part)
            if (!b.isValid) continue
            val mn = b.min.map { it * st.sceneScale }; val mx = b.max.map { it * st.sceneScale }
            var n = 0
            fun p(x: Int, y: Int, z: Int) { v[n++] = if (x == 0) mn[0] else mx[0]; v[n++] = if (y == 0) mn[1] else mx[1]; v[n++] = if (z == 0) mn[2] else mx[2] }
            for (a in 0..1) for (c in 0..1) { p(0, a, c); p(1, a, c) }
            for (a in 0..1) for (c in 0..1) { p(a, 0, c); p(a, 1, c) }
            for (a in 0..1) for (c in 0..1) { p(a, c, 0); p(a, c, 1) }
            drawLines(v, 24, vp, 1f, 0.72f, 0.1f, 1f)
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    // ------------------------------------------------------------------ gestión de recursos GPU

    private fun switchModel(newModel: VehicleModel?) {
        releaseGpu()
        gpuModel = newModel
        if (newModel == null) return
        try {
            val full = newModel.fullBounds()
            if (full != null) {
                groundY = full.min[1]
                val r = full.radius
                gridStep = if (r > 40) 10f else if (r > 12) 5f else if (r > 6) 2f else 1f
            }
            val uploadedGeo = HashSet<Int>()
            for (sm in newModel.subMeshes) {
                val g = sm.geometry
                if (uploadedGeo.add(g.id)) geoVbo[g.id] = uploadGeometry(g)
                subIbo[sm.id] = uploadIndices(sm.indices)
            }
            for (m in newModel.materials) m.texture?.let { if (it !in texIds) texIds[it] = uploadTexture(it) }
            gpuError = null
        } catch (e: Throwable) {
            gpuError = "No se pudo subir el modelo a la GPU: ${e.message}"
            Log.e(TAG, gpuError!!, e)
            releaseGpu()
        }
    }

    /** Libera VBO, IBO y texturas del vehículo anterior. */
    fun releaseGpu() {
        if (geoVbo.isNotEmpty()) GLES30.glDeleteBuffers(geoVbo.size, geoVbo.values.toIntArray(), 0)
        if (subIbo.isNotEmpty()) GLES30.glDeleteBuffers(subIbo.size, subIbo.values.toIntArray(), 0)
        if (texIds.isNotEmpty()) GLES30.glDeleteTextures(texIds.size, texIds.values.toIntArray(), 0)
        geoVbo.clear(); subIbo.clear(); texIds.clear()
    }

    private fun uploadGeometry(g: GeometryData): Int {
        val bb = ByteBuffer.allocateDirect(g.vertexCount * STRIDE).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        for (i in 0 until g.vertexCount) {
            fb.put(g.positions, i * 3, 3); fb.put(g.normals, i * 3, 3); fb.put(g.uvs, i * 2, 2)
        }
        fb.position(0)
        val id = IntArray(1); GLES30.glGenBuffers(1, id, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, g.vertexCount * STRIDE, fb, GLES30.GL_STATIC_DRAW)
        return id[0]
    }

    private fun uploadIndices(idx: IntArray): Int {
        val ib = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(idx); ib.position(0)
        val id = IntArray(1); GLES30.glGenBuffers(1, id, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, id[0])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ib, GLES30.GL_STATIC_DRAW)
        return id[0]
    }

    private fun uploadTexture(t: TextureImage): Int {
        var w = t.width; var h = t.height; var data = t.rgba
        while (w > maxTexSize || h > maxTexSize) { // reducción 2x2 si la GPU no admite el tamaño
            val nw = maxOf(1, w / 2); val nh = maxOf(1, h / 2)
            val out = ByteArray(nw * nh * 4)
            for (y in 0 until nh) for (x in 0 until nw) for (c in 0..3) {
                var s = 0
                for (dy in 0..1) for (dx in 0..1) s += data[(minOf(h - 1, y * 2 + dy) * w + minOf(w - 1, x * 2 + dx)) * 4 + c].toInt() and 0xFF
                out[(y * nw + x) * 4 + c] = (s / 4).toByte()
            }
            data = out; w = nw; h = nh
        }
        val bb = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()); bb.put(data); bb.position(0)
        val id = IntArray(1); GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        return id[0]
    }

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs); val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES30.glGetProgramInfoLog(p); GLES30.glDeleteProgram(p); throw RuntimeException("link: $log") }
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES30.glGetShaderInfoLog(s); GLES30.glDeleteShader(s); throw RuntimeException("shader: $log") }
        return s
    }

    companion object {
        private const val TAG = "VehicleRenderer"
        private const val STRIDE = 32
    }
}
