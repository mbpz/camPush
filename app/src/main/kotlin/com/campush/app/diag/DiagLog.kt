package com.campush.app.diag

import android.content.Context
import android.os.Build
import android.util.Log
import com.campush.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 进程内诊断日志环形缓冲：供局域网 `…/logs` 页面下载，不依赖 adb/logcat。
 *
 * 约束：日志只保留进程内最近 [MAX_LINES] 行 + 上一次崩溃栈；不得记录 token/PIN 等敏感信息。
 */
object DiagLog {

    private const val MAX_LINES = 800
    private const val TAG = "Campush"

    private val lines = ArrayDeque<String>(MAX_LINES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile private var header: String = "Campush 诊断日志（未初始化）"
    @Volatile private var logDir: File? = null

    fun init(context: Context) {
        val app = context.applicationContext
        header = buildString {
            appendLine("=== Campush 诊断日志 ===")
            appendLine("生成时间: ${stampFormat.format(Date())}")
            appendLine("应用: ${BuildConfig.APPLICATION_ID} version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) debug=${BuildConfig.DEBUG}")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("线程: 日志为 App 内环形缓冲，最多 $MAX_LINES 行；不含 token/PIN")
            appendLine("========================")
        }
        logDir = File(app.filesDir, "diag").apply { runCatching { mkdirs() } }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                e("Crash", "未捕获异常 thread=${thread.name}: ${error.javaClass.name}: ${error.message}", error)
                writeCrashSnapshot(error)
            }
            previous?.uncaughtException(thread, error)
        }
        i("App", "DiagLog initialized")
    }

    fun i(tag: String, message: String) = append("I", tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        append("W", tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        append("E", tag, message, throwable)

    private fun append(level: String, tag: String, message: String, throwable: Throwable?) {
        val line = buildString {
            append(timeFormat.format(Date()))
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(message)
            if (throwable != null) {
                append(" | ")
                append(throwable.javaClass.simpleName)
                append(": ")
                append(throwable.message)
                throwable.stackTrace.take(6).forEach { frame ->
                    append("\n    at ")
                    append(frame)
                }
            }
        }
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
        when (level) {
            "E" -> Log.e(TAG, message, throwable)
            "W" -> Log.w(TAG, message, throwable)
            else -> Log.i(TAG, message)
        }
    }

    /** 供局域网 `/s/{pageToken}/logs` 下载：设备信息 + App 状态 + 最近日志。 */
    fun snapshot(extra: String? = null): String {
        val body = synchronized(lines) { lines.joinToString("\n") }
        val crash = logDir?.let { File(it, "last-crash.txt") }
            ?.takeIf { it.exists() }
            ?.let { runCatching { it.readText() }.getOrNull() }
        return buildString {
            appendLine(header)
            if (!crash.isNullOrBlank()) {
                appendLine()
                appendLine("=== 上一次崩溃 ===")
                appendLine(crash)
            }
            appendLine()
            appendLine("=== 当前状态 ===")
            appendLine(extra ?: "(no state)")
            appendLine()
            appendLine("=== 最近日志（${body.lineSequence().count()} 行）===")
            appendLine(body)
            appendLine()
            appendLine("=== 日志结束 ===")
        }
    }

    private fun writeCrashSnapshot(error: Throwable) {
        val dir = logDir ?: return
        val text = snapshot("crash=${error.javaClass.name}: ${error.message}")
        runCatching { File(dir, "last-crash.txt").writeText(text) }
    }
}
