package com.campush.app.camera.dual

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * P2 T0-A3 实验：双摄 PiP OpenGL 合成预览（单 SurfaceView 输出）。
 *
 * 仅原型验证：同时打开前后摄，用 GL 把前摄缩小叠到后摄右下角；
 * 不接入 MediaRecorder/RTMP，正式 PiP 编码需在 D-3 门槛通过后接入管线。
 */
class DualPipActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var renderer: PipRenderer? = null
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var backDevice: CameraDevice? = null
    private var frontDevice: CameraDevice? = null
    private var backSession: CameraCaptureSession? = null
    private var frontSession: CameraCaptureSession? = null
    private var backSurface: Surface? = null
    private var frontSurface: Surface? = null
    private var compositionMode = 0
    private var swapped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            text = "双摄 PiP：等待相机权限…"
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply { setMargins(24, 48, 24, 24) })
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val modeButton = Button(this).apply {
            text = "合成：PiP"
            setOnClickListener {
                compositionMode = (compositionMode + 1) % 3
                text = when (compositionMode) {
                    0 -> "合成：PiP"
                    1 -> "合成：左右分屏"
                    else -> "合成：上下分屏"
                }
                renderer?.let { it.mode = compositionMode; it.swapped = swapped; it.requestRenderNow() }
            }
        }
        val swapButton = Button(this).apply {
            text = "交换主次"
            setOnClickListener {
                swapped = !swapped
                renderer?.let { it.swapped = swapped; it.requestRenderNow() }
            }
        }
        controls.addView(modeButton)
        controls.addView(swapButton)
        root.addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER or Gravity.BOTTOM).apply { setMargins(0, 0, 0, 48) })
        setContentView(root)
        surfaceView.holder.addCallback(this)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) status.text = "未授予相机权限"
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        thread = HandlerThread("dual-pip-gl").also { it.start() }
        handler = Handler(thread!!.looper)
        renderer = PipRenderer(holder.surface, { msg -> runOnUiThread { status.text = msg } })
        handler!!.post { renderer?.init() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        closeCameras()
        handler?.post { renderer?.release() }
        thread?.quitSafely()
        thread = null
        renderer = null
    }

    private fun startCameras() {
        val r = renderer ?: return
        if (!r.hasTextures()) return
        backSurface = Surface(r.backTexture)
        frontSurface = Surface(r.frontTexture)
        open("back") { id, surface ->
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    backDevice = camera
                    createSession(camera, surface) { backSession = it }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "back camera error $error")
                    runOnUiThread { status.text = "后摄打开失败：$error（设备可能不支持并发双摄）" }
                    camera.close()
                }
            }, handler!!)
        }
        open("front") { id, surface ->
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    frontDevice = camera
                    createSession(camera, surface) { frontSession = it }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "front camera error $error")
                    runOnUiThread { status.text = "前摄打开失败：$error（设备可能不支持并发双摄）" }
                    camera.close()
                }
            }, handler!!)
        }
    }

    private fun open(which: String, block: (String, Surface) -> Unit) {
        val target = if (which == "back") CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        val id = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == target
        } ?: return
        block(id, if (which == "back") backSurface!! else frontSurface!!)
    }

    private fun createSession(device: CameraDevice, surface: Surface, onReady: (CameraCaptureSession) -> Unit) {
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                onReady(session)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }.build()
                session.setRepeatingRequest(request, null, handler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.w(TAG, "session configure failed")
                runOnUiThread { status.text = "双摄会话配置失败" }
            }
        }, handler)
    }

    private fun closeCameras() {
        runCatching { backSession?.close() }
        runCatching { frontSession?.close() }
        runCatching { backDevice?.close() }
        runCatching { frontDevice?.close() }
        backSession = null; frontSession = null; backDevice = null; frontDevice = null
        backSurface?.release(); frontSurface?.release()
        backSurface = null; frontSurface = null
    }

    private inner class PipRenderer(
        private val surface: Surface,
        private val statusSink: (String) -> Unit,
    ) {
        private var egl: EglCore? = null
        private var window: EGLSurface? = null
        private var program = 0
        private var mainTexture = 0
        private var pipTexture = 0
        lateinit var backTexture: android.graphics.SurfaceTexture
        lateinit var frontTexture: android.graphics.SurfaceTexture
        private var running = false
        private var frames = 0L
        private var startedAt = 0L
        @Volatile var mode: Int = 0
        @Volatile var swapped: Boolean = false

        fun hasTextures(): Boolean = ::backTexture.isInitialized && ::frontTexture.isInitialized

        fun init() {
            val core = EglCore()
            egl = core
            window = core.createWindowSurface(surface)
            core.makeCurrent(window!!)
            program = createProgram()
            mainTexture = createExternalTexture()
            pipTexture = createExternalTexture()
            backTexture = android.graphics.SurfaceTexture(mainTexture).apply { setDefaultBufferSize(640, 480) }
            frontTexture = android.graphics.SurfaceTexture(pipTexture).apply { setDefaultBufferSize(320, 240) }
            backTexture.setOnFrameAvailableListener { requestRender() }
            frontTexture.setOnFrameAvailableListener { requestRender() }
            startedAt = System.currentTimeMillis()
            running = true
            handler?.post { startCameras() }
            statusSink("双摄 PiP 运行中（后摄主画面 + 前摄 PiP）")
            render()
        }

        private fun requestRender() {
            if (!running) return
            handler?.post { render() }
        }

        fun requestRenderNow() = requestRender()

        fun render() {
            val core = egl ?: return
            if (window == null || !running) return
            core.makeCurrent(window!!)
            runCatching { backTexture.updateTexImage() }
            runCatching { frontTexture.updateTexImage() }
            GLES20.glViewport(0, 0, surfaceView.width, surfaceView.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            when (mode) {
                0 -> {
                    drawTexture(if (swapped) pipTexture else mainTexture, fullScreen = true, useFrontMatrix = swapped)
                    drawTexture(if (swapped) mainTexture else pipTexture, fullScreen = false, useFrontMatrix = !swapped)
                }
                1 -> {
                    val w = surfaceView.width.coerceAtLeast(1)
                    val h = surfaceView.height.coerceAtLeast(1)
                    GLES20.glViewport(0, 0, w / 2, h)
                    drawTexture(if (swapped) pipTexture else mainTexture, fullScreen = true, useFrontMatrix = swapped)
                    GLES20.glViewport(w / 2, 0, w - w / 2, h)
                    drawTexture(if (swapped) mainTexture else pipTexture, fullScreen = true, useFrontMatrix = !swapped)
                }
                else -> {
                    val w = surfaceView.width.coerceAtLeast(1)
                    val h = surfaceView.height.coerceAtLeast(1)
                    GLES20.glViewport(0, h / 2, w, h - h / 2)
                    drawTexture(if (swapped) pipTexture else mainTexture, fullScreen = true, useFrontMatrix = swapped)
                    GLES20.glViewport(0, 0, w, h / 2)
                    drawTexture(if (swapped) mainTexture else pipTexture, fullScreen = true, useFrontMatrix = !swapped)
                }
            }
            core.swapBuffers(window!!)
            frames++
            if (frames % 30L == 0L) {
                val sec = ((System.currentTimeMillis() - startedAt) / 1000.0).coerceAtLeast(1.0)
                val thermal = if (Build.VERSION.SDK_INT >= 29) {
                    (getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
                } else -1
                statusSink("双摄 PiP · ${(frames / sec).toInt()} fps · thermal=$thermal")
            }
        }

        private fun drawTexture(texture: Int, fullScreen: Boolean, useFrontMatrix: Boolean = !fullScreen) {
            GLES20.glUseProgram(program)
            val pos = GLES20.glGetAttribLocation(program, "aPos")
            val tex = GLES20.glGetAttribLocation(program, "aTex")
            val matrix = FloatArray(16)
            if (useFrontMatrix) frontTexture.getTransformMatrix(matrix) else backTexture.getTransformMatrix(matrix)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMatrix"), 1, false, matrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
            val coords = if (fullScreen) FULL else PIP
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, coords.first)
            GLES20.glEnableVertexAttribArray(tex)
            GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, coords.second)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() {
            running = false
            runCatching { backTexture.release() }
            runCatching { frontTexture.release() }
            if (program != 0) GLES20.glDeleteProgram(program)
            egl?.releaseSurface(window)
            egl?.release()
            egl = null
            window = null
        }
    }

    private class EglCore {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var config: EGLConfig? = null

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)
            val attrib = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(display, attrib, 0, configs, 0, 1, num, 0)
            config = configs[0]
            val ctx = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctx, 0)
        }

        fun createWindowSurface(surface: Surface): EGLSurface {
            val attrib = intArrayOf(EGL14.EGL_NONE)
            return EGL14.eglCreateWindowSurface(display, config, surface, attrib, 0)
        }

        fun makeCurrent(surface: EGLSurface) = EGL14.eglMakeCurrent(display, surface, surface, context)
        fun swapBuffers(surface: EGLSurface) = EGL14.eglSwapBuffers(display, surface)
        fun releaseSurface(surface: EGLSurface?) { if (surface != null) EGL14.eglDestroySurface(display, surface) }
        fun release() {
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
    }

    companion object {
        private const val TAG = "DualPip"
        private val FULL: Pair<FloatBuffer, FloatBuffer> = buffers(
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
        )
        private val PIP: Pair<FloatBuffer, FloatBuffer> = buffers(
            floatArrayOf(0.62f, -0.95f, 0.98f, -0.95f, 0.62f, -0.55f, 0.98f, -0.55f),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
        )

        private fun buffers(pos: FloatArray, tex: FloatArray): Pair<FloatBuffer, FloatBuffer> =
            pos.toBuffer() to tex.toBuffer()

        private fun FloatArray.toBuffer(): FloatBuffer =
            ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(this@toBuffer); position(0) }

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

        private fun createProgram(): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, """
                attribute vec4 aPos; attribute vec4 aTex; varying vec2 vTex;
                uniform mat4 uMatrix;
                void main(){ gl_Position=aPos; vTex=(uMatrix*aTex).xy; }
            """.trimIndent())
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES sTexture; varying vec2 vTex;
                void main(){ gl_FragColor=texture2D(sTexture,vTex); }
            """.trimIndent())
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p)
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            return s
        }
    }
}
