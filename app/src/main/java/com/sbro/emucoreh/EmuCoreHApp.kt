package com.sbro.emucoreh

import android.app.Application
import android.app.ActivityManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.sbro.emucoreh.core.AppAnalytics
import com.sbro.emucoreh.core.AppIconManager
import com.sbro.emucoreh.core.BackupSessionGate
import com.sbro.emucoreh.core.CrashLogger
import com.sbro.emucoreh.core.EmulatorBridge
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.drive.DriveBackupArchive
import com.sbro.emucoreh.data.drive.DriveBackupException
import com.sbro.emucoreh.data.drive.DriveBackupWork
import com.sbro.emucoreh.discord.DiscordIntegration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EmuCoreHApp : Application() {
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // The Discord SDK helper must not initialize the emulator-side application graph.
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            getSystemService(ActivityManager::class.java)?.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        if (processName?.endsWith(":discord") == true) return
        // CrashLogger must be the very first thing — it catches crashes in all subsequent init steps
        if (BuildConfig.DEBUG) startupStep("crash_logger") { CrashLogger.init(this) }
        if (DriveBackupArchive.hasPendingRecovery(this)) applicationScope.launch {
            try { BackupSessionGate.whileStopped { DriveBackupArchive(this@EmuCoreHApp).recoverPending() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // VM startup performs the same recovery under the gate before it can open cards.
                if ((error as? DriveBackupException)?.reason != "busy") {
                    if (BuildConfig.DEBUG) Log.w("DriveBackup", "Pending restore recovery will be retried before VM startup")
                }
            }
        }
        startupStep("analytics") { AppAnalytics.initialize(this) }
        startupStep("app_icon") { AppIconManager.applyProIcon(this, AppPreferences(this).getProUnlockedSync()) }
        startupStep("pending_backup") { DriveBackupWork.resumePending(this) }
        startupStep("emulator_bridge") { EmulatorBridge.initializeOnce(this) }
        startupStep("discord") { DiscordIntegration.initialize(this) }
    }

    private inline fun startupStep(name: String, block: () -> Unit) {
        if (!BuildConfig.DEBUG) {
            block()
            return
        }
        val started = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("AppStartup:$name")
        try { block() }
        finally {
            Trace.endSection()
            Log.i("AppStartup", "$name elapsedMs=${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000}")
        }
    }
}
