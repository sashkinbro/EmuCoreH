package com.sbro.emucoreh.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "crash.log"
    private const val MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024L  // 5 MB
    private const val KEEP_BYTES_AFTER_ROTATION = 4 * 1024 * 1024  // keep last 4 MB

    private const val EXIT_PREFS = "crash_logger_exits"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    private const val MAX_EXIT_REPORTS = 3
    private const val MAX_EXIT_TRACE_CHARS = 200_000

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        logFile = resolveLogFile(context)
        installUncaughtExceptionHandler()
        writeStartupLine(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            logPreviousRunExits(context)
        }
    }

    /**
     * Installs the native fatal-signal handler once the native library is
     * loaded. The handler appends a full report (signal, fault address,
     * thread, backtrace) to this log the moment the process dies from a
     * native crash; in normal operation it writes nothing. Idempotent, so
     * it is safe to call again after late native (re)initialization.
     */
    fun installNativeCrashReporter() {
        val file = logFile ?: return
        runCatching { CoreRuntime.bridge.nativeInstallCrashReporter(file.absolutePath) }
            .onFailure { Log.w(TAG, "Unable to install native crash reporter", it) }
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val body = buildString {
            append("[$tag] $message")
            if (throwable != null) {
                append("\n")
                append(throwable.stackTraceString())
            }
        }
        writeEntry("ERROR", body)
    }

    fun logInfo(tag: String, message: String) {
        writeEntry("INFO", "[$tag] $message")
    }

    fun logContext(key: String, value: Any?) {
        writeEntry("CTX", "$key = $value")
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun resolveLogFile(context: Context): File? {
        return try {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, LOG_DIR)
            if (!dir.exists()) dir.mkdirs()
            File(dir, LOG_FILE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve log file", e)
            null
        }
    }

    private fun writeStartupLine(context: Context) {
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("?")

        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val androidVer = Build.VERSION.RELEASE
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"

        writeEntry("START", "APP START — v$appVersion | Android $androidVer | $device | $abi")
    }

    private fun installUncaughtExceptionHandler() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeEntry(
                    "CRASH",
                    "Thread: ${thread.name}\n${throwable.stackTraceString()}"
                )
            } catch (_: Exception) {
                // Don't let logger crash prevent the original crash from propagating
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Reports how the previous app process ended. ApplicationExitInfo carries
     * the platform's own verdict, including the native-crash tombstone, which
     * covers deaths the in-process handlers could not record (OOM kills, ANR,
     * signals that bypassed the handler). Only abnormal terminations newer
     * than the last reported one are written, at most [MAX_EXIT_REPORTS] runs.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun logPreviousRunExits(context: Context) {
        runCatching { reportPreviousRunExits(context) }
            .onFailure { Log.w(TAG, "Unable to read previous exit info", it) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reportPreviousRunExits(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val exits = manager.getHistoricalProcessExitReasons(
            context.packageName,
            0,
            MAX_EXIT_REPORTS + 8
        )
        if (exits.isEmpty()) return

        val prefs = context.getSharedPreferences(EXIT_PREFS, Context.MODE_PRIVATE)
        val watermark = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        var newest = watermark
        var reported = 0

        for (info in exits) {
            if (info.timestamp <= watermark) break
            newest = maxOf(newest, info.timestamp)
            if (reported >= MAX_EXIT_REPORTS || !isAbnormalExit(info.reason)) continue
            reported++
            writeEntry("CRASH", describeExit(info))
        }

        if (newest != watermark) {
            prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, newest).apply()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isAbnormalExit(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_SIGNALED -> true
        else -> false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describeExit(info: ApplicationExitInfo): String {
        val body = StringBuilder("Previous run terminated abnormally\n")
        body.append("reason = ${exitReasonName(info.reason)} (${info.reason})\n")
        body.append("pid = ${info.pid} | process = ${info.processName} | importance = ${info.importance}\n")
        body.append("time = ${dateFmt.format(Date(info.timestamp))}\n")
        info.description?.takeIf { it.isNotBlank() }?.let { body.append("description = $it\n") }
        val trace = readExitTrace(info)
        if (trace.isNotEmpty()) {
            body.append("trace:\n").append(trace)
        }
        return body.toString().trimEnd()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readExitTrace(info: ApplicationExitInfo): String = runCatching {
        info.traceInputStream?.use { stream ->
            val reader = stream.bufferedReader()
            val buffer = CharArray(8192)
            val out = StringBuilder()
            while (out.length < MAX_EXIT_TRACE_CHARS) {
                val read = reader.read(buffer, 0, minOf(buffer.size, MAX_EXIT_TRACE_CHARS - out.length))
                if (read <= 0) break
                out.append(buffer, 0, read)
            }
            sanitizeTrace(out.toString())
        }.orEmpty()
    }.getOrDefault("")

    /**
     * Some ROMs hand the tombstone back as a binary protobuf instead of plain
     * text; keep the readable strings and fold the binary runs into dots so
     * the entry stays legible.
     */
    private fun sanitizeTrace(raw: String): String {
        val out = StringBuilder(raw.length)
        var inBinaryRun = false
        for (char in raw) {
            val printable = char == '\n' || char == '\t' || char.code in 0x20..0x7E
            if (printable) {
                inBinaryRun = false
                out.append(char)
            } else if (!inBinaryRun) {
                inBinaryRun = true
                out.append('·')
            }
        }
        return out.toString().trim()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN"
    }

    private fun writeEntry(level: String, body: String) {
        val file = logFile ?: return
        val timestamp = dateFmt.format(Date())
        val line = "[$timestamp] $level: $body\n"

        try {
            rotateIfNeeded(file)
            FileWriter(file, /* append = */ true).use { it.write(line) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log entry", e)
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_LOG_SIZE_BYTES) return
        try {
            val content = file.readBytes()
            // Keep only the last KEEP_BYTES_AFTER_ROTATION bytes to avoid trimming mid-line chaos
            val trimStart = (content.size - KEEP_BYTES_AFTER_ROTATION).coerceAtLeast(0)
            // Find the next newline after trimStart so we start on a clean line
            var safeStart = trimStart
            while (safeStart < content.size && content[safeStart] != '\n'.code.toByte()) safeStart++
            safeStart++ // skip the newline itself

            val rotationHeader = "=== LOG ROTATED at ${dateFmt.format(Date())} (old entries trimmed) ===\n"
            file.writeBytes(rotationHeader.toByteArray() + content.copyOfRange(safeStart.coerceAtMost(content.size), content.size))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log", e)
        }
    }

    private fun Throwable.stackTraceString(): String {
        val sw = StringWriter()
        printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }
}
