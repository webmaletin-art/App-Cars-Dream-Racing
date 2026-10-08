package com.webmaletin.gtaviewer.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.webmaletin.gtaviewer.camera.CameraPreset
import com.webmaletin.gtaviewer.camera.OrbitCamera
import com.webmaletin.gtaviewer.model.Bounds
import kotlin.math.abs
import kotlin.math.hypot
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * Vista 3D interactiva: un dedo = rotar; dos dedos = zoom (pellizco) y desplazar (pan); toque corto = seleccionar;
 * doble toque = auto encuadrar.
 */
class GlViewerView(context: Context) : GLSurfaceView(context) {
    val camera = OrbitCamera()
    val renderer = VehicleRenderer(camera)

    var onTap: ((x: Float, y: Float, w: Int, h: Int) -> Unit)? = null
    private var pendingFrame: (() -> Unit)? = null
    private var framedOnce = false

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f; private var downTime = 0L
    private var lastX = 0f; private var lastY = 0f
    private var lastDist = 0f; private var lastMidX = 0f; private var lastMidY = 0f
    private var moved = false; private var multi = false
    private var lastTapTime = 0L

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(DepthFallbackChooser())
        preserveEGLContextOnPause = false
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        renderer.onSurfaceSize = { _, _ -> post { pendingFrame?.let { it(); pendingFrame = null }; requestRender() } }
    }

    fun update(state: RenderState) {
        renderer.state = state
        requestRender()
    }

    /** Encuadra [bounds] por completo (si la vista aún no tiene tamaño, se aplica en cuanto lo tenga). */
    fun frame(bounds: Bounds?, keepAngles: Boolean = true) {
        val action = {
            if (bounds != null && bounds.isValid && width > 0 && height > 0) {
                synchronized(camera) { camera.frame(bounds, width.toFloat() / height) }
                requestRender()
            }
        }
        if (width > 0 && height > 0) action() else pendingFrame = action
    }

    fun setPreset(p: CameraPreset, bounds: Bounds?) {
        synchronized(camera) { camera.setPreset(p) }
        frame(bounds)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; downTime = e.eventTime; moved = false; multi = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                if (e.pointerCount >= 2) {
                    lastDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    lastMidX = (e.getX(0) + e.getX(1)) / 2; lastMidY = (e.getY(0) + e.getY(1)) / 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val dist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                    synchronized(camera) {
                        if (lastDist > 10f && dist > 10f) camera.zoom(dist / lastDist)
                        camera.pan(mx - lastMidX, my - lastMidY, height)
                    }
                    lastDist = dist; lastMidX = mx; lastMidY = my
                    moved = true
                    requestRender()
                } else if (!multi) {
                    if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) moved = true
                    if (moved) {
                        synchronized(camera) { camera.orbit(-(e.x - lastX) * 0.4f, (e.y - lastY) * 0.4f) }
                        requestRender()
                    }
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // al quedar un dedo, evita saltos: re-ancla
                val remaining = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(remaining); lastY = e.getY(remaining)
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && !multi && e.eventTime - downTime < 350) {
                    if (e.eventTime - lastTapTime < 280) { lastTapTime = 0; onDoubleTap?.invoke() }
                    else { lastTapTime = e.eventTime; onTap?.invoke(e.x, e.y, width, height); performClick() }
                }
            }
        }
        return true
    }

    var onDoubleTap: (() -> Unit)? = null

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onPause() { super.onPause() }
}

/** Intenta RGB888 + depth 24 (ES3), luego depth 16, luego cualquier configuración RGB: evita "No config chosen" en GPUs limitadas. */
private class DepthFallbackChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val renderable = intArrayOf(0x40, 0x04) // EGL_OPENGL_ES3_BIT_KHR, EGL_OPENGL_ES2_BIT
        for (r in renderable) for (depth in intArrayOf(24, 16)) {
            val attrs = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, depth, 0x3040 /* EGL_RENDERABLE_TYPE */, r, EGL10.EGL_NONE,
            )
            val n = IntArray(1)
            if (egl.eglChooseConfig(display, attrs, null, 0, n) && n[0] > 0) {
                val cfgs = arrayOfNulls<EGLConfig>(n[0])
                if (egl.eglChooseConfig(display, attrs, cfgs, n[0], n) && cfgs[0] != null) return cfgs[0]!!
            }
        }
        val attrs = intArrayOf(EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_NONE)
        val n = IntArray(1)
        egl.eglChooseConfig(display, attrs, null, 0, n)
        val cfgs = arrayOfNulls<EGLConfig>(maxOf(1, n[0]))
        egl.eglChooseConfig(display, attrs, cfgs, cfgs.size, n)
        return cfgs[0] ?: throw IllegalStateException("No hay configuración EGL compatible")
    }
}
