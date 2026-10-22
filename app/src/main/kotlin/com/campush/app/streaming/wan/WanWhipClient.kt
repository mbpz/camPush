package com.campush.app.streaming.wan

import android.content.Context
import android.util.Log
import com.campush.app.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** P2 T0-A2：Android WHIP 最小客户端（Camera2 720p30 + WebRTC → SRS/WHIP 网关）。 */
class WanWhipClient(private val context: Context) {

    data class State(
        val running: Boolean = false,
        val status: String = "idle",
        val sessionUrl: String? = null,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var peerConnection: PeerConnection? = null
    private var capturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var currentCameraId: String? = null
    private var currentFacingFront = true
    private var muted = false
    private var watermarkEnabled = false
    private var watermarkProcessor: WanWatermarkProcessor? = null
    private var resourceUrl: String? = null
    private var turnUrls: List<String> = emptyList()
    private var turnUsername: String? = null
    private var turnCredential: String? = null

    fun start(
        whipUrl: String,
        turnUrls: List<String> = emptyList(),
        turnUsername: String? = null,
        turnCredential: String? = null,
        watermarkEnabled: Boolean = false,
    ) {
        if (_state.value.running) return
        this.watermarkEnabled = watermarkEnabled
        this.turnUrls = turnUrls
        this.turnUsername = turnUsername
        this.turnCredential = turnCredential
        DiagLog.i("WanWhip", "start url=$whipUrl turn=${turnUrls.size}")
        _state.value = State(running = true, status = "初始化 WebRTC…")
        scope.launch {
            try {
                initFactory()
                val pc = buildPeerConnection()
                peerConnection = pc
                startCamera(pc)
                val offer = createOffer(pc)
                val answer = postOffer(whipUrl, offer)
                pc.setRemoteDescription(SimpleSdpObserver("setRemote"), SessionDescription(SessionDescription.Type.ANSWER, answer))
                _state.value = State(running = true, status = "已发布（WHIP）", sessionUrl = resourceUrl)
                DiagLog.i("WanWhip", "publish ok resource=$resourceUrl")
            } catch (t: Throwable) {
                Log.w(TAG, "whip start failed", t)
                DiagLog.e("WanWhip", "start failed: ${t.message}")
                _state.value = State(running = false, status = "失败", error = t.message)
                cleanup()
            }
        }
    }

    /** P0：WAN 推流中切换前后摄，复用同一 VideoSource/VideoTrack。 */
    fun switchCamera() {
        if (!_state.value.running) return
        scope.launch {
            runCatching {
                val enumerator = Camera2Enumerator(context)
                val targetFront = !currentFacingFront
                val deviceName = enumerator.deviceNames.firstOrNull {
                    if (targetFront) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
                } ?: enumerator.deviceNames.firstOrNull { it != currentCameraId } ?: return@launch
                val helper = surfaceTextureHelper ?: return@launch
                val source = videoSource ?: return@launch
                runCatching { capturer?.stopCapture() }
                runCatching { capturer?.dispose() }
                val cam = enumerator.createCapturer(deviceName, null) ?: return@launch
                cam.initialize(helper, context, source.capturerObserver)
                cam.startCapture(1280, 720, 30)
                capturer = cam
                currentCameraId = deviceName
                currentFacingFront = enumerator.isFrontFacing(deviceName)
                _state.value = _state.value.copy(status = if (currentFacingFront) "前摄" else "后摄")
                DiagLog.i("WanWhip", "camera switched $deviceName")
            }.onFailure { DiagLog.e("WanWhip", "switch camera failed: ${it.message}") }
        }
    }

    fun setTorch(on: Boolean) {
        val id = currentCameraId ?: return
        if (currentFacingFront) return
        runCatching {
            val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            manager.setTorchMode(id, on)
        }.onFailure { Log.w(TAG, "torch failed", it) }
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        audioTrack?.setEnabled(!muted)
    }

    fun stop() {
        val url = resourceUrl
        cleanup()
        _state.value = State(running = false, status = "已停止")
        if (!url.isNullOrBlank()) {
            scope.launch(Dispatchers.IO) {
                runCatching { deleteResource(url) }
            }
        }
        DiagLog.i("WanWhip", "stopped")
    }

    private fun initFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )
        val egl = EglBase.create()
        eglBase = egl
        val audioModule = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(HybridH264EncoderFactory(egl.eglBaseContext))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(audioModule)
            .createPeerConnectionFactory()
    }

    private fun buildPeerConnection(): PeerConnection {
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                _state.value = _state.value.copy(status = "ICE: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        }
        val iceServers = turnUrls.mapNotNull { url ->
            runCatching {
                PeerConnection.IceServer.builder(url)
                    .setUsername(turnUsername ?: "")
                    .setPassword(turnCredential ?: "")
                    .createIceServer()
            }.getOrNull()
        }
        val pc = factory!!.createPeerConnection(iceServers, observer)
            ?: throw IllegalStateException("createPeerConnection failed")
        return pc
    }

    private fun startCamera(pc: PeerConnection) {
        val enumerator = Camera2Enumerator(context)
        // WAN 原型优先前摄，避免与主界面 CameraX 后摄预览冲突；正式版再支持切换。
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("no camera")
        currentCameraId = deviceName
        currentFacingFront = enumerator.isFrontFacing(deviceName)
        val cam = enumerator.createCapturer(deviceName, null)
            ?: throw IllegalStateException("createCapturer failed")
        capturer = cam
        val helper = SurfaceTextureHelper.create("WanCapture", eglBase?.eglBaseContext)
        surfaceTextureHelper = helper
        val source = factory!!.createVideoSource(cam.isScreencast)
        videoSource = source
        if (watermarkEnabled) {
            val processor = WanWatermarkProcessor("CamPush", true)
            watermarkProcessor = processor
            source.setVideoProcessor(processor)
        }
        cam.initialize(helper, context, source.capturerObserver)
        cam.startCapture(1280, 720, 30)
        DiagLog.i("WanWhip", "camera started ${deviceName} ${cam.isScreencast}")
        val track: VideoTrack = factory!!.createVideoTrack("video0", source)
        videoTrack = track
        pc.addTrack(track, listOf("campush"))

        runCatching {
            val audio = factory!!.createAudioSource(MediaConstraints())
            audioSource = audio
            val track: AudioTrack = factory!!.createAudioTrack("audio0", audio)
            track.setEnabled(!muted)
            audioTrack = track
            pc.addTrack(track, listOf("campush"))
        }.onFailure { Log.w(TAG, "audio track failed", it) }
    }

    private suspend fun createOffer(pc: PeerConnection): String {
        val offer = kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) {
                        cont.resumeWith(Result.failure(IllegalStateException("null offer")))
                        return
                    }
                    pc.setLocalDescription(SimpleSdpObserver("setLocal"), desc)
                    cont.resumeWith(Result.success(desc.description))
                }

                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String?) =
                    cont.resumeWith(Result.failure(IllegalStateException(error ?: "createOffer failed")))
                override fun onSetFailure(error: String?) = Unit
            }, MediaConstraints())
        }
        // 等 ICE candidate 收集完成（非 trickle WHIP）
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) break
            kotlinx.coroutines.delay(150)
        }
        return offer
    }

    private suspend fun postOffer(whipUrl: String, offer: String): String = withContext(Dispatchers.IO) {
        val connection = URL(whipUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/sdp")
            val bytes = offer.toByteArray()
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("WHIP HTTP $code: ${connection.errorStream?.bufferedReader()?.readText()}")
            }
            resourceUrl = connection.getHeaderField("Location")
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private fun deleteResource(url: String) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "DELETE"
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        runCatching { connection.responseCode }
        connection.disconnect()
    }

    private fun cleanup() {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        runCatching { videoTrack?.dispose() }; videoTrack = null
        runCatching { audioTrack?.dispose() }; audioTrack = null
        currentCameraId = null
        watermarkProcessor = null
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { peerConnection?.close() }
        peerConnection = null
        runCatching { factory?.dispose() }
        factory = null
        runCatching { eglBase?.release() }
        eglBase = null
        resourceUrl = null
    }

    private class SimpleSdpObserver(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            DiagLog.w("WanWhip", "$tag create failed: $error")
        }

        override fun onSetFailure(error: String?) {
            DiagLog.w("WanWhip", "$tag set failed: $error")
        }
    }

    companion object {
        private const val TAG = "WanWhipClient"
    }
}
