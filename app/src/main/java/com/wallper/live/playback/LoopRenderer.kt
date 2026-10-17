package com.wallper.live.playback

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.wallper.live.core.PanelKind
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Composites up to two video decoder outputs (external OES textures):
 *  - cover-crops the video to any surface aspect (phone, foldable inner screen, tablet),
 *  - positions the crop by a user-chosen focus point plus launcher parallax,
 *  - cross-fades the back layer over the front one to hide the loop seam,
 *  - dithers the output so 10-bit skies do not band on 8-bit panels,
 *  - optionally "defrosts" the picture when the wallpaper appears (wake, unlock,
 *    unfold): it starts as frosted glass and resolves to the sharp video,
 *  - on foldables, follows the hinge like iPhone Duo: each half of the inner panel is
 *    a pane of frosted glass hinged at the crease (blurrier and darker towards its far
 *    edge, slightly bent in perspective) that clears as the phone opens flat; the cover
 *    panel frosts over during the first degrees of opening. Only while the hinge moves:
 *    at rest the picture is sharp at any angle (see [HingeFrost]).
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

    @Volatile var revealEnabled = false
        set(value) {
            field = value
            hingeFrost.enabled = value
        }

    /** Hinge-driven glass of foldable panels. */
    private val hingeFrost = HingeFrost()

    /**
     * Main-thread callback: true once a video picture is on the surface, false while it
     * shows black (no frame yet). Lets the UI keep a still above it instead of black.
     */
    @Volatile var onPictureChanged: ((Boolean) -> Unit)? = null
    private var showingPicture = false

    /** Panel this renderer draws on; only foldable panels react to the hinge. */
    var panel: PanelKind
        get() = hingeFrost.panel
        set(value) {
            hingeFrost.panel = value
        }

    /** True when the crease runs vertically on screen (the natural orientation). */
    @Volatile var creaseVertical = true

    /**
     * Coarse sensors (Samsung reports only 0/90/180°) need a softer spring so the
     * jumps between stops turn into one smooth motion.
     */
    var hingeCoarse: Boolean
        get() = hingeFrost.coarse
        set(value) {
            hingeFrost.coarse = value
        }

    @Volatile private var revealRequested = false
    private var revealStartNanos = 0L
    private var frost = 0f

    private var program = 0
    private var frostProgram = 0
    private var uFrostTex = 0
    private var uFrostAmount = 0
    private var uFrostTexel = 0
    private var uFrostFold = 0
    private var uFrostCrease = 0
    private val frostFbo = IntArray(1)
    private val frostTex = IntArray(1)
    private var frostWidth = 0
    private var frostHeight = 0
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

    /**
     * Plays the frosted-glass reveal: the next frames start fully frosted and resolve
     * to the sharp picture. Ignored when [revealEnabled] is off.
     */
    fun reveal() {
        if (!revealEnabled) return
        revealRequested = true
        requestRender()
    }

    /** Jumps straight to [degrees] (e.g. when a panel lights up mid-unfold). */
    fun snapHinge(degrees: Float) {
        hingeFrost.snap(degrees, System.nanoTime())
        requestRender()
    }

    /** A hinge sensor reading (180 = flat/open, 0 = closed). */
    fun setHingeAngle(degrees: Float) {
        hingeFrost.onHinge(degrees, System.nanoTime())
        requestRender()
    }

    /** Launcher page offset, -0.5 (first page) .. 0.5 (last page). */
    fun setParallax(offset: Float) {
        parallaxTarget = offset.coerceIn(-0.5f, 0.5f)
        requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        releaseTextures()
        program = buildProgram(FRAGMENT)
        uTex[0] = GLES30.glGetUniformLocation(program, "uTex0")
        uTex[1] = GLES30.glGetUniformLocation(program, "uTex1")
        uSt[0] = GLES30.glGetUniformLocation(program, "uSt0")
        uSt[1] = GLES30.glGetUniformLocation(program, "uSt1")
        uScale = GLES30.glGetUniformLocation(program, "uScale")
        uOffset = GLES30.glGetUniformLocation(program, "uOffset")
        uMix = GLES30.glGetUniformLocation(program, "uMix")

        frostProgram = buildProgram(FROST_FRAGMENT)
        uFrostTex = GLES30.glGetUniformLocation(frostProgram, "uFrostTex")
        uFrostAmount = GLES30.glGetUniformLocation(frostProgram, "uAmount")
        uFrostTexel = GLES30.glGetUniformLocation(frostProgram, "uTexel")
        uFrostFold = GLES30.glGetUniformLocation(frostProgram, "uFold")
        uFrostCrease = GLES30.glGetUniformLocation(frostProgram, "uCrease")
        frostWidth = 0
        frostHeight = 0

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
            reportPicture(false)
            return
        }
        reportPicture(true)

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
        updateFrost(hasFrame[f])
        val fold = updateFold()
        if ((frost > 0f || fold > FOLD_EPSILON) && frostProgram != 0 && ensureFrostTarget()) {
            // Pass 1: the sharp composite into a mip-mapped texture.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frostFbo[0])
            GLES30.glViewport(0, 0, frostWidth, frostHeight)
            drawVideo(f, b, scaleX, scaleY, fx, fy)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frostTex[0])
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            // Pass 2: sample a blurrier mip level the more frosted the glass is.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, width, height)
            GLES30.glUseProgram(frostProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frostTex[0])
            GLES30.glUniform1i(uFrostTex, 0)
            GLES30.glUniform1f(uFrostAmount, frost)
            GLES30.glUniform2f(uFrostTexel, 1f / frostWidth, 1f / frostHeight)
            GLES30.glUniform1f(uFrostFold, fold)
            // x: 0 = uniform frost (cover), 1 = hinged halves (inner); y: crease vertical?
            GLES30.glUniform2f(
                uFrostCrease,
                if (panel == PanelKind.Inner) 1f else 0f,
                if (creaseVertical) 1f else 0f,
            )
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            if (frost > 0f) requestRender()
        } else {
            drawVideo(f, b, scaleX, scaleY, fx, fy)
        }
    }

    private fun reportPicture(showing: Boolean) {
        if (showing == showingPicture) return
        showingPicture = showing
        main.post { onPictureChanged?.invoke(showing) }
    }

    private fun drawVideo(f: Int, b: Int, scaleX: Float, scaleY: Float, fx: Float, fy: Float) {
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uScale, scaleX, scaleY)
        GLES30.glUniform2f(uOffset, (1f - scaleX) * fx, (1f - scaleY) * fy)
        GLES30.glUniform1f(uMix, if (hasFrame[b]) mix else 0f)

        bindLayer(0, f)
        bindLayer(1, b)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /** Fold amount 0..1 of the hinge-driven glass; keeps frames coming while it changes. */
    private fun updateFold(): Float {
        val fold = hingeFrost.update(System.nanoTime())
        if (hingeFrost.animating) requestRender()
        return fold
    }

    /** Advances the reveal animation; it starts once a video frame is on screen. */
    private fun updateFrost(hasPicture: Boolean) {
        if (revealRequested) {
            revealRequested = false
            frost = 1f
            revealStartNanos = 0L
        }
        if (frost <= 0f) return
        if (!hasPicture) return
        val now = System.nanoTime()
        if (revealStartNanos == 0L) revealStartNanos = now + REVEAL_HOLD_NANOS
        val t = ((now - revealStartNanos).toFloat() / REVEAL_NANOS).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t) // ease-out cubic
        frost = if (t >= 1f) 0f else 1f - eased
    }

    private fun ensureFrostTarget(): Boolean {
        if (frostWidth == width && frostHeight == height && frostFbo[0] != 0) return true
        if (frostFbo[0] != 0) {
            GLES30.glDeleteFramebuffers(1, frostFbo, 0)
            GLES30.glDeleteTextures(1, frostTex, 0)
        }
        GLES30.glGenTextures(1, frostTex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frostTex[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glGenFramebuffers(1, frostFbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frostFbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, frostTex[0], 0,
        )
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (!complete) {
            Log.w(TAG, "frost framebuffer incomplete")
            return false
        }
        frostWidth = width
        frostHeight = height
        return true
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

    private fun buildProgram(fragment: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
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
        const val REVEAL_NANOS = 950_000_000f
        const val REVEAL_HOLD_NANOS = 120_000_000L
        const val FOLD_EPSILON = 0.002f

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

        // Frosted glass: a blurred mip level, a milky veil and a slight zoom that
        // settles as the glass clears. With uCrease.x = 1 each half of the inner panel
        // is a hinged pane: frost and shade grow towards its far edge, which is also
        // bent slightly in perspective (iPhone Duo style).
        const val FROST_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uFrostTex;
uniform float uAmount;
uniform float uFold;
uniform vec2 uCrease;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 fragColor;

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

const vec2 POISSON[12] = vec2[12](
    vec2(-0.326, -0.406), vec2(-0.840, -0.074), vec2(-0.696, 0.457), vec2(-0.203, 0.621),
    vec2(0.962, -0.195), vec2(0.473, -0.480), vec2(0.519, 0.767), vec2(0.185, -0.893),
    vec2(0.507, 0.064), vec2(0.896, 0.412), vec2(-0.322, -0.933), vec2(-0.792, -0.598)
);

void main() {
    vec2 uv = (vUv - 0.5) / (1.0 + 0.07 * uAmount) + 0.5;

    float across = uCrease.y > 0.5 ? vUv.x : vUv.y;   // coordinate across the crease
    float edge = abs(across - 0.5) * 2.0;              // 0 at the crease, 1 at the far edge
    float pane = uCrease.x > 0.5 ? mix(0.3, 1.0, edge) : 1.0;
    float fold = uFold * pane;
    if (uCrease.x > 0.5) {
        // Perspective: the far edge of a tilted pane looks shorter.
        float squeeze = 1.0 - 0.09 * uFold * edge;
        if (uCrease.y > 0.5) uv.y = (uv.y - 0.5) * (1.0 / squeeze) + 0.5;
        else uv.x = (uv.x - 0.5) * (1.0 / squeeze) + 0.5;
    }

    float amount = max(uAmount, fold);
    // Fractional mip level + a 12-tap Poisson disk rotated per pixel: a smooth, round
    // blur without the blockiness of plain mip sampling, continuous as amount changes.
    float lod = amount * 5.5;
    float radius = exp2(lod) * 1.25;
    float angle = hash(gl_FragCoord.xy) * 6.2831853;
    mat2 spin = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
    vec3 c = textureLod(uFrostTex, uv, lod).rgb;
    float weight = 1.0;
    for (int i = 0; i < 12; i++) {
        vec2 o = spin * POISSON[i] * radius * uTexel;
        float w = 1.0 - 0.5 * length(POISSON[i]);
        c += textureLod(uFrostTex, uv + o, lod).rgb * w;
        weight += w;
    }
    c /= weight;
    // Milky veil + gentle grain sell the "glass" rather than a plain blur.
    c = mix(c, vec3(0.92), 0.16 * uAmount + 0.10 * fold);
    c *= 1.0 - 0.42 * fold;
    c += (hash(gl_FragCoord.xy) - 0.5) * 0.035 * amount;
    float d = hash(gl_FragCoord.yx + 7.7) + hash(gl_FragCoord.xy + 3.3) - 1.0;
    fragColor = vec4(c + d / 255.0, 1.0);
}
"""
    }
}
