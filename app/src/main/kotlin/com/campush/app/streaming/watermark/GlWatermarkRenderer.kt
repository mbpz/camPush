package com.campush.app.streaming.watermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * P3：把 Camera2 的 SurfaceTexture 通过 GL 合成水印后写入 MediaRecorder 输入 Surface。
 * 只在 Camera2SurfacePipeline 的 watermark 模式使用；失败由上层回退。
 */
class GlWatermarkRenderer(
    private val output: Surface,
    private val width: Int,
    private val height: Int,
    private val text: String = "CamPush",
    private val debugContext: Context? = null,
) {
    private val thread = HandlerThread("campush-gl-watermark").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var wmProgram = 0
    private var texture = 0
    private var wmTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var input: Surface? = null
    private var running = false

    fun start(): Surface {
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                initEgl()
                input = Surface(surfaceTexture)
                running = true
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return input ?: throw IllegalStateException("GL watermark init failed")
    }

    fun release() {
        handler.post {
            running = false
            runCatching { surfaceTexture?.release() }
            surfaceTexture = null
            runCatching { input?.release() }
            input = null
            if (program != 0) GLES20.glDeleteProgram(program)
            if (wmProgram != 0) GLES20.glDeleteProgram(wmProgram)
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            if (wmTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(wmTexture), 0)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
            thread.quitSafely()
        }
    }

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(display, version, 0, version, 1)
        val attrib = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID：MediaRecorder/MediaCodec 输入 Surface 必需
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attrib, 0, configs, 0, 1, num, 0)
        config = configs[0] ?: error("no EGL config")
        val ctx = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctx, 0)
        window = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        Log.i(TAG, "egl surface=${window != EGL14.EGL_NO_SURFACE} err=${EGL14.eglGetError()}")
        val current = EGL14.eglMakeCurrent(display, window, window, context)
        Log.i(TAG, "egl current=$current err=${EGL14.eglGetError()}")
        check(current)
        program = createExternalProgram()
        wmProgram = createTextureProgram()
        texture = createExternalTexture()
        wmTexture = createWatermarkTexture()
        val st = SurfaceTexture(texture).apply { setDefaultBufferSize(width, height) }
        st.setOnFrameAvailableListener {
            if (!running) return@setOnFrameAvailableListener
            handler.post { render() }
        }
        surfaceTexture = st
    }

    private var renderLogged = false
    private var frameCount = 0
    private var uniformLogged = false
    private fun render() {
        if (!running) return
        if (!renderLogged) { renderLogged = true; Log.i(TAG, "render first frame ${width}x$height") }
        val st = surfaceTexture ?: return
        runCatching { st.updateTexImage() }.onFailure { return }
        EGL14.eglMakeCurrent(display, window, window, context)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val m = FloatArray(16)
        st.getTransformMatrix(m)
        drawExternal(m)
        EGLExt.eglPresentationTimeANDROID(display, window, System.nanoTime())
        EGL14.eglSwapBuffers(display, window)
    }

    private fun drawExternal(matrix: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMatrix"), 1, false, matrix, 0)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val tex = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, FULL.first)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, FULL.second)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wmTexture)
        val wmLoc = GLES20.glGetUniformLocation(program, "uWm")
        val vpLoc = GLES20.glGetUniformLocation(program, "uViewport")
        if (debugContext != null && !uniformLogged) {
            uniformLogged = true
            Log.i(TAG, "uniforms uWm=$wmLoc uViewport=$vpLoc")
        }
        GLES20.glUniform1i(wmLoc, 1)
        GLES20.glUniform2f(vpLoc, width.toFloat(), height.toFloat())
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawWatermark() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(wmProgram)
        val pos = GLES20.glGetAttribLocation(wmProgram, "aPos")
        val tex = GLES20.glGetAttribLocation(wmProgram, "aTex")
        val p = floatArrayOf(0.52f, -0.98f, 0.98f, -0.98f, 0.52f, -0.82f, 0.98f, -0.82f)
        val t = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, p.toBuffer())
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, t.toBuffer())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wmTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(wmProgram, "sTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val err = GLES20.glGetError()
        if (err != 0) Log.w(TAG, "watermark draw glError=$err")
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun createExternalTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun createWatermarkTexture(): Int {
        val bmp = Bitmap.createBitmap(512, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255)
            textSize = 64f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(4f, 0f, 2f, Color.argb(160, 0, 0, 0))
        }
        canvas.drawText(text, 20f, 88f, paint)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return tex[0]
    }

    private fun createExternalProgram(): Int = link(
        """
        attribute vec4 aPos; attribute vec4 aTex; varying vec2 vTex;
        uniform mat4 uMatrix;
        void main(){ gl_Position=aPos; vTex=(uMatrix*aTex).xy; }
        """.trimIndent(),
        """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES sTexture;
        uniform sampler2D uWm;
        uniform vec2 uViewport;
        varying vec2 vTex;
        void main(){
            vec4 base = texture2D(sTexture, vTex);
            float w = 280.0; float h = 70.0;
            float x0 = (uViewport.x - w) * 0.5;
            float y0 = (uViewport.y - h) * 0.5;
            vec2 p = gl_FragCoord.xy;
            if (p.x >= x0 && p.x <= x0 + w && p.y >= y0 && p.y <= y0 + h) {
                vec2 uv = vec2((p.x - x0) / w, (p.y - y0) / h);
                vec4 wm = texture2D(uWm, uv);
                base = mix(base, wm, wm.a * 0.9);
            }
            gl_FragColor = base;
        }
        """.trimIndent(),
    )

    private fun createTextureProgram(): Int = link(
        """
        attribute vec4 aPos; attribute vec4 aTex; varying vec2 vTex;
        uniform mat4 uMatrix;
        void main(){ gl_Position=aPos; vTex=(uMatrix*aTex).xy; }
        """.trimIndent(),
        """
        precision mediump float; uniform sampler2D sTexture; varying vec2 vTex;
        void main(){ gl_FragColor=texture2D(sTexture,vTex); }
        """.trimIndent(),
    )

    private fun link(vsSrc: String, fsSrc: String): Int {
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, vsSrc); GLES20.glCompileShader(it)
            val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] != GLES20.GL_TRUE) Log.e(TAG, "vs compile: ${GLES20.glGetShaderInfoLog(it)}")
        }
        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, fsSrc); GLES20.glCompileShader(it)
            val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] != GLES20.GL_TRUE) Log.e(TAG, "fs compile: ${GLES20.glGetShaderInfoLog(it)}")
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] != GLES20.GL_TRUE) Log.e(TAG, "link failed: ${GLES20.glGetProgramInfoLog(p)}")
        return p
    }

    companion object {
        private const val TAG = "GlWatermark"
        private val FULL = run {
            val p = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f).toBuffer()
            val t = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f).toBuffer()
            p to t
        }
    }
}


private fun FloatArray.toBuffer(): FloatBuffer =
    ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(this@toBuffer); position(0) }
