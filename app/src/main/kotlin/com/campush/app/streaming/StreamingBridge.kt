package com.campush.app.streaming

import com.campush.app.core.P1Error
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 推流引擎对外状态机（docs/02 §3 / §5.3 客户端会话状态的内部投影）。 */
enum class EnginePhase { IDLE, STARTING, STREAMING, STOPPING, FAILED }

data class EngineSnapshot(
    val phase: EnginePhase = EnginePhase.IDLE,
    val playUrl: String? = null,
    val lanIp: String? = null,
    val port: Int = 8089,
    val actualWidth: Int = 0,
    val actualHeight: Int = 0,
    val viewers: Int = 0,
    val maxViewers: Int = 3,
    val bitrateKbps: Int = 0,
    val fps: Int = 0,
    val networkLabel: String = "未知",
)

/**
 * Controller（UI 进程内单例）与前台服务/推流引擎之间的桥。
 * 所有字段线程安全；UI 侧只在主线程收集 StateFlow。
 */
object StreamingBridge {

    private val _snapshot = MutableStateFlow(EngineSnapshot())
    val snapshot: StateFlow<EngineSnapshot> = _snapshot.asStateFlow()

    private val _errors = MutableSharedFlow<P1Error>(extraBufferCapacity = 16)
    val errors: SharedFlow<P1Error> = _errors.asSharedFlow()

    private val _warnings = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val warnings: SharedFlow<String> = _warnings.asSharedFlow()

    @Volatile
    var engine: LanStreamEngine? = null
        internal set

    fun update(transform: (EngineSnapshot) -> EngineSnapshot) {
        _snapshot.update(transform)
    }

    fun set(snapshot: EngineSnapshot) {
        _snapshot.value = snapshot
    }

    fun emitError(error: P1Error) {
        _errors.tryEmit(error)
    }

    fun emitWarning(text: String) {
        _warnings.tryEmit(text)
    }

    fun reset(networkLabel: String = _snapshot.value.networkLabel) {
        _snapshot.value = EngineSnapshot(networkLabel = networkLabel)
    }
}
