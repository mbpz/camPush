package com.campush.app.diag

import android.content.Context
import android.os.Build
import android.util.Log
import com.campush.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.concurrent.Executors
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
    @Volatile private var logFile: File? = null
    private val fileLock = Any()
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "campush-log-io").apply { isDaemon = true }
    }

    fun init(context: Context) {
        val app = context.applicationContext
        header = buildString {
            appendLine("=== Campush 诊断日志 ===")
            appendLine("生成时间: ${stampFormat.format(Date())}")
            appendLine("应用: ${BuildConfig.APPLICATION_ID} version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) debug=${BuildConfig.DEBUG}")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("日志: 累计落盘（2×2MB 轮转），本文件为全部记录；不含 token/PIN")
            appendLine("========================")
        }
        logDir = File(app.filesDir, "diag").apply { runCatching { mkdirs() } }
        logFile = File(logDir, "campush.log")
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
        persist(line)
        when (level) {
            "E" -> Log.e(TAG, message, throwable)
            "W" -> Log.w(TAG, message, throwable)
            else -> Log.i(TAG, message)
        }
    }

    /** 供局域网日志服务下载：设备信息 + 崩溃 + 累计落盘日志 + 当前状态。 */
    fun downloadText(extra: String? = null): String {
        val history = synchronized(fileLock) {
            buildString {
                listOf("campush.log.1", "campush.log").forEach { name ->
                    val f = logDir?.let { File(it, name) } ?: return@forEach
                    if (f.exists()) {
                        runCatching { append(f.readText()) }
                    }
                }
            }
        }
        val body = if (history.isNotBlank()) {
            history
        } else {
            synchronized(lines) { lines.joinToString("\n") }
        }
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
            appendLine("=== 累计日志（落盘，最多 2×2MB）===")
            appendLine(body)
            appendLine()
            appendLine("=== 日志结束 ===")
        }
    }

    /** 兼容旧入口：`/s/{token}/logs` 使用同一份累计日志。 */
    fun snapshot(extra: String? = null): String = downloadText(extra)

    /** 把单行日志异步追加到文件；超过 2MB 轮转一份历史文件。 */
    private fun persist(line: String) {
        val file = logFile ?: return
        ioExecutor.execute {
            synchronized(fileLock) {
                runCatching {
                    if (file.length() > 2L * 1024 * 1024) {
                        val old = File(file.parentFile, "campush.log.1")
                        runCatching { old.delete() }
                        runCatching { file.renameTo(old) }
                    }
                    file.appendText(line + "\n")
                }
            }
        }
    }

    private fun writeCrashSnapshot(error: Throwable) {
        val dir = logDir ?: return
        val text = downloadText("crash=${error.javaClass.name}: ${error.message}")
        runCatching { File(dir, "last-crash.txt").writeText(text) }
    }
}
