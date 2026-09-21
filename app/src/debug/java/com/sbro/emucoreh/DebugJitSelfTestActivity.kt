// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.sbro.emucoreh.core.NativeCoreBridge
import kotlin.concurrent.thread

/** A debug-build-only entry point for running the native ARM64 JIT oracle on a device. */
class DebugJitSelfTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thread(name = "EmuCoreH-JitSelfTest") {
            val report = runCatching { NativeCoreBridge().runJitTests() }
                .getOrElse { error -> "JIT SELF TEST CRASHED\n${error.stackTraceToString()}" }
            Log.i(TAG, report)
            runOnUiThread { finish() }
        }
    }

    private companion object {
        const val TAG = "JitSelfTest"
    }
}
