
package com.sbro.emucoreh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucoreh.core.NativeCoreBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConsoleState(
    val apiVersionHex: String = "0x0",
    val diagnostics: String = "loading...",
    val smokeResult: String = "not run",
    val cpuTestResult: String = "not run",
    val irqTimerResult: String = "not run",
    val dmaTestResult: String = "not run",
    val gteTestResult: String = "not run",
    val gpuTestResult: String = "not run",
    val spuMdecTestResult: String = "not run",
    val cdromSioTestResult: String = "not run",
    val jitTestResult: String = "not run",
    val biosTestResult: String = "not run",
    val optimizedTestResult: String = "not run",
    val regressionTestResult: String = "not run",
    val finalTestResult: String = "not run",
    val discLoaderResult: String = "not run",
    val asyncDiscResult: String = "not run",
    val savestateFileResult: String = "not run",
    val bootResult: String = "not run",
    val hostThreadResult: String = "not run",
    val isRunning: Boolean = false,
    val hostInfo: String = "",
)

class TestConsoleViewModel : ViewModel() {
    private val bridge by lazy { NativeCoreBridge() }
    private val _state = MutableStateFlow(ConsoleState())
    val state: StateFlow<ConsoleState> = _state

    init {
        refreshDiagnostics()
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            val (diag, host, api) = withContext(Dispatchers.IO) {
                try {
                    val d = bridge.getDiagnostics()
                    val h = bridge.getHostInfo()
                    val a = bridge.apiVersion()
                    Triple(d, h, a)
                } catch (e: Throwable) {
                    Triple("error: ${e.message}", "error", 0)
                }
            }
            _state.value = _state.value.copy(
                diagnostics = diag,
                hostInfo = host,
                apiVersionHex = "0x%08x".format(api)
            )
        }
    }

    fun runSmoke() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runSmoke()
                } catch (e: Throwable) {
                    "smoke error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(smokeResult = result, isRunning = false)
            // also refresh diagnostics to show new hash
            refreshDiagnostics()
        }
    }

    fun runCpuTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runCpuTests()
                } catch (e: Throwable) {
                    "cpu test error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(cpuTestResult = result, isRunning = false)
        }
    }

    fun runIrqTimerTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runIrqTimerTests()
                } catch (e: Throwable) {
                    "irq/timer error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(irqTimerResult = result, isRunning = false)
        }
    }

    fun runDmaTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runDmaTests()
                } catch (e: Throwable) {
                    "dma error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(dmaTestResult = result, isRunning = false)
        }
    }

    fun runGteTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runGteTests()
                } catch (e: Throwable) {
                    "gte error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(gteTestResult = result, isRunning = false)
        }
    }

    fun runGpuTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runGpuTests()
                } catch (e: Throwable) {
                    "gpu error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(gpuTestResult = result, isRunning = false)
        }
    }

    fun runSpuMdecTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runSpuMdecTests()
                } catch (e: Throwable) {
                    "spu/mdec error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(spuMdecTestResult = result, isRunning = false)
        }
    }

    fun runCdromSioTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runCdromSioTests()
                } catch (e: Throwable) {
                    "cdrom/sio error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(cdromSioTestResult = result, isRunning = false)
        }
    }

    fun runJitTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runJitTests()
                } catch (e: Throwable) {
                    "jit error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(jitTestResult = result, isRunning = false)
        }
    }

    fun runBiosTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runBiosTests()
                } catch (e: Throwable) {
                    "bios error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(biosTestResult = result, isRunning = false)
        }
    }

    fun runOptimizedTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runOptimizedTests()
                } catch (e: Throwable) {
                    "optimized error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(optimizedTestResult = result, isRunning = false)
        }
    }

    fun runRegressionTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runRegressionTests()
                } catch (e: Throwable) {
                    "regression error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(regressionTestResult = result, isRunning = false)
        }
    }

    fun runFinalTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runFinalTests()
                } catch (e: Throwable) {
                    "final error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(finalTestResult = result, isRunning = false)
        }
    }

    fun runDiscLoaderTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runDiscLoaderTests()
                } catch (e: Throwable) {
                    "disc loader error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(discLoaderResult = result, isRunning = false)
        }
    }

    fun runAsyncDiscTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runAsyncDiscTests()
                } catch (e: Throwable) {
                    "async disc error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(asyncDiscResult = result, isRunning = false)
        }
    }

    fun runSavestateFileTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runSavestateFileTests()
                } catch (e: Throwable) {
                    "savestate file error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(savestateFileResult = result, isRunning = false)
        }
    }

    fun runBootTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runBootTests()
                } catch (e: Throwable) {
                    "boot error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(bootResult = result, isRunning = false)
        }
    }

    fun runHostThreadTests() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(isRunning = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    bridge.runHostThreadTests()
                } catch (e: Throwable) {
                    "host thread error: ${e.message}\n${e.stackTraceToString()}"
                }
            }
            _state.value = _state.value.copy(hostThreadResult = result, isRunning = false)
        }
    }
}
