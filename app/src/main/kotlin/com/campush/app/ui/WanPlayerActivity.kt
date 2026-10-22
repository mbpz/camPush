package com.campush.app.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import com.campush.app.BuildConfig
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** P1：原生 WHEP 播放（WebRTC 收流，WAN 房间）。 */
class WanPlayerActivity : Activity() {
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var status: TextView
    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var pc: PeerConnection? = null
    private var videoTrack: VideoTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        renderer = SurfaceViewRenderer(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        root.addView(renderer)
        status = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
            text = "连接中…"
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply { setMargins(24, 48, 24, 24) })
        setContentView(root)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) { status.text = "缺少 WHEP 地址"; return }
        Thread { connect(url) }.start()
    }

    private fun connect(url: String) {
        runCatching {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                    .createInitializationOptions(),
            )
            val egl = EglBase.create()
            eglBase = egl
            val audio = JavaAudioDeviceModule.builder(this).createAudioDeviceModule()
            val f = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .setAudioDeviceModule(audio)
                .createPeerConnectionFactory()
            factory = f
            runOnUiThread { renderer.init(egl.eglBaseContext, null); renderer.setMirror(false) }
            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            val connection = f.createPeerConnection(config, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    runOnUiThread { status.text = "ICE: $state" }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
                override fun onAddStream(stream: MediaStream?) = Unit
                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        videoTrack = track
                        runOnUiThread {
                            track.addSink(renderer)
                            status.text = "WHEP 播放中"
                        }
                    }
                }
            }) ?: error("createPeerConnection failed")
            pc = connection
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }
            val offer = awaitSdp { observer -> connection.createOffer(observer, constraints) }
            val local = awaitSdp { observer -> connection.setLocalDescription(observer, offer) }
            waitIce(connection)
            val answerSdp = postOffer(url, local.description)
            val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
            awaitSdp { observer -> connection.setRemoteDescription(observer, answer) }
        }.onFailure { e ->
            runOnUiThread { status.text = "播放失败：${e.message}" }
        }
    }

    private fun waitIce(connection: PeerConnection) {
        val latch = CountDownLatch(1)
        // 简化：等待 1.2s 让 host/turn candidate 收集完成（WHEP 非 trickle）后再 POST。
        latch.await(1200, TimeUnit.MILLISECONDS)
    }

    private fun postOffer(url: String, sdp: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/sdp")
            val bytes = sdp.toByteArray()
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("WHEP HTTP $code")
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun awaitSdp(block: (SdpObserver) -> Unit): SessionDescription {
        val latch = CountDownLatch(1)
        var result: SessionDescription? = null
        block(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) { result = sdp; latch.countDown() }
            override fun onCreateFailure(error: String?) { latch.countDown() }
            override fun onSetSuccess() { latch.countDown() }
            override fun onSetFailure(error: String?) { latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
        return result ?: throw IllegalStateException("SDP failed")
    }

    override fun onDestroy() {
        runCatching { videoTrack?.removeSink(renderer) }
        runCatching { pc?.close() }
        runCatching { factory?.dispose() }
        runCatching { renderer.release() }
        runCatching { eglBase?.release() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "whep_url"
        const val EXTRA_API = "whep_api"
    }
}
