// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.sbro.emucoreh.core.NativeCoreBridge

class DebugGamesBootActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread(null, Runnable {
            Log.i("GamesBoot", "START GamesBoot")
            val r = try { NativeCoreBridge().runGamesBootTests() } catch(e: Throwable) { "CRASH ${e.stackTraceToString()}" }
            Log.i("GamesBoot", "RESULT len=${r.length} ${r.take(500)}")
            Log.i("GamesBoot", if(r.contains("PASSED")) "PASSED" else "FAILED")
            runOnUiThread { finish() }
        }, "GamesBoot", 8*1024*1024L).start()
    }
}
