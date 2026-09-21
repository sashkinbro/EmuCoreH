// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.Surface
import com.sbro.emucoreh.data.RetroArchShaderEffects
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide owner of the native libretro session.
 *
 * The bundled PPSSPP core is a libretro singleton; [sessionLock] serialises
 * every call that touches it. The Kotlin layer keeps the same per-frame PCM and
 * pad contract as before, so the rest of the app is unchanged.
 */
internal object CoreRuntime {
    private const val TAG = "CoreRuntime"
    private const val DEFAULT_FRAME_WIDTH = 320
    private const val DEFAULT_FRAME_HEIGHT = 240
    private const val SAVE_STATE_MAGIC = 0x54534345
    private const val SAVE_STATE_VERSION = 1
    private const val SAVE_STATE_HEADER_BYTES = 8

    val bridge: NativeCoreBridge by lazy { NativeCoreBridge() }
    val settings = ConcurrentHashMap<String, String>()
    private val _failure = MutableStateFlow<RuntimeFailure?>(null)
    val failure = _failure.asStateFlow()

    private val lifecycleLock = ReentrantLock()
    private val sessionLock = ReentrantLock()
    private var context: Context? = null
    @Volatile private var session = 0L
    @Volatile private var worker: Thread? = null
    private var audioOutput: NativeAudioOutput? = null

    private var systemDirectory = ""
    private var saveDirectory = ""
    private var coreAssetsDirectory = ""

    /** Directory the core uses for VMU files and other per-game save data. */
    val saveDirPath: String get() = saveDirectory

    /** Directory holding the shared VMU images (`<system>/dc`). */
    val vmuDirPath: String get() = File(systemDirectory, "dc").apply { mkdirs() }.absolutePath

    /** Directory the core uses for BIOS files and other system data. */
    val systemDirPath: String get() = systemDirectory

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var timeControlMode = 0
    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    @Volatile private var renderedFirstFrame = false
    @Volatile private var sessionStartedAtNanos = 0L
    @Volatile private var frameWidth = DEFAULT_FRAME_WIDTH
    @Volatile private var frameHeight = DEFAULT_FRAME_HEIGHT
    @Volatile private var requestedRenderer = RendererDefaults.defaultForHardware()
    @Volatile private var activeCoreRenderer = RendererDefaults.toCoreRenderer(requestedRenderer)
    @Volatile private var currentGamePath: String? = null
    @Volatile private var currentBiosOnly = false
    @Volatile private var performanceMetricsEnabled = false
    @Volatile private var detailedPerformanceMetrics = false
    @Volatile private var performanceMetricsSnapshot: String? = null
    @Volatile private var activeFrameRate = 59.94

    private val desiredPadButtons = AtomicIntegerArray(IntArray(2) { 0xFFFF })
    private val pendingPadPressEdges = AtomicIntegerArray(2)
    // A short physical or touch tap can finish before PPSSPP polls input.
    // Keep each edge visible for three frontend frames.
    private val padEdgeHoldMask = IntArray(2)
    private val padEdgeHoldFrames = IntArray(2)
    private val pendingPadAnalog = AtomicIntegerArray(IntArray(2) { 0x80808080.toInt() })

    private class FrameTask(
        val block: () -> Boolean,
        val completed: CountDownLatch
    ) {
        @Volatile var result: Boolean = false
    }

    private val frameTasks = ConcurrentLinkedQueue<FrameTask>()

    /**
     * Runs [block] on the frame worker thread.
     *
     * Serialization must happen there because the OpenGL ES renderer owns its
     * EGL context on that thread: calling [NativeCoreBridge.saveState] /
     * [NativeCoreBridge.loadState] from any other thread silently skips the GL
     * VRAM readback/upload and produces corrupted save states.
     */
    private fun runOnFrameThread(block: () -> Boolean): Boolean {
        val activeWorker = worker
        if (activeWorker == null || !activeWorker.isAlive || activeWorker === Thread.currentThread()) {
            return block()
        }
        val task = FrameTask(block, CountDownLatch(1))
        frameTasks.add(task)
        return try {
            if (!task.completed.await(30, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timed out waiting for the frame worker to run a state operation")
                false
            } else {
                task.result
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun drainFrameTasks() {
        while (true) {
            val task = frameTasks.poll() ?: return
            task.result = try {
                task.block()
            } catch (error: Throwable) {
                Log.e(TAG, "Frame-thread state operation failed", error)
                false
            }
            task.completed.countDown()
        }
    }

    fun initialize(context: Context) {
        this.context = context.applicationContext
        val root = File(context.filesDir, "flycast")
        systemDirectory = File(root, "system").apply { mkdirs() }.absolutePath
        saveDirectory = File(root, "save").apply { mkdirs() }.absolutePath
        coreAssetsDirectory = File(root, "assets").apply { mkdirs() }.absolutePath
        CoreOptionStore.initialize(context.applicationContext)
        runCatching {
            bridge.nativeInit(systemDirectory, saveDirectory, coreAssetsDirectory)
            bridge.apiVersion()
        }.onFailure { Log.e(TAG, "Unable to initialise libretro frontend", it) }
    }

    fun isRunning(): Boolean = running && failure.value == null && sessionLock.withLock { session != 0L }
    fun hasSession(): Boolean = sessionLock.withLock { session != 0L }

    fun setPerformanceMetricsEnabled(visible: Boolean, detailed: Boolean) {
        performanceMetricsEnabled = visible
        detailedPerformanceMetrics = visible && detailed
        if (!visible) performanceMetricsSnapshot = null
    }

    fun performanceMetricsSnapshot(): String? = performanceMetricsSnapshot

    fun setAudioGain(volume: Int, muted: Boolean) {
        val normalized = volume.coerceIn(AudioDefaults.VOLUME_MIN, AudioDefaults.VOLUME_MAX) /
            AudioDefaults.VOLUME_MAX.toFloat()
        bridge.setAudioGain(if (muted) 0f else normalized)
    }

    fun start(gamePath: String, biosOnly: Boolean): Boolean = lifecycleLock.withLock {
        startSession(gamePath, biosOnly)
    }

    /**
     * Restarts the running session on [renderer]. The core only accepts a
     * renderer change on boot, so the game is booted again on the new backend.
     * A savestate is captured first and restored on the new session when the
     * core accepts it, so the player normally continues where they left off.
     */
    fun restartWithRenderer(renderer: Int): Boolean = lifecycleLock.withLock lock@{
        val normalized = RendererDefaults.normalizeAndroidRenderer(renderer)
        val previousRenderer = requestedRenderer
        val gamePath = currentGamePath
        if (!isRunning() || gamePath.isNullOrBlank()) {
            requestedRenderer = normalized
            return@lock true
        }
        val biosOnly = currentBiosOnly
        val wasPaused = paused
        val stateFile = context?.let { File(it.cacheDir, "renderer-switch.rstate") }
        val statePath = stateFile?.absolutePath
        val stateSaved = statePath != null && runOnFrameThread {
            sessionLock.withLock {
                session != 0L && bridge.saveState(session, statePath) == 0
            }
        }
        Log.i(TAG, "Renderer restart renderer=" +
            RendererDefaults.coreRendererName(RendererDefaults.toCoreRenderer(normalized)) +
            " stateSaved=$stateSaved")
        shutdownSession()
        requestedRenderer = normalized
        var started = startSession(gamePath, biosOnly)
        if (!started && normalized != previousRenderer) {
            Log.w(TAG, "Renderer restart failed; reverting to " +
                RendererDefaults.coreRendererName(RendererDefaults.toCoreRenderer(previousRenderer)))
            requestedRenderer = previousRenderer
            started = startSession(gamePath, biosOnly)
        }
        try {
            if (statePath != null && started && stateSaved) {
                val restored = runOnFrameThread {
                    sessionLock.withLock {
                        session != 0L && bridge.loadState(session, statePath) == 0
                    }
                }
                if (!restored) {
                    Log.w(TAG, "Renderer switch state restore rejected; continuing from boot")
                }
            }
        } finally {
            stateFile?.delete()
        }
        if (started && wasPaused) pause()
        started
    }

    private fun startSession(gamePath: String, biosOnly: Boolean): Boolean {
        val startupStartedAtNanos = System.nanoTime()
        if (!biosOnly && !isSupportedDiscPath(gamePath)) {
            Log.e(TAG, "Unsupported PSP image: $gamePath")
            return false
        }
        val requestedCore = RendererDefaults.toCoreRenderer(requestedRenderer)
        val candidates = listOf(requestedCore)

        shutdownSession()
        // The native audio ring is shared between sessions; clear it so the new
        // AAudio stream does not replay the tail of the previous game.
        bridge.resetAudioQueue()
        var coreRenderer = candidates.first()
        var created = false
        for (candidate in candidates) {
            if (createSessionLocked(gamePath, biosOnly, candidate)) {
                coreRenderer = candidate
                created = true
                break
            }
            if (candidate != candidates.last()) {
                Log.w(TAG, "${RendererDefaults.coreRendererName(candidate)} renderer unavailable; " +
                    "falling back to ${RendererDefaults.coreRendererName(candidates.last())}")
            }
        }
        if (!created) return false

        currentGamePath = gamePath
        currentBiosOnly = biosOnly
        for (port in 0..1) {
            desiredPadButtons.set(port, 0xFFFF)
            pendingPadPressEdges.set(port, 0)
            padEdgeHoldMask[port] = 0
            padEdgeHoldFrames[port] = 0
        }
        running = true
        paused = false
        renderedFirstFrame = false
        sessionStartedAtNanos = startupStartedAtNanos
        var started = false
        try {
            val output = NativeAudioOutput()
            audioOutput = output
            output.play()
            worker = thread(name = "EmuCoreH-Frame", isDaemon = true, start = true) {
                // The frame loop shares the CPU with the UI, background work
                // and the audio output. A display-level priority keeps the
                // emulated frame deadline stable under that contention while
                // staying below the audio thread, so mixing never waits on us.
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                runLoop(output)
            }
            Log.i(TAG, String.format(Locale.US, "Startup setup %.1f ms",
                (System.nanoTime() - startupStartedAtNanos) / 1_000_000.0))
            started = true
            return true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start frame/audio runtime", error)
            return false
        } finally {
            if (!started) shutdownSession()
        }
    }

    private fun createSessionLocked(gamePath: String, biosOnly: Boolean,
                                    coreRenderer: Int): Boolean = sessionLock.withLock {
        // Publish core options before retro_init so the core's initial settings
        // load already sees them.
        applyStartOptions(coreRenderer)
        val handle = bridge.createSession()
        if (handle == 0L) return@withLock false
        // Attach the surface before loading content: SET_HW_RENDER fires inside
        // retro_load_game and needs a window to build the render context.
        if (bridge.setSurface(handle, surface, coreRenderer) != 0) {
            Log.e(TAG, "Failed to initialize ${RendererDefaults.coreRendererName(coreRenderer)} renderer")
            bridge.destroySession(handle)
            return@withLock false
        }
        if (biosOnly) {
            // Boot the core with no content so the GPU/display are created
            // before the frame worker calls retro_run().
            if (bridge.loadBiosOnly(handle) != 0) {
                bridge.destroySession(handle)
                return@withLock false
            }
        } else if (loadDisc(handle, gamePath) != 0) {
            bridge.destroySession(handle)
            return@withLock false
        }
        session = handle
        activeCoreRenderer = coreRenderer
        true
    }

    private fun applyStartOptions(coreRenderer: Int) {
        // The frontend owns the graphics context: the renderer is negotiated
        // with the core through the libretro hardware-render interface instead
        // of a core option. Aspect ratio and shader presets are frontend
        // presentation settings applied through the bridge.
        pushAspectRatio(displayAspectRatioPreference() ?: ASPECT_RATIO_AUTO)
        pushShaderEffect()
        pushShaderPreset()
        val upscale = settings["EmuCoreH/Display:Upscale"]?.toFloatOrNull()
            ?: settings["EmuCoreH:UpscaleMultiplier"]?.toFloatOrNull()
        upscale?.let(::pushInternalResolution)
        // Explicit user choices from the settings / game manager / in-game menu
        // win over every derived default.
        CoreOptionStore.persistedEntries().forEach { (key, value) ->
            if (FlycastCoreOptions.option(key) != null) {
                bridge.nativeSetOption(key, value)
            }
        }
        // A Dreamcast BIOS dump is optional: when none is installed the core
        // boots through its HLE BIOS so games still start.
        if (!DreamcastBios.hasBootRom(systemDirectory)) {
            bridge.nativeSetOption("reicast_hle_bios", "enabled")
        }
        // Internal resolution is owned by the app's per-game upscale setting, so
        // re-assert it after the persisted store so a stale entry cannot shadow it.
        upscale?.let(::pushInternalResolution)
    }

    private fun pushInternalResolution(preference: Float) {
        val option = FlycastCoreOptions.option("reicast_internal_resolution") ?: return
        val targetWidth = Math.round(640f * preference).coerceAtLeast(640)
        val choice = option.choices.minByOrNull { candidate ->
            val width = candidate.value.substringBefore('x').toIntOrNull() ?: Int.MAX_VALUE
            Math.abs(width - targetWidth)
        }?.value ?: return
        bridge.nativeSetOption(option.key, choice)
    }

    /** Persists and forwards a Flycast core option. */
    fun setCoreOption(key: String, value: String) {
        CoreOptionStore.set(key, value)
        bridge.nativeSetOption(key, value)
    }

    fun setTimeControl(mode: Int) {
        val safeMode = mode.coerceIn(0, 2)
        if (timeControlMode == safeMode) return
        timeControlMode = safeMode
        bridge.setTimeControl(safeMode)
        bridge.resetAudioQueue()
    }

    /** Effective value of a core option (user override or core default). */
    fun coreOptionValue(key: String): String? =
        CoreOptionStore.value(key) ?: FlycastCoreOptions.option(key)?.defaultValue

    /**
     * Forwards a core option without persisting it. Used for per-game overrides
     * that must not pollute the global option store.
     */
    fun applyCoreOption(key: String, value: String) {
        bridge.nativeSetOption(key, value)
    }

    /** Persists and forwards the app's aspect-ratio selection (0..4). */
    fun setDisplayAspectRatio(type: Int) {
        val normalized = if (type in ASPECT_RATIO_STRETCH..ASPECT_RATIO_CUSTOM) type else ASPECT_RATIO_AUTO
        settings["EmuCoreH/Display:AspectRatio"] = normalized.toString()
        pushAspectRatio(normalized)
    }

    private fun displayAspectRatioPreference(): Int? {
        return settings["EmuCoreH/Display:AspectRatio"]?.toIntOrNull()
            ?: settings["EmuCoreH/GS:AspectRatio"]?.toIntOrNull()
    }

    private fun pushAspectRatio(type: Int) {
        val normalized = if (type in ASPECT_RATIO_STRETCH..ASPECT_RATIO_CUSTOM) type else ASPECT_RATIO_AUTO
        bridge.setDisplayAspectRatio(normalized)
    }

    private fun currentShaderEffect(): Int {
        val enabled = settings["EmuCoreH/GS:ShaderChainEnabled"]?.toBooleanStrictOrNull() == true
        if (!enabled) return RetroArchShaderEffects.NONE
        return RetroArchShaderEffects.classify(settings["EmuCoreH/GS:ShaderChainPreset"])
    }

    private fun pushShaderEffect() {
        runCatching { bridge.nativeSetShaderEffect(currentShaderEffect()) }
            .onFailure { Log.w(TAG, "Unable to apply shader effect", it) }
    }

    private fun pushShaderPreset() {
        val enabled = settings["EmuCoreH/GS:ShaderChainEnabled"]?.toBooleanStrictOrNull() == true
        val preset = settings["EmuCoreH/GS:ShaderChainPreset"].orEmpty()
        runCatching { bridge.nativeSetShaderPreset(if (enabled) preset else "", enabled) }
            .onFailure { Log.w(TAG, "Unable to apply shader preset", it) }
    }

    fun pause() = lifecycleLock.withLock {
        paused = true
        audioOutput?.pause()
    }

    fun resume() = lifecycleLock.withLock {
        if (!running) return@withLock
        audioOutput?.play()
        paused = false
    }

    fun shutdown() = lifecycleLock.withLock {
        shutdownSession()
    }

    private fun shutdownSession() {
        val activeWorker = worker
        check(activeWorker !== Thread.currentThread()) {
            "The frame worker cannot synchronously shut itself down"
        }
        running = false
        setTimeControl(0)
        for (port in 0..1) {
            desiredPadButtons.set(port, 0xFFFF)
            pendingPadPressEdges.set(port, 0)
            padEdgeHoldMask[port] = 0
            padEdgeHoldFrames[port] = 0
        }
        audioOutput?.let { output -> runCatching { output.pause() } }
        activeWorker?.interrupt()
        var callerInterrupted = false
        try {
            if (activeWorker != null) {
                while (activeWorker.isAlive) {
                    try {
                        activeWorker.join()
                    } catch (_: InterruptedException) {
                        callerInterrupted = true
                    }
                }
            }
            worker = null
            audioOutput?.let { output -> runCatching { output.release() } }
            audioOutput = null
            sessionLock.withLock {
                if (session != 0L) {
                    bridge.destroySession(session)
                    session = 0L
                }
            }
            paused = false
            renderedFirstFrame = false
            sessionStartedAtNanos = 0L
            performanceMetricsSnapshot = null
            _failure.value = null
        } finally {
            if (callerInterrupted) Thread.currentThread().interrupt()
        }
    }

    fun changeDisc(path: String): Boolean {
        if (!isSupportedDiscPath(path)) return false
        return sessionLock.withLock { session != 0L && loadDisc(session, path) == 0 }
    }

    fun saveState(path: String): Boolean {
        val target = File(path)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.saving")
        return try {
            val saved = runOnFrameThread {
                sessionLock.withLock {
                    session != 0L && bridge.saveState(session, temporary.absolutePath) == 0
                }
            }
            saved && writeSaveStateFile(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    fun loadState(path: String): Boolean = lifecycleLock.withLock lifecycle@{
        val file = File(path)
        if (!file.isFile) return@lifecycle false
        val wasPaused = paused
        paused = true
        try {
            audioOutput?.pause()
            val raw = File(file.parentFile, ".${file.name}.loading")
            val prepared = runCatching {
                val bytes = file.readBytes()
                val payload = if (
                    bytes.size > SAVE_STATE_HEADER_BYTES &&
                    readSaveStateMagic(bytes) == SAVE_STATE_MAGIC
                ) {
                    bytes.copyOfRange(SAVE_STATE_HEADER_BYTES, bytes.size)
                } else {
                    bytes
                }
                raw.writeBytes(payload)
            }.isSuccess
            val loaded = prepared && runOnFrameThread {
                sessionLock.withLock {
                    session != 0L && bridge.loadState(session, raw.absolutePath) == 0
                }
            }
            raw.delete()
            if (loaded) {
                audioOutput?.flush()
                renderedFirstFrame = false
            }
            loaded
        } finally {
            if (!wasPaused && running) audioOutput?.play()
            paused = wasPaused
        }
    }

    /** Writes the EmuCoreH state container (magic + version + libretro payload). */
    private fun writeSaveStateFile(rawFile: File, target: File): Boolean {
        val payload = runCatching { rawFile.readBytes() }.getOrNull() ?: return false
        val staging = File(target.parentFile, ".${target.name}.tmp")
        return try {
            staging.outputStream().use { output ->
                val header = ByteArray(SAVE_STATE_HEADER_BYTES)
                for (index in 0 until 4) {
                    header[index] = (SAVE_STATE_MAGIC ushr (index * 8)).toByte()
                    header[4 + index] = (SAVE_STATE_VERSION ushr (index * 8)).toByte()
                }
                output.write(header)
                output.write(payload)
            }
            if (staging.renameTo(target)) {
                true
            } else {
                staging.delete()
                false
            }
        } catch (_: Exception) {
            staging.delete()
            false
        }
    }

    private fun readSaveStateMagic(bytes: ByteArray): Int {
        var value = 0
        for (index in 0 until 4) value = value or ((bytes[index].toInt() and 0xFF) shl (index * 8))
        return value
    }

    @Volatile private var lastCheatFilePath: String? = null

    fun loadCheats(path: String) {
        lastCheatFilePath = path
        sessionLock.withLock { runCatching { bridge.loadCheats(path) } }
    }

    fun clearCheats() {
        lastCheatFilePath = null
        sessionLock.withLock { runCatching { bridge.clearCheats() } }
    }

    fun reloadCheats() {
        val path = lastCheatFilePath ?: return
        sessionLock.withLock { runCatching { bridge.loadCheats(path) } }
    }

    fun setMemoryCardPath(slot: Int, path: String?) {
        if (slot !in 0..1) return
        runCatching { bridge.setMemoryCardPath(slot, path) }
    }

    fun setTextureReplacementsPathOverride(path: String?) {
        runCatching { bridge.setTextureReplacementsPathOverride(path?.takeIf(String::isNotBlank)) }
            .onFailure { Log.w(TAG, "Unable to set texture replacements path", it) }
    }

    fun setPadButtons(port: Int, buttons: Int): Boolean {
        if (port !in 0..1) return false
        val next = buttons and 0xFFFF
        val previous = desiredPadButtons.getAndSet(port, next)
        val pressedEdges = previous and next.inv() and 0xFFFF
        if (pressedEdges != 0) {
            while (true) {
                val queued = pendingPadPressEdges.get(port)
                if (pendingPadPressEdges.compareAndSet(port, queued, queued or pressedEdges)) break
            }
        }
        return true
    }

    fun setPadAnalog(port: Int, lx: Int, ly: Int, rx: Int, ry: Int): Boolean {
        if (port !in 0..1) return false
        val packed = lx.coerceIn(0, 255) or
            (ly.coerceIn(0, 255) shl 8) or
            (rx.coerceIn(0, 255) shl 16) or
            (ry.coerceIn(0, 255) shl 24)
        pendingPadAnalog.set(port, packed)
        return true
    }

    fun setPadAnalogMode(port: Int, enabled: Boolean): Boolean = sessionLock.withLock {
        if (session == 0L) return@withLock false
        bridge.setPadAnalogMode(session, port, enabled)
        true
    }

    fun togglePadAnalogMode(port: Int): Boolean? = sessionLock.withLock {
        if (session == 0L) return@withLock null
        val state = bridge.getPadState(session, port)
        val analog = (state >= 0) && (state and PAD_ANALOG_MODE_BIT) == 0
        bridge.setPadAnalogMode(session, port, analog)
        analog
    }

    /** Strong/weak rumble latched by the core, as [strong, weak] in 0..1. */
    fun getPadRumble(port: Int): FloatArray? = sessionLock.withLock {
        if (session == 0L || port !in 0..1) return@withLock null
        val state = bridge.getPadState(session, port)
        if (state < 0) return@withLock null
        floatArrayOf(((state shr 8) and 0xFF) / 255f, (state and 0xFF) / 255f)
    }

    fun attachSurface(value: Surface, width: Int, height: Int) {
        surface = value
        surfaceWidth = width
        surfaceHeight = height
        sessionLock.withLock {
            if (session != 0L && bridge.setSurface(session, value, activeCoreRenderer) != 0)
                Log.e(TAG, "Failed to attach ${RendererDefaults.coreRendererName(activeCoreRenderer)} presentation surface")
        }
    }

    fun hasAttachedSurface(value: Surface, width: Int, height: Int): Boolean =
        sessionLock.withLock {
            session != 0L && surface === value && surfaceWidth == width && surfaceHeight == height
        }

    fun detachSurface() {
        sessionLock.withLock {
            if (session != 0L && bridge.setSurface(session, null, activeCoreRenderer) != 0)
                Log.w(TAG, "Failed to detach presentation surface")
        }
        surface = null
        surfaceWidth = 0
        surfaceHeight = 0
        renderedFirstFrame = false
    }

    fun displayRect(): FloatArray? {
        if (!renderedFirstFrame || surfaceWidth <= 0 || surfaceHeight <= 0) return null
        // The presenters letterbox using the core's display aspect ratio, which
        // can differ from the raw frame pixel aspect (pixel-aspect games,
        // widescreen overrides, hi-res modes). Ask them for the exact rect so
        // the side artwork never overlaps the emulated image.
        val presented = runCatching { bridge.getPresentRect() }
            .getOrNull()
            ?.takeIf { it.size >= 4 && it[2] > it[0] && it[3] > it[1] }
        if (presented != null) return presented
        val rect = fitRect(surfaceWidth, surfaceHeight, frameWidth, frameHeight)
        return floatArrayOf(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
    }

    fun diagnostics(): String = sessionLock.withLock { bridge.getDiagnostics() }

    fun gpuBackendSubmissions(): Long = 0L

    fun updateSetting(section: String, key: String, value: String): Boolean {
        if ((section == "EmuCoreH" || section == "EmuCoreH/GS") && key == "Renderer") {
            val renderer = value.toIntOrNull() ?: return false
            requestedRenderer = RendererDefaults.normalizeAndroidRenderer(renderer)
            settings["$section:$key"] = value
            return true
        }
        settings["$section:$key"] = value
        forwardCoreSetting(section, key, value)
        return true
    }

    /**
     * Translates the app's existing settings into Flycast libretro option keys
     * and forwards them to the core at runtime. Options the core does not
     * understand are simply ignored.
     */
    private fun forwardCoreSetting(section: String, key: String, value: String) {
        if (section == "EmuCoreH/GS" &&
            (key == "ShaderChainEnabled" || key == "ShaderChainPreset")) {
            pushShaderEffect()
            pushShaderPreset()
        }
        val bool = value.toBooleanStrictOrNull()
        val target: Pair<String, String>? = when ("$section:$key") {
            "EmuCoreH/Display:Upscale" -> value.toFloatOrNull()?.let {
                val option = FlycastCoreOptions.option("reicast_internal_resolution") ?: return@let null
                val targetWidth = Math.round(640f * it).coerceAtLeast(640)
                val choice = option.choices.minByOrNull { candidate ->
                    val width = candidate.value.substringBefore('x').toIntOrNull() ?: Int.MAX_VALUE
                    Math.abs(width - targetWidth)
                }?.value ?: return@let null
                option.key to choice
            }
            "EmuCoreH/GS:LoadTextureReplacements" ->
                bool?.let { "reicast_custom_textures" to if (it) "enabled" else "disabled" }
            "EmuCoreH/Display:AspectRatio" -> value.toIntOrNull()?.let { type ->
                setDisplayAspectRatio(type)
                null
            }
            else -> null
        }
        target?.let { (coreKey, coreValue) -> bridge.nativeSetOption(coreKey, coreValue) }
    }

    private fun textureFilterName(filter: Int): String = when (filter) {
        1 -> "Linear"
        else -> "Nearest"
    }

    private fun publishPerformanceMetrics(fps: Double, frames: Int, frameNanos: Long,
                                          audioStats: LongArray?, cpuLoadPercent: Double) {
        if (frames <= 0 || !performanceMetricsEnabled) return
        val softwareRenderer = activeCoreRenderer == RendererDefaults.CORE_SOFTWARE
        val targetFps = activeFrameRate
        val speed = fps / targetFps * 100.0
        val renderer = RendererDefaults.coreRendererName(activeCoreRenderer)
        val frameMs = frameNanos / frames / 1_000_000.0
        val gpuLoad = if (detailedPerformanceMetrics) GpuLoadReader.loadPercent() else null
        val overlay = buildString {
            append(String.format(Locale.US, "FPS:%.1f | Speed:%.1f%% | Target:%.2f", fps, speed, targetFps))
            if (detailedPerformanceMetrics) {
                // The renderer line must end with " HW |" / " SW |" so the
                // overlay recognises it as the active backend and keeps it on
                // its own bottom line instead of duplicating it inline.
                append('\n').append(renderer).append(if (softwareRenderer) " SW |" else " HW |")
                append('\n').append("CPU:Host | ").append(String.format(Locale.US, "%.1f%%", cpuLoadPercent))
                append('\n').append("GPU:Host")
                if (gpuLoad != null) append(String.format(Locale.US, " | %.1f%%", gpuLoad))
                append('\n').append("Res:").append(frameWidth).append('x').append(frameHeight)
                append('\n').append(String.format(Locale.US, "Frame:%.1f ms", frameMs))
                if (audioStats != null && audioStats.size >= 8) {
                    append('\n').append(String.format(Locale.US, "Audio:%d Hz | queue %d", audioStats[2], audioStats[4]))
                }
            }
        }
        performanceMetricsSnapshot = String.format(Locale.US, "%.3f\n%.3f\n%s", fps, speed, overlay)
    }

    private fun runLoop(output: NativeAudioOutput) {
        var metricsStartNanos = System.nanoTime()
        var metricsFrames = 0
        var metricsFrameTotalNanos = 0L
        var metricsStartCpuMs = android.os.Process.getElapsedCpuTime()
        val framePacer = FramePacer()
        var frameStartNanos = 0L
        var previousFrameStartNanos = 0L
        var metricsMaxIntervalNanos = 0L
        var metricsMaxCoreNanos = 0L
        var resetMetrics = true
        try {
            while (running) {
                // Owns the thread-affine EGL context, so any queued save/load
                // task must wait until the hardware context is ready here.
                bridge.ensureHardwareContext()
                drainFrameTasks()
                if (paused) {
                    framePacer.reset()
                    resetMetrics = true
                    Thread.sleep(8)
                    continue
                }
                // The frame limiter is mandatory: every game runs at the rate
                // reported by the core (59.94/50 Hz NTSC/PAL), and fast forward
                // multiplies that instead of uncapping the host speed.
                val frameLimitEnabled = true
                if (frameLimitEnabled) {
                    val timeMode = timeControlMode
                    val coreFrameRate = bridge.getFrameRate(session).takeIf { it > 1.0 } ?: 59.94
                    // A manual target rate overrides the console's reported one.
                    val manualTargetFps = settings["EmuCoreH/GS:TargetFps"]?.toIntOrNull() ?: 0
                    val frameRate = if (manualTargetFps in 20..120) {
                        manualTargetFps.toDouble()
                    } else {
                        coreFrameRate
                    }
                    activeFrameRate = frameRate
                    // The core lowers its reported frame rate when it notifies the
                    // frontend about a locked 30/20 fps scene (vsync swap
                    // interval): each call then advances several console frames
                    // and carries their audio, so the stream is still real time.
                    // Only a manual target rate may change the audio speed.
                    val audioPlaybackRate =
                        if (manualTargetFps in 20..120) manualTargetFps / coreFrameRate else 1.0
                    bridge.setAudioPlaybackRate(audioPlaybackRate)
                    if (frameRate > 1.0) {
                        val pacedRate = when (timeMode) {
                            1 -> frameRate * 3.0
                            2 -> 2.0
                            else -> frameRate
                        }
                        while (running && !paused) {
                            drainFrameTasks()
                            val remainingNanos = framePacer.remainingNanos(System.nanoTime(), pacedRate)
                            if (remainingNanos <= 0L) break
                            if (remainingNanos > FRAME_PACING_SPIN_NANOS) Thread.sleep(1)
                        }
                    }
                } else {
                    bridge.setAudioPlaybackRate(1.0)
                    framePacer.reset()
                }
                val t0 = System.nanoTime()
                var skippedPausedFrame = false
                val coreNanos = sessionLock.withLock {
                    if (!running || session == 0L) null else if (paused) {
                        skippedPausedFrame = true
                        null
                    } else {
                        for (port in 0..1) {
                            // Preserve a tap that began and ended between two
                            // guest frames, including cores that poll input
                            // less often than the frontend presents frames.
                            val pressedEdges = pendingPadPressEdges.getAndSet(port, 0)
                            if (pressedEdges != 0) {
                                padEdgeHoldMask[port] = padEdgeHoldMask[port] or pressedEdges
                                padEdgeHoldFrames[port] = 3
                            }
                            val buttons = desiredPadButtons.get(port) and padEdgeHoldMask[port].inv()
                            bridge.setPadButtons(session, port, buttons)
                            if (padEdgeHoldFrames[port] > 0 && --padEdgeHoldFrames[port] == 0) {
                                padEdgeHoldMask[port] = 0
                            }
                            val analog = pendingPadAnalog.get(port)
                            bridge.setPadAnalog(
                                session,
                                port,
                                analog and 0xFF,
                                (analog ushr 8) and 0xFF,
                                (analog ushr 16) and 0xFF,
                                (analog ushr 24) and 0xFF
                            )
                        }
                        frameStartNanos = System.nanoTime()
                        framePacer.frameStarted(frameStartNanos)
                        val coreStartNanos = System.nanoTime()
                        bridge.runFrame(session)
                        val elapsed = System.nanoTime() - coreStartNanos
                        bridge.getDisplayRect(session)
                            ?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
                            ?.let {
                                frameWidth = it[2]
                                frameHeight = it[3]
                            }
                        if (!renderedFirstFrame) {
                            renderedFirstFrame = true
                            val startedAt = sessionStartedAtNanos
                            if (startedAt != 0L) {
                                Log.i(TAG, String.format(Locale.US,
                                    "First emulated frame after %.1f ms (core %.1f ms)",
                                    (System.nanoTime() - startedAt) / 1_000_000.0,
                                    elapsed / 1_000_000.0))
                            }
                        }
                        elapsed
                    }
                } ?: if (skippedPausedFrame) continue else break
                val frameNanos = System.nanoTime() - t0

                if (resetMetrics) {
                    metricsStartNanos = frameStartNanos
                    previousFrameStartNanos = frameStartNanos
                    metricsFrames = 0
                    metricsFrameTotalNanos = 0L
                    metricsMaxIntervalNanos = 0L
                    metricsMaxCoreNanos = 0L
                    metricsStartCpuMs = android.os.Process.getElapsedCpuTime()
                    resetMetrics = false
                    continue
                }
                metricsMaxIntervalNanos = maxOf(metricsMaxIntervalNanos, frameStartNanos - previousFrameStartNanos)
                metricsMaxCoreNanos = maxOf(metricsMaxCoreNanos, coreNanos)
                previousFrameStartNanos = frameStartNanos
                metricsFrames++
                metricsFrameTotalNanos += frameNanos
                val now = frameStartNanos
                if (!performanceMetricsEnabled) {
                    metricsStartNanos = now
                    metricsFrames = 0
                    metricsFrameTotalNanos = 0L
                    metricsStartCpuMs = android.os.Process.getElapsedCpuTime()
                } else if (now - metricsStartNanos >= 1_000_000_000L) {
                    val elapsed = now - metricsStartNanos
                    val fps = metricsFrames * 1_000_000_000.0 / elapsed
                    val cpuNowMs = android.os.Process.getElapsedCpuTime()
                    val cpuDeltaMs = (cpuNowMs - metricsStartCpuMs).coerceAtLeast(0L)
                    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                    val cpuLoad = if (elapsed > 0L) {
                        cpuDeltaMs.toDouble() / (elapsed / 1_000_000.0) / cores * 100.0
                    } else {
                        0.0
                    }
                    publishPerformanceMetrics(fps, metricsFrames, metricsFrameTotalNanos,
                        output.stats(), cpuLoad)
                    if (com.sbro.emucoreh.BuildConfig.DEBUG) {
                        Log.d(TAG, "pacing fps=%.1f core=%.1fms queue=%d high=%d silence=%d maxInterval=%.1fms maxCore=%.1fms".format(
                            Locale.US, fps, metricsFrameTotalNanos / metricsFrames / 1_000_000.0,
                            output.bufferedFrames(), output.pacingHighWaterFrames(), output.stats()?.getOrNull(7) ?: 0L,
                            metricsMaxIntervalNanos / 1_000_000.0, metricsMaxCoreNanos / 1_000_000.0))
                    }
                    metricsMaxIntervalNanos = 0L
                    metricsMaxCoreNanos = 0L
                    metricsStartNanos = now
                    metricsStartCpuMs = cpuNowMs
                    metricsFrames = 0
                    metricsFrameTotalNanos = 0L
                }
            }
        } catch (error: InterruptedException) {
            if (running) reportFailure("Emulation worker was interrupted unexpectedly")
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            Log.e(TAG, "Emulation frame loop stopped", error)
            reportFailure("Emulation frame loop stopped: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        } finally {
            drainFrameTasks()
            running = false
        }
    }

    private fun reportFailure(detail: String) {
        if (_failure.compareAndSet(null, RuntimeFailure(detail))) Log.e(TAG, detail)
    }

    private fun loadDisc(handle: Long, gamePath: String): Int {
        val app = context ?: return -1
        // SAF documents are exposed to the core through the frontend VFS: the
        // bridge hands out detached descriptors, so a multi-file CUE/GDI set is
        // streamed in place and nothing is copied into app storage.
        val path = if (gamePath.startsWith("content://")) {
            SafStorageBridge.prepare(app, gamePath) ?: return -1
        } else gamePath
        return bridge.loadDisc(handle, path)
    }

    fun hasDiscMedia(): Boolean = sessionLock.withLock {
        session != 0L && runCatching { bridge.hasDiscMedia(session) }.getOrDefault(false)
    }

    private fun isSupportedDiscPath(path: String): Boolean {
        val name = if (path.startsWith("content://")) {
            context?.let { DocumentPathResolver.getDisplayName(it, path) } ?: return false
        } else path
        return GameFormats.isSupportedName(name)
    }

    private fun fitRect(containerWidth: Int, containerHeight: Int, contentWidth: Int, contentHeight: Int): Rect {
        val scale = minOf(containerWidth.toFloat() / contentWidth, containerHeight.toFloat() / contentHeight)
        val width = (contentWidth * scale).toInt().coerceAtLeast(1)
        val height = (contentHeight * scale).toInt().coerceAtLeast(1)
        val left = (containerWidth - width) / 2
        val top = (containerHeight - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private const val BIOS_BYTES = 512L * 1024L
    private const val PAD_ANALOG_MODE_BIT = 1 shl 16
    private const val FRAME_PACING_SPIN_NANOS = 2_000_000L
    // App aspect-ratio preference values (mirrors the display settings UI).
    private const val ASPECT_RATIO_STRETCH = 0
    private const val ASPECT_RATIO_AUTO = 1
    private const val ASPECT_RATIO_4_3 = 2
    private const val ASPECT_RATIO_16_9 = 3
    private const val ASPECT_RATIO_CUSTOM = 4
}
