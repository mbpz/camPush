package com.campush.lan

/**
 * Lan-core 公共 API（Lead 冻结；core-dev 只实现，不改签名）。
 * 契约来源：docs/00-conventions.md §8、docs/05-lan-service.md、docs/06-playback-page.md。
 */

enum class LanRoomState { CREATED, LIVE, STOPPING, CLOSED, FAILED }

data class LanConfig(
    val port: Int = 8089,
    val portRange: IntRange = 8089..8099,
    val maxViewers: Int = 3,
    val pinRequired: Boolean = false,
    val pin: String? = null,
    val pageTokenTtlSeconds: Long = 3600,
    val mediaTokenTtlSeconds: Long = 300,
    val bindAddress: String = "0.0.0.0",
    /** P2 D-4：端侧 RTMP ingest 端口；0 表示关闭。 */
    val rtmpPort: Int = 0,
    /** 端侧诊断日志提供者；非空时 `/s/{pageToken}/logs` 返回可下载的文本日志。 */
    val logProvider: (() -> String)? = null,
)

data class LanStatus(
    val state: LanRoomState,
    val lanIp: String,
    val port: Int,
    val viewers: Int,
    val maxViewers: Int,
    val pinRequired: Boolean,
    val mediaProtocols: List<String>,
    val uptimeSeconds: Long,
    val bitrateBps: Long,
    val fps: Int,
    val startedAtMillis: Long,
    /** P2 D-4：端侧 RTMP ingest 累计视频帧数（0 表示未启用/未推流）。 */
    val rtmpFrames: Long = 0,
)

data class H264AccessUnit(
    val nalUnits: List<ByteArray>,
    val ptsUs: Long,
    val isKeyFrame: Boolean,
    val sps: ByteArray? = null,
    val pps: ByteArray? = null,
)

data class AacAccessUnit(
    val frame: ByteArray,
    val ptsUs: Long,
    val isSequenceHeader: Boolean = false,
    val sampleRate: Int = 48000,
    val channels: Int = 1,
)

data class MediaStats(
    val fps: Int = 0,
    val bitrateBps: Long = 0,
    val lastVideoAtMillis: Long = 0,
    val lastAudioAtMillis: Long = 0,
)

interface LanMediaPublisher {
    fun publishJpeg(jpeg: ByteArray)
    fun publishH264(unit: H264AccessUnit)
    fun publishAac(unit: AacAccessUnit)
    fun stats(): MediaStats
    fun requestKeyFrame()

    /** 是否有观看者正在使用 MJPEG；为 false 时客户端可跳过 JPEG 编码以节省 CPU。 */
    fun isMjpegWanted(): Boolean = false
}

enum class LanEvent {
    SERVER_STARTED,
    SERVER_STOPPED,
    VIEWER_JOINED,
    VIEWER_LEFT,
    VIEWER_LIMITED,
    PIN_LOCKED,
    AP_ISOLATION_SUSPECTED,
    ERROR,
}

data class LanEventData(
    val event: LanEvent,
    val message: String? = null,
    val viewers: Int = 0,
    val code: Int? = null,
)

fun interface LanEventListener {
    fun onEvent(event: LanEventData)
}

class LanException(
    val code: Int,
    message: String,
    val userHint: String? = null,
) : Exception(message)

/**
 * 端侧局域网服务。start() 返回唯一播放地址 `http://{lanIp}:{port}/s/{pageToken}`；
 * stop() 必须在 ≤1 s 内关闭端口并销毁 token 密钥。
 */
class LanServer(
    val config: LanConfig,
    private val ipProvider: () -> String?,
    private val listener: LanEventListener? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val impl = LanServerImpl(config, ipProvider, listener, clock)

    val publisher: LanMediaPublisher = impl.publisher

    fun start(): String = impl.start()

    fun stop(): Unit = impl.stop()

    fun status(): LanStatus = impl.status()

    fun updatePin(required: Boolean, pin: String?): Unit = impl.updatePin(required, pin)

    /** P1 端侧埋点计数（事件名 -> 次数）；供诊断日志/局域网观测使用。 */
    fun telemetrySummary(): Map<String, Long> = impl.telemetrySummary()
}
