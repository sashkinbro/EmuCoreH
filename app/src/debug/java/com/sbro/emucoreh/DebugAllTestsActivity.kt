// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.sbro.emucoreh.core.NativeCoreBridge

class DebugAllTestsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Use 8MB stack for native-heavy tests (EmulationSession ~1.1MB frame)
        Thread(null, Runnable {
            val b = NativeCoreBridge()
            fun run(name: String, fn: ()->String) {
                Log.i("AllTests", "START $name")
                val r = runCatching(fn).getOrElse { "CRASH $name\n${it.stackTraceToString()}" }
                Log.i("AllTests", "=== $name ===\n$r")
                if (!r.contains("PASSED") && !r.contains("PASS")) Log.e("AllTests", "FAILED $name")
            }
            run("Smoke") { b.runSmoke() }
            run("Cpu") { b.runCpuTests() }
            run("IrqTimer") { b.runIrqTimerTests() }
            run("Dma") { b.runDmaTests() }
            run("Gte") { b.runGteTests() }
            run("Gpu") { b.runGpuTests() }
            run("SpuMdec") { b.runSpuMdecTests() }
            run("CdromSio") { b.runCdromSioTests() }
            run("Jit") { b.runJitTests() }
            run("Bios") { b.runBiosTests() }
            run("Optimized") { b.runOptimizedTests() }
            run("Regression") { b.runRegressionTests() }
            run("Final") { b.runFinalTests() }
            run("DiscLoader") { b.runDiscLoaderTests() }
            run("AsyncDisc") { b.runAsyncDiscTests() }
            run("Savestate") { b.runSavestateFileTests() }
            run("Boot") { b.runBootTests() }
            run("GamesBoot") { b.runGamesBootTests() }
            run("HostThread") { b.runHostThreadTests() }
            Log.i("AllTests", "ALL DONE")
            runOnUiThread { finish() }
        }, "EmuCoreH-AllTests", (8 * 1024 * 1024).toLong()).start()
    }
}
