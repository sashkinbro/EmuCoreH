// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
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
 * The bundled Flycast core is a libretro singleton; [sessionLock] serialises
 * every call that touches it. The Kotlin layer keeps the same per-frame PCM and
 * pad contract as before, so the rest of the app is unchanged.
 */
internal object CoreRuntime {
    private const val TAG = "CoreRuntime"
    private const val DEFAULT_FRAME_WIDTH = 320
    private const val DEFAULT_FRAME_HEIGHT = 240
    private val ADRENOTOOLS_HOOK_LIBRARIES = listOf(
        "libmain_hook.so",
        "libhook_impl.so",
        "libgsl_alloc_hook.so",
        "libfile_redirect_hook.so"
    )

    val bridge: NativeCoreBridge by lazy { NativeCoreBridge() }
    val settings = ConcurrentHashMap<String, String>()
    @Volatile var selectedGpuDriverPath: String? = null
    private val _failure = MutableStateFlow<RuntimeFailure?>(null)
    val failure = _failure.asStateFlow()

    // Flycast-style renderer fallback: when the requested hardware renderer
    // cannot initialize, the ViewModel relaunches the session on the fallback
    // renderer instead of ending emulation with an error.
    private val _rendererFallbackRequested = MutableStateFlow<Int?>(null)
    val rendererFallbackRequested = _rendererFallbackRequested.asStateFlow()
    private var rendererFallbackAttempted = false

    private val lifecycleLock = ReentrantLock()
    private val sessionLock = ReentrantLock()
    private var context: Context? = null
    @Volatile private var session = 0L
    @Volatile private var worker: Thread? = null
    @Volatile private var audioOutput: NativeAudioOutput? = null
    private val hostPlayback = HostPlaybackGate()

    private var systemDirectory = ""
    private var saveDirectory = ""
    private var coreAssetsDirectory = ""
    @Volatile var platform: EmulationPlatform = EmulationPlatform.DREAMCAST
        private set
    private var dataRootOverride: String? = null
    @Volatile var launchCoreOptions: Map<String, String> = emptyMap()
    @Volatile var launchIdentityPath: String? = null

    /** Directory holding the shared VMU images (`<system>/dc`). */
    val vmuDirPath: String get() = File(context?.filesDir, "flycast/system/dc").apply { mkdirs() }.absolutePath

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
    // A short physical or touch tap can finish before the core polls input.
    // Keep each edge visible for three frontend frames.
    private val padEdgeHoldMask = IntArray(2)
    private val padEdgeHoldFrames = IntArray(2)
    private val pendingPadAnalog = AtomicIntegerArray(IntArray(2) { 0x80808080.toInt() })
    private val pendingPadTriggers = Array(2) { Model3TriggerPressure() }
    private val lightGunInputs = Array(2) { LightGunInputState() }
    private val requestedInputDevices = AtomicIntegerArray(2)
    private val appliedInputDevices = IntArray(2) { -1 }
    @Volatile private var arcadeInputMode = -1
    @Volatile private var lightGunCursorEnabled = true

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
                if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Timed out waiting for the frame worker to run a state operation")
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
                if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.e(TAG, "Frame-thread state operation failed", error)
                false
            }
            task.completed.countDown()
        }
    }

    fun initialize(context: Context) {
        this.context = context.applicationContext
        if (!hasSession()) JGenesisContentPreparer.cleanupAbandonedContent(context)
        if (!hasSession()) SaturnContentPreparer.cleanupAbandonedContent(context)
        if (!hasSession()) Model3ContentPreparer.cleanupAbandonedContent(context)
        val root = File(context.filesDir, "flycast")
        systemDirectory = File(root, "system").apply { mkdirs() }.absolutePath
        saveDirectory = File(root, "save").apply { mkdirs() }.absolutePath
        coreAssetsDirectory = File(root, "assets").apply { mkdirs() }.absolutePath
        CoreOptionStore.initialize(context.applicationContext)
        runCatching {
            bridge.nativeInit(systemDirectory, saveDirectory, coreAssetsDirectory)
            bridge.apiVersion()
        }.onFailure { CrashLogger.logError(TAG, "Unable to initialise libretro frontend", it) }
        CrashLogger.installNativeCrashReporter()
    }

    fun isRunning(): Boolean = running && failure.value == null && sessionLock.withLock { session != 0L }
    fun hasSession(): Boolean = sessionLock.withLock { session != 0L }

    /**
     * Consumes a pending renderer fallback request. The ViewModel owns the
     * relaunch: it persists the new renderer and reboots the session.
     */
    fun consumeRendererFallbackRequest(): Int? {
        val requested = _rendererFallbackRequested.value
        if (requested != null) _rendererFallbackRequested.value = null
        return requested
    }

    private fun requestRendererFallback(): Boolean {
        val target = RendererFallbackPolicy
            .fallbackTarget(activeCoreRenderer, rendererFallbackAttempted)
            ?: return false
        rendererFallbackAttempted = true
        _rendererFallbackRequested.value = target
        return true
    }

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
        if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.i(TAG, "Renderer restart renderer=" +
            RendererDefaults.coreRendererName(RendererDefaults.toCoreRenderer(normalized)) +
            " stateSaved=$stateSaved")
        shutdownSession()
        requestedRenderer = normalized
        var started = startSession(gamePath, biosOnly)
        if (!started && normalized != previousRenderer) {
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Renderer restart failed; reverting to " +
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
                    if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Renderer switch state restore rejected; continuing from boot")
                }
            }
        } finally {
            stateFile?.delete()
        }
        if (started && wasPaused) pause()
        started
    }

    private fun startSession(gamePath: String, biosOnly: Boolean): Boolean {
        val startupStartedAtNanos = if (com.sbro.emucoreh.BuildConfig.DEBUG) System.nanoTime() else 0L
        if (!biosOnly && !isSupportedDiscPath(gamePath)) {
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.e(TAG, "Unsupported disc image: $gamePath")
            return false
        }
        val app = context ?: return false
        val detectedPlatform = if (biosOnly) EmulationPlatform.DREAMCAST else SegaPlatformDetector.detect(app, gamePath)
        val requestedCore = when {
            detectedPlatform.isSaturn -> RendererDefaults.CORE_OPENGL
            // jgenesis has no core-side GPU rasterizer, but both of our GPU
            // output backends can present its software frames. Legacy
            // "Software" preferences from Flycast map to the OpenGL output.
            detectedPlatform.isSega || detectedPlatform.isModel3 -> RendererDefaults.toCoreRenderer(requestedRenderer)
                .takeIf { it != RendererDefaults.CORE_SOFTWARE } ?: RendererDefaults.CORE_OPENGL
            else -> RendererDefaults.toCoreRenderer(requestedRenderer)
        }
        val candidates = if ((detectedPlatform.isSega || detectedPlatform.isModel3) && requestedCore == RendererDefaults.CORE_VULKAN) {
            listOf(RendererDefaults.CORE_VULKAN, RendererDefaults.CORE_OPENGL)
        } else {
            listOf(requestedCore)
        }

        shutdownSession()
        val driverPath = if (detectedPlatform.supportsCustomGpuDrivers() && requestedCore == RendererDefaults.CORE_VULKAN)
            GpuDriverManager(app).resolveUsableDriverPath(selectedGpuDriverPath) else null
        bridge.configureVulkanDriver(driverPath, adrenoToolsHookDirectory(app), app.cacheDir.absolutePath)
        platform = detectedPlatform
        val root = File(app.filesDir, when (platform.coreId) { 1 -> "jgenesis"; 2 -> "supermodel"; 3 -> "saturn"; else -> "flycast" })
        systemDirectory = File(root, "system").apply { mkdirs() }.absolutePath
        if (platform.isModel3) {
            val definitions = File(systemDirectory, "supermodel/Games.xml")
            definitions.parentFile?.mkdirs()
            app.assets.open("supermodel/Games.xml").use { source ->
                definitions.outputStream().use { source.copyTo(it) }
            }
        }
        saveDirectory = (if (platform.isSega) {
            File(EmulatorStorage.runtimeDirectories(app, dataRootOverride).root, "saves/jgenesis/${platform.name.lowercase(Locale.ROOT)}")
        } else if (platform.isSaturn) {
            File(EmulatorStorage.runtimeDirectories(app, dataRootOverride).root, "saves/saturn")
        } else if (platform.isModel3) {
            File(EmulatorStorage.runtimeDirectories(app, dataRootOverride).root, "saves")
        } else File(root, "save")).apply { mkdirs() }.absolutePath
        coreAssetsDirectory = File(root, "assets").apply { mkdirs() }.absolutePath
        if (!bridge.nativeSelectCore(platform.coreId)) {
            CrashLogger.logError(TAG, "Unable to select core for $platform")
            return false
        }
        bridge.nativeInit(systemDirectory, saveDirectory, coreAssetsDirectory)
        if (platform.coreId == 0) bridge.setDataRootOverride(dataRootOverride)
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
                if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "${RendererDefaults.coreRendererName(candidate)} renderer unavailable; " +
                    "falling back to ${RendererDefaults.coreRendererName(candidates.last())}")
            }
        }
        if (!created) {
            CrashLogger.logError(TAG, "Unable to start $platform: all renderer candidates failed")
            return false
        }

        // A freshly booted session may use its own renderer fallback budget.
        rendererFallbackAttempted = false
        _rendererFallbackRequested.value = null

        currentGamePath = gamePath
        currentBiosOnly = biosOnly
        arcadeInputMode = -1
        for (port in 0..1) {
            lightGunInputs[port].reset()
            if (platform.coreId != 0) requestedInputDevices.set(port, 0)
            appliedInputDevices[port] = -1
            desiredPadButtons.set(port, 0xFFFF)
            pendingPadTriggers[port].reset()
            pendingPadPressEdges.set(port, 0)
            padEdgeHoldMask[port] = 0
            padEdgeHoldFrames[port] = 0
        }
        running = true
        paused = true
        renderedFirstFrame = false
        sessionStartedAtNanos = startupStartedAtNanos
        var started = false
        try {
            val output = NativeAudioOutput()
            audioOutput = output
            hostPlayback.runIfForeground {
                output.play()
                paused = false
            }
            worker = thread(name = "EmuCoreH-Frame", isDaemon = true, start = true) {
                // The frame loop shares the CPU with the UI, background work
                // and the audio output. A display-level priority keeps the
                // emulated frame deadline stable under that contention while
                // staying below the audio thread, so mixing never waits on us.
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                runLoop(output)
            }
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.i(TAG, String.format(Locale.US, "Startup setup %.1f ms",
                (System.nanoTime() - startupStartedAtNanos) / 1_000_000.0))
            started = true
            return true
        } catch (error: Exception) {
            CrashLogger.logError(TAG, "Failed to start frame/audio runtime", error)
            return false
        } finally {
            if (!started) shutdownSession()
        }
    }

    private fun createSessionLocked(gamePath: String, biosOnly: Boolean,
                                    coreRenderer: Int): Boolean = sessionLock.withLock {
        // Publish core options before retro_init so the core's initial settings
        // load already sees them.
        if (platform.isSega) {
            pushAspectRatio(displayAspectRatioPreference() ?: ASPECT_RATIO_AUTO)
            pushShaderEffect()
            pushShaderPreset()
            context?.let { app ->
                val identity = launchIdentityPath ?: gamePath
                val displayName = if (identity.startsWith("content://"))
                    DocumentPathResolver.getDisplayName(app, identity) else File(identity).name
                bridge.nativeSetOption("jgenesis_save_id", SegaContentIdentity.batteryKey(identity, displayName))
                val values = SegaCoreOptions.persistedEntries(app) + launchCoreOptions
                SegaCoreOptions.options(platform).forEach { option ->
                    val value = values[option.key]?.takeIf { candidate -> option.choices.any { it.value == candidate } }
                        ?: option.defaultValue
                    bridge.nativeSetOption(option.key, value)
                }
            }
        } else if (platform.isSaturn) {
            pushAspectRatio(displayAspectRatioPreference() ?: ASPECT_RATIO_AUTO)
            pushShaderEffect()
            pushShaderPreset()
            context?.let { app ->
                val values = SaturnCoreOptions.persistedEntries(app) + launchCoreOptions
                SaturnCoreOptions.options().forEach { option ->
                    val value = values[option.key]?.takeIf { candidate -> option.choices.any { it.value == candidate } }
                        ?: option.defaultValue
                    bridge.nativeSetOption(option.key, value)
                }
            }
        } else if (platform.isModel3) {
            pushAspectRatio(displayAspectRatioPreference() ?: ASPECT_RATIO_AUTO)
            pushShaderEffect()
            pushShaderPreset()
            pushInternalResolution(startUpscalePreference())
        } else applyStartOptions(coreRenderer)
        val handle = bridge.createSession()
        if (handle == 0L) {
            if (platform.isSaturn) context?.let(SaturnContentPreparer::releasePreparedContent)
            else if (platform.isSega) context?.let(JGenesisContentPreparer::releasePreparedContent)
            if (platform.isModel3) context?.let(Model3ContentPreparer::releasePreparedContent)
            return@withLock false
        }
        // Attach the surface before loading content: SET_HW_RENDER fires inside
        // retro_load_game and needs a window to build the render context.
        if (bridge.setSurface(handle, surface, coreRenderer) != 0) {
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.e(TAG, "Failed to initialize ${RendererDefaults.coreRendererName(coreRenderer)} renderer")
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
            if (platform.isSaturn) context?.let(SaturnContentPreparer::releasePreparedContent)
            else if (platform.isSega) context?.let(JGenesisContentPreparer::releasePreparedContent)
            if (platform.isModel3) context?.let(Model3ContentPreparer::releasePreparedContent)
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
        val upscale = startUpscalePreference()
        pushInternalResolution(upscale)
        // These frontend defaults intentionally differ from standalone Flycast.
        // Publish them too, so a fresh gun profile displays the configured cursor.
        listOf("reicast_lightgun1_crosshair", "reicast_lightgun2_crosshair").forEach { key ->
            bridge.nativeSetOption(key, coreOptionValue(key) ?: "disabled")
        }
        // Explicit user choices from the settings / game manager / in-game menu
        // win over every derived default.
        FlycastCoreOptions.startupOptions(CoreOptionStore.persistedEntries(), launchCoreOptions).forEach { (key, value) ->
            bridge.nativeSetOption(key, value)
        }
        // A Dreamcast BIOS dump is optional: when none is installed the core
        // boots through its HLE BIOS so games still start.
        if (!DreamcastBios.hasBootRom(systemDirectory)) {
            bridge.nativeSetOption("reicast_hle_bios", "enabled")
        }
        // Internal resolution is owned by the app's per-game upscale setting, so
        // re-assert it after the persisted store so a stale entry cannot shadow it.
        pushInternalResolution(upscale)
    }

    private fun startUpscalePreference(): Float =
        settings["EmuCoreH/Display:Upscale"]?.toFloatOrNull()
            ?: settings["EmuCoreH:UpscaleMultiplier"]?.toFloatOrNull()
            ?: UPSCALE_DEFAULT

    private fun pushInternalResolution(preference: Float) {
        internalResolutionCoreOption(platform, preference)?.let { (key, value) ->
            bridge.nativeSetOption(key, value)
        }
    }

    /** Persists and forwards a Flycast core option. */
    fun setCoreOption(key: String, value: String) {
        if (key.startsWith("jgenesis_")) {
            context?.let { SegaCoreOptions.set(it, key, value) }
        } else if (key.startsWith("beetle_saturn_")) {
            context?.let { SaturnCoreOptions.set(it, key, value) }
        } else CoreOptionStore.set(key, value)
        applyCoreOption(key, value)
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
        if (key.startsWith("jgenesis_")) context?.let { SegaCoreOptions.value(it, key) }
            ?: SegaCoreOptions.options(platform).firstOrNull { it.key == key }?.defaultValue
        else if (key.startsWith("beetle_saturn_")) context?.let { SaturnCoreOptions.value(it, key) }
            ?: SaturnCoreOptions.options().firstOrNull { it.key == key }?.defaultValue
        else CoreOptionStore.value(key) ?: when (key) {
            "reicast_custom_textures" -> settings["EmuCoreH/Runtime:LoadTextureReplacements"]
            "reicast_preload_custom_textures" -> settings["EmuCoreH/Runtime:PrecacheTextureReplacements"]
            "reicast_dump_textures" -> settings["EmuCoreH/Runtime:DumpReplaceableTextures"]
            else -> null
        }?.toBooleanStrictOrNull()?.let { if (it) "enabled" else "disabled" }
            ?: FlycastCoreOptions.option(key)?.defaultValue

    /**
     * Forwards a core option without persisting it. Used for per-game overrides
     * that must not pollute the global option store.
     */
    fun applyCoreOption(key: String, value: String) {
        if (platform.isModel3) return
        if (platform.isSaturn) {
            if (key.startsWith("beetle_saturn_") && !SaturnCoreOptions.requiresRestart(key)) bridge.nativeSetOption(key, value)
            return
        }
        if (platform.isSega != key.startsWith("jgenesis_")) return
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
            ?: settings["EmuCoreH/Runtime:AspectRatio"]?.toIntOrNull()
    }

    private fun pushAspectRatio(type: Int) {
        val normalized = if (type in ASPECT_RATIO_STRETCH..ASPECT_RATIO_CUSTOM) type else ASPECT_RATIO_AUTO
        bridge.setDisplayAspectRatio(normalized)
    }

    private fun currentShaderEffect(): Int {
        val enabled = settings["EmuCoreH/Runtime:ShaderChainEnabled"]?.toBooleanStrictOrNull() == true
        if (!enabled) return RetroArchShaderEffects.NONE
        return RetroArchShaderEffects.classify(settings["EmuCoreH/Runtime:ShaderChainPreset"])
    }

    private fun pushShaderEffect() {
        runCatching { bridge.nativeSetShaderEffect(currentShaderEffect()) }
            .onFailure { if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Unable to apply shader effect", it) }
    }

    private fun pushShaderPreset() {
        val enabled = settings["EmuCoreH/Runtime:ShaderChainEnabled"]?.toBooleanStrictOrNull() == true
        val preset = settings["EmuCoreH/Runtime:ShaderChainPreset"].orEmpty()
        runCatching { bridge.nativeSetShaderPreset(if (enabled) preset else "", enabled) }
            .onFailure { if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Unable to apply shader preset", it) }
    }

    // Host intent must reach startup even while the VM is copying SAF content or
    // waiting for the native lifecycle lock. Foregrounding alone never resumes a menu.
    fun setHostForeground(foreground: Boolean) {
        hostPlayback.setForeground(foreground) {
            paused = true
            clearModel3TriggerPressure()
            audioOutput?.let { output -> runCatching { output.pause() } }
        }
    }

    fun pause() = lifecycleLock.withLock {
        clearModel3TriggerPressure()
        if (paused) return@withLock
        paused = true
        audioOutput?.pause()
    }

    fun resume() = lifecycleLock.withLock {
        if (!running || !paused) return@withLock
        hostPlayback.runIfForeground {
            resumeAudioIfRunning()
        }
    }

    private fun resumeAudioIfRunning() {
        audioOutput?.let { output ->
            synchronized(output) {
                // Recheck after taking the same monitor used by worker cleanup:
                // a queued resume/load must not restart a terminal worker's audio.
                if (running) {
                    output.play()
                    paused = false
                }
            }
        }
    }

    fun shutdown() = lifecycleLock.withLock {
        shutdownSession()
    }

    private fun shutdownSession() {
        val releaseSegaContent = (platform.isSega || platform.isSaturn) && session != 0L
        val releaseModel3Content = platform.isModel3 && session != 0L
        val activeWorker = worker
        check(activeWorker !== Thread.currentThread()) {
            "The frame worker cannot synchronously shut itself down"
        }
        running = false
        arcadeInputMode = -1
        setTimeControl(0)
        for (port in 0..1) {
            lightGunInputs[port].reset()
            appliedInputDevices[port] = -1
            desiredPadButtons.set(port, 0xFFFF)
            pendingPadTriggers[port].reset()
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
            if (releaseSegaContent) context?.let {
                if (platform.isSaturn) SaturnContentPreparer.releasePreparedContent(it)
                else JGenesisContentPreparer.releasePreparedContent(it)
            }
            if (releaseModel3Content) context?.let(Model3ContentPreparer::releasePreparedContent)
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
        if (!platform.supportsDisc) return false
        if (!isSupportedDiscPath(path, replacementOnly = true)) return false
        val app = context ?: return false
        val preparedPath = if (platform.isSaturn) {
            runCatching { SaturnContentPreparer.prepare(app, path) }.getOrNull() ?: return false
        } else if (platform.isSega) {
            runCatching { JGenesisContentPreparer.prepare(app, path) }.getOrNull() ?: return false
        } else if (path.startsWith("content://")) {
            SafStorageBridge.prepare(app, path) ?: return false
        } else path
        return runOnFrameThread {
            sessionLock.withLock {
                session != 0L && bridge.changeDisc(session, preparedPath) == 0
            }
        }
    }

    /** Native snapshot owns its lock; never requires a live/visible Android surface. */
    fun captureSoftwareFrame(): IntArray? = sessionLock.withLock {
        if (session == 0L || !platform.isSega) null else bridge.captureSoftwareFrame(session)
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
                val payload = CoreSaveState.unwrap(bytes, platform.coreId)
                    ?: error("Save state belongs to a different core or has an invalid header")
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
            if (!wasPaused && running) {
                hostPlayback.runIfForeground {
                    resumeAudioIfRunning()
                }
            }
        }
    }

    /** Writes the EmuCoreH state container (magic + version + libretro payload). */
    private fun writeSaveStateFile(rawFile: File, target: File): Boolean {
        val payload = runCatching { rawFile.readBytes() }.getOrNull() ?: return false
        val staging = File(target.parentFile, ".${target.name}.tmp")
        return try {
            staging.outputStream().use { output ->
                output.write(CoreSaveState.wrap(payload, platform.coreId))
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

    @Volatile private var lastCheatFilePath: String? = null

    fun loadCheats(path: String) {
        if (platform.isModel3 || platform.isSaturn) return
        lastCheatFilePath = path
        sessionLock.withLock { runCatching { bridge.loadCheats(path) } }
    }

    fun clearCheats() {
        lastCheatFilePath = null
        if (platform.isModel3 || platform.isSaturn) return
        sessionLock.withLock { runCatching { bridge.clearCheats() } }
    }

    fun setDataRootOverride(path: String?) {
        dataRootOverride = path?.takeIf(String::isNotBlank)
        if (platform.coreId != 0) return
        runCatching { bridge.setDataRootOverride(dataRootOverride) }
            .onFailure { if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Unable to set the Flycast data root", it) }
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

    /** A context change cancels taps; ordinary button release preserves them. */
    fun resetPadInput(port: Int) = sessionLock.withLock {
        if (port !in 0..1) return@withLock
        desiredPadButtons.set(port, 0xFFFF)
        pendingPadPressEdges.set(port, 0)
        padEdgeHoldMask[port] = 0
        padEdgeHoldFrames[port] = 0
        pendingPadAnalog.set(port, 0x80808080.toInt())
        pendingPadTriggers[port].reset()
        lightGunInputs[port].reset(recenter = false)
    }

    fun getArcadeInputMode(): Int = arcadeInputMode

    fun setPadTriggerPressure(port: Int, source: Int, right: Boolean, range: Int, pressed: Boolean) {
        if (platform.isModel3 && port in 0..1) pendingPadTriggers[port].update(source, right, range, pressed)
    }

    private fun clearModel3TriggerPressure() {
        if (platform.isModel3) for (port in 0..1) pendingPadTriggers[port].reset()
    }

    fun setArcadeControllerDevice(port: Int, device: Int) {
        if (port !in 0..1) return
        val normalized = device.coerceIn(0, 2)
        if (requestedInputDevices.getAndSet(port, normalized) != normalized) {
            lightGunInputs[port].reset()
        }
    }

    fun setLightGunAim(port: Int, x: Float, y: Float, offscreen: Boolean) {
        if (port in 0..1) lightGunInputs[port].setAim(x, y, offscreen)
    }

    fun setLightGunButton(port: Int, bit: Int, pressed: Boolean) {
        if (port in 0..1) lightGunInputs[port].setButton(bit, pressed)
    }

    fun resetLightGunInput(port: Int) {
        if (port in 0..1) lightGunInputs[port].reset(recenter = false)
    }

    fun setLightGunCursorEnabled(enabled: Boolean) { lightGunCursorEnabled = enabled }

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
                if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.e(TAG, "Failed to attach ${RendererDefaults.coreRendererName(activeCoreRenderer)} presentation surface")
        }
    }

    fun hasAttachedSurface(value: Surface, width: Int, height: Int): Boolean =
        sessionLock.withLock {
            session != 0L && surface === value && surfaceWidth == width && surfaceHeight == height
        }

    fun detachSurface() {
        sessionLock.withLock {
            if (session != 0L && bridge.setSurface(session, null, activeCoreRenderer) != 0)
                if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Failed to detach presentation surface")
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

    fun diagnostics(): String = if (com.sbro.emucoreh.BuildConfig.DEBUG) {
        sessionLock.withLock { bridge.getDiagnostics() }
    } else "{}"

    fun updateSetting(section: String, key: String, value: String): Boolean {
        if ((section == "EmuCoreH" || section == "EmuCoreH/Runtime") && key == "Renderer") {
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
        if (section == "EmuCoreH/Runtime" &&
            (key == "ShaderChainEnabled" || key == "ShaderChainPreset")) {
            pushShaderEffect()
            pushShaderPreset()
        }
        if (section == "EmuCoreH/Display" && key == "Upscale") {
            value.toFloatOrNull()?.let(::pushInternalResolution)
            return
        }
        if (platform.coreId != 0) return
        val bool = value.toBooleanStrictOrNull()
        val target: Pair<String, String>? = when ("$section:$key") {
            "EmuCoreH/Runtime:LoadTextureReplacements" ->
                bool?.let { "reicast_custom_textures" to if (it) "enabled" else "disabled" }
            "EmuCoreH/Runtime:PrecacheTextureReplacements" ->
                bool?.let { "reicast_preload_custom_textures" to if (it) "enabled" else "disabled" }
            "EmuCoreH/Runtime:DumpReplaceableTextures" ->
                bool?.let { "reicast_dump_textures" to if (it) "enabled" else "disabled" }
            "EmuCoreH/Display:AspectRatio" -> value.toIntOrNull()?.let { type ->
                setDisplayAspectRatio(type)
                null
            }
            else -> null
        }
        target?.let { (coreKey, coreValue) -> bridge.nativeSetOption(coreKey, coreValue) }
    }

    private fun publishPerformanceMetrics(fps: Double, frames: Int, frameNanos: Long,
                                          timing: EmulationTiming.Metrics?, audioStats: LongArray?,
                                          cpuLoadPercent: Double) {
        if (frames <= 0 || !performanceMetricsEnabled) return
        val softwareRenderer = activeCoreRenderer == RendererDefaults.CORE_SOFTWARE
        val targetFps = activeFrameRate
        val displayFps = timing?.fps ?: fps
        val speed = timing?.speedPercent ?: (fps / targetFps * 100.0)
        val renderer = if (platform.isSega) {
            "jgenesis ${RendererDefaults.coreRendererName(activeCoreRenderer)}"
        } else if (platform.isSaturn) {
            "Beetle Saturn"
        } else if (platform.isModel3) {
            "Supermodel"
        } else {
            RendererDefaults.coreRendererName(activeCoreRenderer)
        }
        val frameMs = frameNanos / frames / 1_000_000.0
        val gpuLoad = if (detailedPerformanceMetrics) GpuLoadReader.loadPercent() else null
        val overlay = buildString {
            append(String.format(Locale.US, "FPS:%.1f | Speed:%.1f%% | Target:%.2f", displayFps, speed, targetFps))
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
        performanceMetricsSnapshot = String.format(Locale.US, "%.3f\n%.3f\n%s", displayFps, speed, overlay)
    }

    private fun runLoop(output: NativeAudioOutput) {
        var metricsStartNanos = 0L
        var metricsFrames = 0
        var metricsFrameTotalNanos = 0L
        var metricsStartCpuMs = 0L
        val framePacer = FramePacer()
        val timingClock = EmulationTiming()
        var previousSourceFrames = output.stats()?.getOrNull(5) ?: 0L
        var lastRunSourceFrames = 0L
        // Guest rate of the last run that actually produced audio. Used when a
        // run uploads nothing: the console's reported refresh (a boot/attract
        // value on some arcade systems) must not re-pace the loop.
        var lastRunRate = 0.0
        var sourceSampleRate = 44100L
        var frameStartNanos = 0L
        var previousFrameStartNanos = 0L
        var metricsMaxIntervalNanos = 0L
        var metricsMaxCoreNanos = 0L
        var resetMetrics = true
        var frameHint: FramePerformanceHint? = null
        var hintAttempted = false
        var hintTargetNanos = 14_166_667L
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var nextVolumeCheckNanos = 0L
        var systemAudioMuted: Boolean? = null
        try {
            while (running) {
                // Owns the thread-affine EGL context, so any queued save/load
                // task must wait until the hardware context is ready here.
                // A false return is transient while no surface is attached;
                // only a real setup failure ends the session, with the native
                // side having written the reason to the crash log.
                if (!bridge.ensureHardwareContext() && bridge.hardwareContextFailed()) {
                    // Flycast-style fallback: a Vulkan context that cannot be
                    // created reboots the game on OpenGL instead of ending
                    // the session with an error. The ViewModel performs the
                    // relaunch so the renderer preference and UI stay in
                    // sync; anything else is a terminal failure.
                    if (requestRendererFallback()) {
                        if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Hardware context failed; requesting OpenGL fallback")
                        break
                    }
                    reportFailure(
                        "${RendererDefaults.coreRendererName(activeCoreRenderer)} initialization failed"
                    )
                    break
                }
                drainFrameTasks()
                if (paused || !hostPlayback.isForeground) {
                    frameHint?.close()
                    frameHint = null
                    hintAttempted = false
                    framePacer.reset()
                    resetMetrics = true
                    timingClock.reset()
                    lastRunSourceFrames = 0L
                    lastRunRate = 0.0
                    Thread.sleep(8)
                    continue
                }
                if (!hintAttempted) {
                    hintAttempted = true
                    frameHint = context?.let { createFramePerformanceHint(it, android.os.Process.myTid(), hintTargetNanos) }
                }
                val volumeCheckTime = System.nanoTime()
                if (volumeCheckTime >= nextVolumeCheckNanos) {
                    val muted = audioManager?.let {
                        runCatching { it.isStreamMute(AudioManager.STREAM_MUSIC) || it.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 }.getOrDefault(false)
                    } ?: false
                    if (muted != systemAudioMuted) {
                        output.setSystemMuted(muted)
                        systemAudioMuted = muted
                    }
                    nextVolumeCheckNanos = volumeCheckTime + 250_000_000L
                }
                // The frame limiter is mandatory: every game runs at the rate
                // reported by the core (59.94/50 Hz NTSC/PAL), and fast forward
                // multiplies that instead of uncapping the host speed.
                val frameLimitEnabled = true
                if (frameLimitEnabled) {
                    val timeMode = timeControlMode
                    val coreFrameRate = bridge.getFrameRate(session).takeIf { it > 1.0 } ?: 59.94
                    // A manual target rate overrides the console's reported one.
                    val manualTargetFps = settings["EmuCoreH/Runtime:TargetFps"]?.toIntOrNull() ?: 0
                    val frameRate = if (manualTargetFps in 20..120) {
                        manualTargetFps.toDouble()
                    } else {
                        coreFrameRate
                    }
                    activeFrameRate = frameRate
                    // Manual speed uses console refresh, not the detected scene FPS.
                    val audioPlaybackRate = EmulationTiming.speedMultiplier(coreFrameRate, manualTargetFps)
                    bridge.setAudioPlaybackRate(audioPlaybackRate)
                    if (frameRate > 1.0) {
                        // Use the guest time advanced by the previous run. Swap
                        // interval detection can lag behind scene transitions or
                        // be disabled; neither should change the console speed.
                        val runRate = EmulationTiming.pacingRate(
                            coreFrameRate, lastRunSourceFrames, sourceSampleRate, lastRunRate)
                        if (lastRunSourceFrames > 0L) lastRunRate = runRate
                        val pacedRate = when (timeMode) {
                            1 -> runRate * audioPlaybackRate * 3.0
                            2 -> 2.0
                            else -> runRate * audioPlaybackRate
                        }
                        if (pacedRate.isFinite() && pacedRate > 0.0) {
                            // Reserve part of each frame for presentation and scheduling jitter.
                            hintTargetNanos = (850_000_000.0 / pacedRate).toLong().coerceAtLeast(1L)
                        }
                        // The console speed is anchored to the sound card by the
                        // native audio push back pressure (standalone Flycast
                        // blocks in push() the same way). This deadline only
                        // keeps the loop from spinning while the core has
                        // nothing to present, e.g. during silent loading.
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
                // Release measures only the user-requested performance overlay.
                val collectMetrics = performanceMetricsEnabled
                val t0 = if (collectMetrics) System.nanoTime() else 0L
                var skippedPausedFrame = false
                val coreNanos = sessionLock.withLock {
                    if (!running || session == 0L) null else if (paused) {
                        skippedPausedFrame = true
                        null
                    } else {
                        for (port in 0..1) {
                            val device = ArcadeInputPolicy.controllerDevice(requestedInputDevices.get(port), arcadeInputMode, platform)
                            if (appliedInputDevices[port] != device) {
                                bridge.setControllerDevice(session, port, device)
                                appliedInputDevices[port] = device
                            }
                            val gun = lightGunInputs[port].nextFrame()
                            bridge.setLightGunState(session, port, gun.aim.x, gun.aim.y, gun.buttons, gun.aim.offscreen)
                            // Preserve a tap that began and ended between two
                            // guest frames, including cores that poll input
                            // less often than the frontend presents frames.
                            val pressedEdges = pendingPadPressEdges.getAndSet(port, 0)
                            if (pressedEdges != 0) {
                                padEdgeHoldMask[port] = padEdgeHoldMask[port] or pressedEdges
                                // Model 3 polls every 60 Hz frame; one frame is
                                // enough to deliver a tap and avoids holding a
                                // released pedal/button for ~50 ms.
                                padEdgeHoldFrames[port] = if (platform.isModel3) 1 else 3
                            }
                            val buttons = desiredPadButtons.get(port) and padEdgeHoldMask[port].inv()
                            bridge.setPadButtons(session, port, buttons)
                            if (padEdgeHoldFrames[port] > 0 && --padEdgeHoldFrames[port] == 0) {
                                padEdgeHoldMask[port] = 0
                            }
                            val analog = pendingPadAnalog.get(port)
                            if (platform.isModel3) {
                                val triggers = pendingPadTriggers[port].packed()
                                bridge.setPadTriggers(session, port, triggers and 255, triggers ushr 8)
                            }
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
                        val coreStartNanos = if (collectMetrics || com.sbro.emucoreh.BuildConfig.DEBUG || frameHint != null) System.nanoTime() else 0L
                        bridge.setLightGunCursorEnabled(lightGunCursorEnabled)
                        bridge.runFrame(session)
                        arcadeInputMode = bridge.getArcadeInputMode(session)
                        val elapsed = if (collectMetrics || com.sbro.emucoreh.BuildConfig.DEBUG || frameHint != null) System.nanoTime() - coreStartNanos else 0L
                        bridge.getDisplayRect(session)
                            ?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
                            ?.let {
                                frameWidth = it[2]
                                frameHeight = it[3]
                            }
                        if (!renderedFirstFrame) {
                            renderedFirstFrame = true
                            val startedAt = if (com.sbro.emucoreh.BuildConfig.DEBUG) sessionStartedAtNanos else 0L
                            if (startedAt != 0L) {
                                if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.i(TAG, String.format(Locale.US,
                                    "First emulated frame after %.1f ms (core %.1f ms)",
                                    (System.nanoTime() - startedAt) / 1_000_000.0,
                                    elapsed / 1_000_000.0))
                            }
                        }
                        elapsed
                    }
                } ?: if (skippedPausedFrame) continue else break
                frameHint?.report(coreNanos, hintTargetNanos)
                val frameNanos = if (collectMetrics) System.nanoTime() - t0 else 0L
                // Recover a disconnected stream in release builds as well.
                output.bufferedFrames()
                val audioStats = output.stats()
                val sampleNanos = if (collectMetrics) System.nanoTime() else 0L
                val sourceFrames = audioStats?.getOrNull(5) ?: previousSourceFrames
                lastRunSourceFrames = (sourceFrames - previousSourceFrames).coerceAtLeast(0L)
                previousSourceFrames = sourceFrames
                sourceSampleRate = audioStats?.getOrNull(8) ?: sourceSampleRate

                if (!collectMetrics) {
                    resetMetrics = true
                    continue
                }

                if (resetMetrics) {
                    metricsStartNanos = frameStartNanos
                    previousFrameStartNanos = frameStartNanos
                    metricsFrames = 0
                    metricsFrameTotalNanos = 0L
                    metricsMaxIntervalNanos = 0L
                    metricsMaxCoreNanos = 0L
                    metricsStartCpuMs = android.os.Process.getElapsedCpuTime()
                    resetMetrics = false
                    timingClock.sample(audioStats, sampleNanos)
                    continue
                }
                if (com.sbro.emucoreh.BuildConfig.DEBUG) {
                    metricsMaxIntervalNanos = maxOf(metricsMaxIntervalNanos, frameStartNanos - previousFrameStartNanos)
                    metricsMaxCoreNanos = maxOf(metricsMaxCoreNanos, coreNanos)
                    previousFrameStartNanos = frameStartNanos
                }
                metricsFrames++
                metricsFrameTotalNanos += frameNanos
                val now = frameStartNanos
                if (now - metricsStartNanos >= 250_000_000L) {
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
                        timingClock.sample(audioStats, sampleNanos), audioStats, cpuLoad)
                    if (com.sbro.emucoreh.BuildConfig.DEBUG) {
                        if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.d(TAG, "pacing fps=%.1f core=%.1fms queue=%d high=%d silence=%d maxInterval=%.1fms maxCore=%.1fms".format(
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
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.e(TAG, "Emulation frame loop stopped", error)
            reportFailure("Emulation frame loop stopped: ${error.javaClass.simpleName}: ${error.message.orEmpty()}", error)
        } finally {
            frameHint?.close()
            running = false
            // A failed worker retains its session for orderly UI cleanup, but
            // must not retain a playing stream after it stops producing audio.
            runCatching { output.pause() }
                .onFailure { if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Unable to pause stopped worker audio", it) }
            drainFrameTasks()
        }
    }

    private fun reportFailure(detail: String, throwable: Throwable? = null) {
        if (_failure.compareAndSet(null, RuntimeFailure(detail))) {
            CrashLogger.logError(TAG, detail, throwable)
        }
    }

    /**
     * adrenotools builds a linker namespace from the directory that holds its
     * hook libraries. Uncompressed native libraries (extractNativeLibs=false)
     * have no such directory on disk, so the hooks are unpacked from the APK
     * once. Without a custom driver the directory is never opened.
     */
    private fun adrenoToolsHookDirectory(app: Context): String {
        val nativeDirectory = app.applicationInfo.nativeLibraryDir
        if (!nativeDirectory.isNullOrBlank() && File(nativeDirectory).isDirectory) return nativeDirectory
        val hooksDirectory = File(app.filesDir, "adrenotools-hooks").apply { mkdirs() }
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        runCatching {
            java.util.zip.ZipFile(app.applicationInfo.sourceDir).use { archive ->
                ADRENOTOOLS_HOOK_LIBRARIES.forEach { name ->
                    val entry = archive.getEntry("lib/$abi/$name") ?: return@forEach
                    val target = File(hooksDirectory, name)
                    if (target.isFile && target.length() == entry.size) return@forEach
                    archive.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }.onFailure { CrashLogger.logError(TAG, "Unable to unpack the adrenotools hooks", it) }
        return hooksDirectory.absolutePath
    }

    private fun loadDisc(handle: Long, gamePath: String): Int {
        val app = context ?: return -1
        // SAF documents are exposed to the core through the frontend VFS: the
        // bridge hands out detached descriptors, so a multi-file CUE/GDI set is
        // streamed in place and nothing is copied into app storage.
        val path = if (platform.isModel3) {
            runCatching { Model3ContentPreparer.prepare(app, gamePath) }
                .onFailure { if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.e(TAG, "Unable to prepare Model 3 content", it) }.getOrNull() ?: return -1
        } else if (platform.isSaturn) {
            runCatching {
                SaturnBios.requireInstalled(app)
                SaturnContentPreparer.prepare(app, gamePath)
            }.onFailure { reportFailure(it.message ?: "Unable to prepare Saturn content", it) }.getOrNull() ?: return -1
        } else if (platform.isSega) {
            runCatching { JGenesisContentPreparer.prepare(app, gamePath) }
                .onFailure { if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.e(TAG, "Unable to prepare Sega content", it) }.getOrNull() ?: return -1
        } else if (gamePath.startsWith("content://")) {
            SafStorageBridge.prepare(app, gamePath) ?: return -1
        } else gamePath
        return bridge.loadDisc(handle, path)
    }

    fun hasDiscMedia(): Boolean = sessionLock.withLock {
        session != 0L && platform.supportsDisc && runCatching { bridge.hasDiscMedia(session) }.getOrDefault(false)
    }

    private fun isSupportedDiscPath(path: String, replacementOnly: Boolean = false): Boolean {
        val name = if (path.startsWith("content://")) {
            context?.let { DocumentPathResolver.getDisplayName(it, path) } ?: return false
        } else path
        return if (replacementOnly) GameFormats.isDiscImageName(name) else GameFormats.isLaunchableName(name)
    }

    private fun fitRect(containerWidth: Int, containerHeight: Int, contentWidth: Int, contentHeight: Int): Rect {
        val scale = minOf(containerWidth.toFloat() / contentWidth, containerHeight.toFloat() / contentHeight)
        val width = (contentWidth * scale).toInt().coerceAtLeast(1)
        val height = (contentHeight * scale).toInt().coerceAtLeast(1)
        val left = (containerWidth - width) / 2
        val top = (containerHeight - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private const val PAD_ANALOG_MODE_BIT = 1 shl 16
    private const val FRAME_PACING_SPIN_NANOS = 2_000_000L
    // App aspect-ratio preference values (mirrors the display settings UI).
    private const val ASPECT_RATIO_STRETCH = 0
    private const val ASPECT_RATIO_AUTO = 1
    private const val ASPECT_RATIO_CUSTOM = 4
}
