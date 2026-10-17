package com.wallper.live.playback

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Composites up to two video decoder outputs (external OES textures):
 *  - cover-crops the video to any surface aspect (phone, foldable inner screen, tablet),
 *  - positions the crop by a user-chosen focus point plus launcher parallax,
 *  - cross-fades the back layer over the front one to hide the loop seam,
 *  - dithers the output so 10-bit skies do not band on 8-bit panels.
 *
 * GL work happens on the GLSurfaceView thread; [target] is touched on the main thread.
 */
class LoopRenderer(private val requestRender: () -> Unit) : GLSurfaceView.Renderer {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var front = 0
    @Volatile private var mix = 0f
    @Volatile private var videoAspect = 16f / 9f
    @Volatile var focusX = 0.5f
    @Volatile var focusY = 0.5f
    @Volatile var parallaxEnabled = true
    @Volatile private var parallaxTarget = 0f
    private var parallax = 0f

    private var program = 0
    private var uTex = IntArray(2)
    private var uSt = IntArray(2)
    private var uScale = 0
    private var uOffset = 0
    private var uMix = 0
    private var width = 1
    private var height = 1

    private val textures = IntArray(2)
    private val surfaceTextures = arrayOfNulls<SurfaceTexture>(2)
    private val frameAvailable = Array(2) { AtomicBoolean(false) }
    private val hasFrame = BooleanArray(2)
    private val matrices = Array(2) { FloatArray(16) }

    private var surfaces: List<Surface>? = null

    /** The [VideoTarget] facade used by sessions (main thread). */
    val target: VideoTarget = object : VideoTarget {
        override val surfaces: List<Surface>? get() = this@LoopRenderer.surfaces
        override var onSurfacesChanged: (() -> Unit)? = null

        override fun present(front: Int, mix: Float) {
            this@LoopRenderer.front = front
            this@LoopRenderer.mix = mix
            requestRender()
        }

        override fun setVideoSize(width: Int, height: Int, pixelRatio: Float) {
            if (width > 0 && height > 0) {
                videoAspect = width * pixelRatio / height
                requestRender()
            }
        }
    }

    /** Launcher page offset, -0.5 (first page) .. 0.5 (last page). */
    fun setParallax(offset: Float) {
        parallaxTarget = offset.coerceIn(-0.5f, 0.5f)
        requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        releaseTextures()
        program = buildProgram()
        uTex[0] = GLES30.glGetUniformLocation(program, "uTex0")
        uTex[1] = GLES30.glGetUniformLocation(program, "uTex1")
        uSt[0] = GLES30.glGetUniformLocation(program, "uSt0")
        uSt[1] = GLES30.glGetUniformLocation(program, "uSt1")
        uScale = GLES30.glGetUniformLocation(program, "uScale")
        uOffset = GLES30.glGetUniformLocation(program, "uOffset")
        uMix = GLES30.glGetUniformLocation(program, "uMix")

        GLES30.glGenTextures(2, textures, 0)
        val created = ArrayList<Surface>(2)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[i])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val st = SurfaceTexture(textures[i])
            st.setOnFrameAvailableListener {
                frameAvailable[i].set(true)
                requestRender()
            }
            surfaceTextures[i] = st
            hasFrame[i] = false
            created += Surface(st)
        }
        main.post {
            val previous = surfaces
            surfaces = created
            target.onSurfacesChanged?.invoke()
            previous?.forEach { it.release() }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = maxOf(width, 1)
        this.height = maxOf(height, 1)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        for (i in 0..1) {
            val st = surfaceTextures[i] ?: continue
            if (frameAvailable[i].getAndSet(false)) {
                try {
                    st.updateTexImage()
                    st.getTransformMatrix(matrices[i])
                    hasFrame[i] = true
                } catch (e: RuntimeException) {
                    Log.w(TAG, "updateTexImage failed", e)
                }
            }
        }

        val enabledTarget = if (parallaxEnabled) parallaxTarget else 0f
        parallax += (enabledTarget - parallax) * 0.18f
        if (abs(enabledTarget - parallax) > 0.0005f) requestRender() else parallax = enabledTarget

        val f = front
        val b = 1 - f
        if (program == 0 || !hasFrame[f]) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        // Cover crop in normalised video coordinates.
        val surfaceAspect = width.toFloat() / height
        val scaleX: Float
        val scaleY: Float
        if (videoAspect > surfaceAspect) {
            scaleX = surfaceAspect / videoAspect
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = videoAspect / surfaceAspect
        }
        // Parallax pans across the spare width (most of it on portrait phones).
        val fx = (focusX + parallax * PARALLAX_RANGE).coerceIn(0f, 1f)
        val fy = 1f - focusY // GL texture space grows upwards
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uScale, scaleX, scaleY)
        GLES30.glUniform2f(uOffset, (1f - scaleX) * fx, (1f - scaleY) * fy)
        GLES30.glUniform1f(uMix, if (hasFrame[b]) mix else 0f)

        bindLayer(0, f)
        bindLayer(1, b)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun bindLayer(unit: Int, index: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[index])
        GLES30.glUniform1i(uTex[unit], unit)
        GLES30.glUniformMatrix4fv(uSt[unit], 1, false, matrices[index], 0)
    }

    /** Releases decoder surfaces; call after the session has been detached. */
    fun release() {
        main.post {
            surfaces?.forEach { it.release() }
            surfaces = null
        }
        releaseTextures()
    }

    private fun releaseTextures() {
        for (i in 0..1) {
            surfaceTextures[i]?.setOnFrameAvailableListener(null)
            surfaceTextures[i]?.release()
            surfaceTextures[i] = null
            hasFrame[i] = false
            frameAvailable[i].set(false)
        }
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] == 0) {
            Log.e(TAG, "link: " + GLES30.glGetProgramInfoLog(p))
            GLES30.glDeleteProgram(p)
            return 0
        }
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "compile: " + GLES30.glGetShaderInfoLog(shader))
        return shader
    }

    private companion object {
        const val TAG = "LoopRenderer"
        const val PARALLAX_RANGE = 0.6f

        // Full-screen triangle generated from gl_VertexID: no vertex buffers needed.
        const val VERTEX = """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex0;
uniform samplerExternalOES uTex1;
uniform mat4 uSt0;
uniform mat4 uSt1;
uniform vec2 uScale;
uniform vec2 uOffset;
uniform float uMix;
in vec2 vUv;
out vec4 fragColor;

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec4 uv = vec4(vUv * uScale + uOffset, 0.0, 1.0);
    vec3 color = texture(uTex0, (uSt0 * uv).xy).rgb;
    if (uMix > 0.0) {
        vec3 next = texture(uTex1, (uSt1 * uv).xy).rgb;
        color = mix(color, next, smoothstep(0.0, 1.0, uMix));
    }
    // Triangular dither: removes banding when 10-bit video meets an 8-bit surface.
    float d = hash(gl_FragCoord.xy) + hash(gl_FragCoord.yx + 19.19) - 1.0;
    fragColor = vec4(color + d / 255.0, 1.0);
}
"""
    }
}
