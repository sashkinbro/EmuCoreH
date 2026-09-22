
package com.sbro.emucoreh.core

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.DisplayCrop
import com.sbro.emucoreh.data.LearnedSerialRepository
import com.sbro.emucoreh.ui.common.invalidateCoverImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

object EmulatorBridge {
    private const val TAG = "EmulatorBridge"
    const val AUTO_RENDERER = RendererDefaults.AUTO
    const val OPENGL_RENDERER = RendererDefaults.OPENGL
    const val VULKAN_RENDERER = RendererDefaults.VULKAN
    private const val ANGLE_EGL_LIBRARY_NAME = "libEGL_angle.so"
    private const val ANGLE_GLES_LIBRARY_NAME = "libGLESv2_angle.so"
    private const val BOOT_SMOKE_PROBE_STEPS = 67_108_864

    private val aspectRatioSettingValues = mapOf(
        0 to "Stretch",
        1 to "Auto 4:3/3:2",
        2 to "4:3",
        3 to "16:9",
        4 to "10:7"
    )

    private val serialDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val serialScope = CoroutineScope(SupervisorJob() + serialDispatcher)
    private val inputScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var isNativeLoaded: Boolean = false
        private set

    @Volatile
    private var isVmActive: Boolean = false

    @Volatile
    private var lastSurface: Surface? = null

    @Volatile
    private var lastSurfaceWidth: Int = 0

    @Volatile
    private var lastSurfaceHeight: Int = 0

    @Volatile
    private var surfaceEventVersion: Long = 0

    private val _presentationSurfaceGeneration = MutableStateFlow(0L)
    val presentationSurfaceGeneration: StateFlow<Long> =
        _presentationSurfaceGeneration.asStateFlow()
    val runtimeFailure: StateFlow<RuntimeFailure?> get() = NativeApp.runtimeFailure

    @Volatile
    private var shutdownRequested: Boolean = false

    private var contextRef: WeakReference<Context>? = null
    private val settingsCache = HashMap<String, String>()
    private var audioVolumeSetting: Int = AudioDefaults.VOLUME_DEFAULT
    private var audioMutedSetting: Boolean = false
    private var audioBufferMsSetting: Int = AudioDefaults.BUFFER_MS_DEFAULT

    init {
        isNativeLoaded = NativeApp.hasNativeCore
        if (isNativeLoaded) {
            Log.i(TAG, "${NativeApp.loadedCoreLibraryName} is ready")
        } else {
            Log.e(TAG, "${NativeApp.loadedCoreLibraryName} is unavailable")
        }
    }

    private data class RuntimeOp(val kind: String, val fields: List<String>)
    private data class PreparedMetadataPath(
        val path: String,
        val descriptor: ParcelFileDescriptor? = null
    )

    private fun settingOp(section: String, key: String, type: String, value: String) =
        RuntimeOp("setting", listOf(section, key, type, value))

    private fun upscaleOp(value: Float) = RuntimeOp("upscale", listOf(value.toString()))

    private fun normalizeAspectRatio(type: Int): Int {
        return if (type in aspectRatioSettingValues.keys) type else 1
    }

    private fun aspectOp(type: Int) = RuntimeOp(
        "aspect",
        normalizeAspectRatio(type).let { listOf(it.toString(), aspectRatioSettingValues.getValue(it)) }
    )

    private fun customDriverOp(path: String) = RuntimeOp("custom_driver", listOf(path))

    private fun prepareCustomDriverLibrary(path: String): String {
        if (path.isBlank()) return ""
        val file = File(path)
        if (file.isFile) {
            file.setReadable(true, true)
            file.setWritable(true, true)
            file.setExecutable(true, true)
        }
        return path
    }

    fun isBundledAngleAvailable(): Boolean {
        val context = getContext() ?: return false
        val nativeLibDir = context.applicationInfo.nativeLibraryDir ?: return false
        return File(nativeLibDir, ANGLE_EGL_LIBRARY_NAME).isFile &&
            File(nativeLibDir, ANGLE_GLES_LIBRARY_NAME).isFile
    }

    private fun shouldUseMediatekAngleOpenGl(
        requested: Boolean,
        gpuHardwareProfile: Int,
        renderer: Int
    ): Boolean {
        return requested &&
            renderer == OPENGL_RENDERER &&
            GpuHardwareProfiles.isMediatekProfile(gpuHardwareProfile) &&
            isBundledAngleAvailable()
    }

    private fun refreshBiosOp() = RuntimeOp("refresh_bios", emptyList())

    private fun regionFramerateOps(ntscFramerate: Float, palFramerate: Float): List<RuntimeOp> {
        val ntsc = sanitizeFramerate(ntscFramerate, AppPreferences.DEFAULT_NTSC_FRAMERATE)
        val pal = sanitizeFramerate(palFramerate, AppPreferences.DEFAULT_PAL_FRAMERATE)
        return listOf(
            settingOp("EmuCoreH/GS", "FramerateNTSC", "float", ntsc.toString()),
            settingOp("EmuCoreH/GS", "FrameratePAL", "float", pal.toString())
        )
    }

    private fun rendererExecutionOps(renderer: Int): List<RuntimeOp> {
        val resolved = normalizeRenderer(renderer)
        return listOf(
            settingOp("EmuCoreH/GS", "Renderer", "int", resolved.toString())
        )
    }

    private suspend fun <T> runSerial(block: () -> T): T = withContext(serialDispatcher) { block() }

    private fun launchSerial(block: suspend () -> Unit) {
        serialScope.launch { block() }
    }

    private suspend fun performRuntimeOps(ops: List<RuntimeOp>): Boolean {
        if (!isNativeLoaded || ops.isEmpty()) return true
        return runSerial {
            var succeeded = true
            NativeApp.beginSettingsBatch()
            try {
                ops.forEach { op ->
                    when (op.kind) {
                        "setting" -> {
                            val section = op.fields.getOrNull(0) ?: return@forEach
                            val key = op.fields.getOrNull(1) ?: return@forEach
                            val type = op.fields.getOrNull(2) ?: return@forEach
                            val value = op.fields.getOrNull(3) ?: return@forEach
                            if (!applyAudioOutputSetting(section, key, value) &&
                                !NativeApp.setSetting(section, key, type, value)
                            ) succeeded = false
                        }
                        "upscale" -> {
                            val value = op.fields.firstOrNull()?.toFloatOrNull() ?: return@forEach
                            NativeApp.renderUpscalemultiplier(normalizeUpscale(value))
                        }
                        "aspect" -> {
                            val type = op.fields.firstOrNull()?.toIntOrNull() ?: return@forEach
                            NativeApp.setAspectRatio(type)
                            if (!NativeApp.setSetting(
                                "EmuCoreH/GS",
                                "AspectRatio",
                                "string",
                                op.fields.getOrNull(1) ?: aspectRatioSettingValues.getValue(1)
                            )) succeeded = false
                        }
                        "custom_driver" -> {
                            NativeApp.setCustomDriverPath(op.fields.firstOrNull().orEmpty())
                        }
                        "refresh_bios" -> {
                            NativeApp.refreshBIOS()
                        }
                    }
                }
            } finally {
                NativeApp.endSettingsBatch()
            }
            succeeded
        }
    }

    /**
     * Volume and mute are applied to the Kotlin PCM path because the core API
     * has no gain stage. Accepts both the settings-screen and in-game keys.
     */
    private fun applyAudioOutputSetting(section: String, key: String, value: String): Boolean {
        if (section != "SPU2/Output" && section != "EmuCoreH/Audio") return false
        when (key) {
            "StandardVolume", "Volume" -> {
                val volume = value.toIntOrNull() ?: return false
                audioVolumeSetting = volume.coerceIn(AudioDefaults.VOLUME_MIN, AudioDefaults.VOLUME_MAX)
            }
            "OutputMuted", "Mute" -> {
                audioMutedSetting = value.toBooleanStrictOrNull() ?: return false
            }
            "BufferMS", "AudioBufferMs" -> {
                val milliseconds = value.toIntOrNull() ?: return false
                audioBufferMsSetting = AudioDefaults.coerceBufferMs(milliseconds)
                NativeApp.setAudioBufferMs(audioBufferMsSetting)
                return true
            }
            "OutputLatencyMS", "AudioOutputLatencyMs" -> {
                val milliseconds = value.toIntOrNull() ?: return false
                NativeApp.setAudioOutputLatencyMs(
                    milliseconds.coerceIn(
                        AudioDefaults.OUTPUT_LATENCY_MS_MIN,
                        AudioDefaults.OUTPUT_LATENCY_MS_MAX
                    )
                )
                return true
            }
            "OutputLatencyMinimal", "MinimalOutputLatency" -> {
                val enabled = value.toBooleanStrictOrNull() ?: return false
                NativeApp.setAudioLowLatency(enabled)
                return true
            }
            else -> return false
        }
        NativeApp.setAudioOutputGain(audioVolumeSetting, audioMutedSetting)
        return true
    }

    private fun rendererName(renderer: Int): String = when (renderer) {
        AUTO_RENDERER -> "OpenGL"
        0 -> "OpenGL"
        OPENGL_RENDERER -> "OpenGL"
        13 -> "Software"
        VULKAN_RENDERER -> "Vulkan"
        15 -> "D3D12"
        3 -> "D3D11"
        else -> "Unknown($renderer)"
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.0.0"
    }

    internal fun normalizeRenderer(renderer: Int): Int {
        return RendererDefaults.normalizeAndroidRenderer(renderer)
    }

    fun getMaxUpscaleMultiplier(renderer: Int): Int {
        if (!isNativeLoaded) return UPSCALE_MAX.toInt()
        val resolvedRenderer = normalizeRenderer(renderer)
        return runCatching { NativeApp.getMaxUpscaleMultiplier(resolvedRenderer) }
            .getOrDefault(UPSCALE_MAX.toInt())
            .coerceAtLeast(UPSCALE_MIN.toInt())
    }

    fun initializeOnce(context: Context) {
        contextRef = WeakReference(context)
        if (!isNativeLoaded) {
            Log.e(TAG, "initializeOnce skipped: native library is not loaded")
            return
        }

        try {
            NativeApp.setNativeLibraryDir(context.applicationInfo.nativeLibraryDir ?: "")
            NativeApp.initializeOnce(context.applicationContext)
            NativeApp.setSetting("EmuCoreH", "AppVersion", "string", appVersionName(context.applicationContext))
            val jitSmokeOk = runCatching { NativeApp.runJitExecutableMemorySmokeTest() }.getOrDefault(false)
            Log.i(TAG, "JIT executable-memory smoke result=$jitSmokeOk")
            val (preferEnglishTitles, emulatorDataPath) = runBlocking {
                val preferences = AppPreferences(context.applicationContext)
                preferences.preferEnglishGameTitles.first() to preferences.getEmulatorDataPathSync()
            }
            NativeApp.setSetting("UI", "PreferEnglishGameTitles", "bool", preferEnglishTitles.toString())
            NativeApp.reloadDataRoot(emulatorDataPath ?: "")
            Log.i(TAG, "initializeOnce completed")
        } catch (error: Exception) {
            Log.e(TAG, "initializeOnce failed", error)
            CrashLogger.logError(TAG, "initializeOnce FAILED", error)
        }
    }

    fun getContext(): Context? = contextRef?.get()

    suspend fun applyRuntimeConfig(
        biosPath: String?,
        emulatorDataPath: String? = null,
        renderer: Int,
        upscaleMultiplier: Float,
        gpuDriverType: Int = 0,
        customDriverPath: String? = null,
        gpuHardwareProfile: Int = GpuHardwareProfiles.ADRENO,
        mediatekAngleOpenGl: Boolean = false,
        aspectRatio: Int = 1,
        localMultiplayerMode: Int = AppPreferences.LOCAL_MULTIPLAYER_OFF,
        displayCrop: DisplayCrop = DisplayCrop.None,
        audioVolume: Int = AudioDefaults.VOLUME_DEFAULT,
        audioFastForwardVolume: Int = AudioDefaults.VOLUME_DEFAULT,
        audioMuted: Boolean = false,
        audioOutputLatencyMs: Int = AudioDefaults.OUTPUT_LATENCY_MS_DEFAULT,
        audioMinimalOutputLatency: Boolean = AudioDefaults.MINIMAL_OUTPUT_LATENCY_DEFAULT,
        frameLimitEnabled: Boolean = true,
        vSyncEnabled: Boolean = false,
        fastForwardSpeed: Float = AppPreferences.DEFAULT_FAST_FORWARD_SPEED,
        targetFps: Int = 0,
        ntscFramerate: Float = AppPreferences.DEFAULT_NTSC_FRAMERATE,
        palFramerate: Float = AppPreferences.DEFAULT_PAL_FRAMERATE,
        shaderChainEnabled: Boolean = false,
        shaderChainPreset: String = "",
        pressureModifierAmount: Int = AppPreferences.DEFAULT_PRESSURE_MODIFIER_AMOUNT,
        memoryCardSlot1: String? = null,
        memoryCardSlot2: String? = null,
        autotestMode: Boolean = false
    ) = withContext(serialDispatcher) {
        if (!isNativeLoaded) return@withContext

        val context = getContext() ?: return@withContext
        val resolvedRenderer = normalizeRenderer(renderer)
        // The core reads its BIOS files from its own system directory, where
        // [DreamcastBios] installs the user's selection. Folder picks are
        // imported there directly instead of being staged per content URI.
        val resolvedBiosPath = biosPath?.let(DocumentPathResolver::resolveDirectoryPath)
        val preferredBiosFile = DocumentPathResolver.findPreferredBiosFileName(resolvedBiosPath)
        // Keep the native layer on the same data root as the runtime directories;
        // saves and memory cards previously ignored the configured location.
        NativeApp.reloadDataRoot(emulatorDataPath ?: "")
        val runtimeDirectories = EmulatorStorage.runtimeDirectories(context, emulatorDataPath)
        // Flycast keeps saves and memory cards under the configured data root.
        NativeApp.setDataRootOverride(runtimeDirectories.root.absolutePath)

        val normalizedGpuHardwareProfile = GpuHardwareProfiles.normalize(gpuHardwareProfile)
        val gpuHardwareProfileOverride = GpuHardwareProfiles.coreOverrideFor(normalizedGpuHardwareProfile)
        // This frontend uses the system EGL/OpenGL driver unless a custom driver is configured.
        val customDriverSupported = false
        val effectiveGpuDriverType = if (gpuDriverType == 1 && customDriverSupported) 1 else 0
        val effectiveMediatekAngleOpenGl = shouldUseMediatekAngleOpenGl(
            requested = mediatekAngleOpenGl,
            gpuHardwareProfile = normalizedGpuHardwareProfile,
            renderer = resolvedRenderer
        )
        NativeApp.setCrashContextString("emu_renderer_name", rendererName(resolvedRenderer))
        NativeApp.setCrashContextString("emu_gpu_driver_mode", if (effectiveGpuDriverType == 1) "custom" else "system")
        NativeApp.setCrashContextString("emu_gpu_profile", gpuHardwareProfileOverride)
        NativeApp.setCrashContextBool("emu_mediatek_angle_opengl", effectiveMediatekAngleOpenGl)
        val resolvedCustomDriverPath = if (effectiveGpuDriverType == 1) {
            prepareCustomDriverLibrary(customDriverPath.orEmpty())
        } else {
            ""
        }
        val prefs = AppPreferences(context)
        val effectiveFrameLimitEnabled = frameLimitEnabled
        val padVibrationEnabled = prefs.padVibration.first()
        val textureReplacementsEnabled = prefs.textureReplacementsEnabled.first()
        val textureReplacementsAsync = prefs.textureReplacementsAsync.first()
        val textureReplacementsPrecache = prefs.textureReplacementsPrecache.first()
        val textureDumpingEnabled = prefs.textureDumpingEnabled.first()
        val runtimeApplied = performRuntimeOps(
            buildList {
                addAll(rendererExecutionOps(resolvedRenderer))
                val pressureAmount = pressureModifierAmount.coerceIn(1, 100) / 100.0f
                add(settingOp("Pad1", "PressureModifier", "float", pressureAmount.toString()))
                add(settingOp("Pad2", "PressureModifier", "float", pressureAmount.toString()))
                add(upscaleOp(upscaleMultiplier))
                add(aspectOp(aspectRatio))
                add(
                    settingOp(
                        "EmuCoreH/GS",
                        "LocalMultiplayerMode",
                        "int",
                        localMultiplayerMode.coerceIn(
                            AppPreferences.LOCAL_MULTIPLAYER_OFF,
                            AppPreferences.LOCAL_MULTIPLAYER_HORIZONTAL_CROP_SWAPPED
                        ).toString()
                    )
                )
                add(settingOp("SPU2/Output", "StandardVolume", "int", AudioDefaults.coerceVolume(audioVolume).toString()))
                add(settingOp("SPU2/Output", "FastForwardVolume", "int", AudioDefaults.coerceVolume(audioFastForwardVolume).toString()))
                add(settingOp("SPU2/Output", "OutputMuted", "bool", audioMuted.toString()))
                add(settingOp("SPU2/Output", "OutputLatencyMS", "int", AudioDefaults.coerceOutputLatencyMs(audioOutputLatencyMs).toString()))
                add(settingOp("SPU2/Output", "OutputLatencyMinimal", "bool", audioMinimalOutputLatency.toString()))
                add(settingOp("Folders", "Bios", "string", resolvedBiosPath.orEmpty()))
                add(settingOp("Folders", "Savestates", "string", runtimeDirectories.saveStates.absolutePath))
                add(settingOp("Folders", "MemoryCards", "string", runtimeDirectories.memoryCards.absolutePath))
                add(settingOp("Folders", "Textures", "string", runtimeDirectories.textures.absolutePath))
                add(settingOp("Folders", "Cheats", "string", runtimeDirectories.cheats.absolutePath))
                add(settingOp("Folders", "Patches", "string", runtimeDirectories.patches.absolutePath))
                add(settingOp("Folders", "Logs", "string", runtimeDirectories.logs.absolutePath))
                add(settingOp("Filenames", "BIOS", "string", preferredBiosFile.orEmpty()))
                add(refreshBiosOp())
                add(settingOp("EmuCoreH", "OpenGLTextureDebugLog", "bool", (resolvedRenderer == 12).toString()))
                add(settingOp("EmuCoreH/GS", "AndroidGpuProfileOverride", "string", gpuHardwareProfileOverride))
                add(settingOp("EmuCoreH/GS", "AndroidUseAngleOpenGL", "bool", effectiveMediatekAngleOpenGl.toString()))
                add(settingOp("EmuCoreH/GS", "FrameLimitEnable", "bool", effectiveFrameLimitEnabled.toString()))
                add(settingOp("EmuCoreH/GS", "VsyncEnable", "bool", vSyncEnabled.toString()))
                addAll(targetFpsOps(targetFps, ntscFramerate, palFramerate))
                add(settingOp("Framerate", "NominalScalar", "float", "1.0"))
                add(settingOp("Framerate", "TurboScalar", "float", sanitizeFastForwardSpeed(fastForwardSpeed).toString()))
                add(settingOp("EmuCoreH/GS", "ShaderChainEnabled", "bool", (shaderChainEnabled && shaderChainPreset.isNotBlank()).toString()))
                add(settingOp("EmuCoreH/GS", "ShaderChainPreset", "string", shaderChainPreset.trim()))
                add(settingOp("EmuCoreH/GS", "LoadTextureReplacements", "bool", textureReplacementsEnabled.toString()))
                add(settingOp("EmuCoreH/GS", "LoadTextureReplacementsAsync", "bool", textureReplacementsAsync.toString()))
                add(settingOp("EmuCoreH/GS", "PrecacheTextureReplacements", "bool", textureReplacementsPrecache.toString()))
                add(settingOp("EmuCoreH/GS", "DumpReplaceableTextures", "bool", textureDumpingEnabled.toString()))
                add(settingOp("EmuCoreH/GS", "DumpTexturesWithFMVActive", "bool", "false"))
                add(settingOp("EmuCoreH/GS", "DisableShaderCache", "bool", "false"))
                add(settingOp("EmuCoreH", "BiosSource", "string", biosPath.orEmpty()))
                add(settingOp("EmuCoreH", "Renderer", "int", resolvedRenderer.toString()))
                add(settingOp("EmuCoreH", "UpscaleMultiplier", "float", upscaleMultiplier.toString()))
                add(settingOp("EmuCoreH", "GpuHardwareProfile", "int", normalizedGpuHardwareProfile.toString()))
                add(settingOp("EmuCoreH", "HasContext", "bool", (context.applicationContext != null).toString()))
                add(settingOp("EmuCoreH", "AutotestMode", "bool", autotestMode.toString()))
                add(settingOp("EmuCoreH", "ProfilerLogcat", "bool", prefs.profilerLogcatSync().toString()))
                add(settingOp("EmuCoreH", "AppVersion", "string", appVersionName(context)))
                add(settingOp("EmuCoreH", "WarnAboutUnsafeSettings", "bool", "false"))
                add(settingOp("InputSources", "PadVibration", "bool", padVibrationEnabled.toString()))
                add(customDriverOp(resolvedCustomDriverPath))
            }
        )
        // Crop is applied by the frontend presenter, not the core option set,
        // so it is pushed straight to the native bridge.
        NativeApp.setDisplayCrop(displayCrop.sanitized())
        if (runtimeApplied) {
            settingsCache["EmuCoreH/GS:Renderer"] = resolvedRenderer.toString()
        }
    }

    suspend fun startEmulation(
        path: String,
        saveStateIdentityPath: String? = null,
        bootSmokeProbe: Boolean = false,
        allowBiosBoot: Boolean = false
    ): Boolean {
        if (!isNativeLoaded) {
            Log.e(TAG, "startEmulation skipped: native library is not loaded")
            return false
        }
        if (path.isBlank() && !allowBiosBoot && !bootSmokeProbe) {
            Log.e(TAG, "startEmulation rejected blank game path")
            return false
        }
        // Save-state files are named after the path the user launched, not the
        // core's prepared/materialized path, so writes and listings agree.
        NativeApp.setSaveStateIdentityPath(saveStateIdentityPath ?: path)
        val isElf = when {
            path.substringAfterLast('.', "").equals("elf", ignoreCase = true) -> true
            path.startsWith("content://") -> {
                val context = getContext()
                val displayName = context?.let { DocumentPathResolver.getDisplayName(it, path) }.orEmpty()
                displayName.substringAfterLast('.', "").equals("elf", ignoreCase = true)
            }
            else -> false
        }
        val isIrx = when {
            path.substringAfterLast('.', "").equals("irx", ignoreCase = true) -> true
            path.startsWith("content://") -> {
                val context = getContext()
                val displayName = context?.let { DocumentPathResolver.getDisplayName(it, path) }.orEmpty()
                displayName.substringAfterLast('.', "").equals("irx", ignoreCase = true)
            }
            else -> false
        }
        val isExeExecutable = when {
            path.substringAfterLast('.', "").let { it.equals("exe", true) || it.equals("psexe", true) || it.equals("cpe", true) } -> true
            path.startsWith("content://") -> {
                val context = getContext()
                val displayName = context?.let { DocumentPathResolver.getDisplayName(it, path) }.orEmpty()
                displayName.substringAfterLast('.', "").let {
                    it.equals("exe", true) || it.equals("psexe", true) || it.equals("cpe", true)
                }
            }
            else -> false
        }
        val pathType = when {
            path.startsWith("content://") -> "content"
            path.isBlank() -> "bios"
            else -> "file"
        }
        NativeApp.logCrashBreadcrumb("startEmulation requested pathType=$pathType vmActive=$isVmActive")
        Log.i(TAG, "startEmulation requested pathType=$pathType bootSmoke=$bootSmokeProbe vmActive=$isVmActive")

        return runSerial {
            isVmActive = true
            shutdownRequested = false
            var result = try {
                NativeApp.logCrashBreadcrumb(
                    "startEmulation entering native ${
                        when {
                            bootSmokeProbe -> "runBootSmokeProbe"
                            isElf -> "bootElf"
                            isIrx -> "bootIrx"
                            else -> "runVMThread"
                        }
                    }"
                )
                when {
                    bootSmokeProbe -> NativeApp.runBootSmokeProbe(path, BOOT_SMOKE_PROBE_STEPS) != 0
                    isElf -> NativeApp.bootElf(path)
                    isIrx -> NativeApp.bootIrx(path)
                    else -> NativeApp.runVMThread(path)
                }
            } catch (error: Exception) {
                NativeApp.logCrashBreadcrumb("startEmulation exception before native start returned")
                Log.e(TAG, "startEmulation native call failed", error)
                false
            }
            if (result && !bootSmokeProbe && !allowBiosBoot && !isElf && !isIrx && !isExeExecutable && !path.isBlank()) {
                // The bundled core silently boots the BIOS when a disc image
                // cannot be opened. Surface that as a failed launch instead of
                // leaving the user on a misleading BIOS screen.
                if (!NativeApp.hasDiscMedia()) {
                    NativeApp.logCrashBreadcrumb("disc image failed to mount; aborting launch")
                    Log.w(TAG, "Disc image could not be mounted; aborting $pathType launch")
                    runCatching { NativeApp.shutdown() }
                    result = false
                }
            }
            isVmActive = NativeApp.hasOwnedVm()
            if (!isVmActive) {
                DocumentPathResolver.releasePreparedLaunchHandles()
            }
            if (result && !bootSmokeProbe && !allowBiosBoot && !isElf && !isIrx && !isExeExecutable &&
                !path.isBlank()
            ) {
                recordLearnedGameSerial(path)
            }
            NativeApp.logCrashBreadcrumb("startEmulation finished result=$result")
            Log.i(TAG, "startEmulation finished result=$result")
            result
        }
    }

    /**
     * CHD and similar containers hide the disc product code from the library
     * scanner, so the serial the core reported while playing is stored for the
     * cheat and texture catalogs.
     */
    private fun recordLearnedGameSerial(path: String) {
        val serial = NativeApp.currentGameSerial() ?: return
        val context = getContext() ?: return
        val fileName = if (path.startsWith("content://")) {
            DocumentPathResolver.getDisplayName(context, path)
        } else {
            File(path).name
        }
        runCatching {
            LearnedSerialRepository.forContext(context).record(fileName, serial)
            Log.i(TAG, "Learned serial $serial for $fileName")
        }
    }


    suspend fun pause() {
        if (!isNativeLoaded || !isVmActive) return
        runSerial {
            try {
                NativeApp.pause()
            } catch (_: Exception) { }
        }
    }

    suspend fun resume() {
        if (!isNativeLoaded || !isVmActive) return
        runSerial {
            try {
                rebindSurface()
                NativeApp.resume()
            } catch (_: Exception) { }
        }
    }

    suspend fun startJitProfiler() {
        if (!isNativeLoaded || !isVmActive) return
        runSerial {
            try {
                NativeApp.startJitProfiler()
            } catch (_: Exception) { }
        }
    }

    suspend fun stopJitProfiler() {
        if (!isNativeLoaded || !isVmActive) return
        runSerial {
            try {
                NativeApp.stopJitProfiler()
            } catch (_: Exception) { }
        }
    }

    suspend fun isJitProfilerActive(): Boolean {
        if (!isNativeLoaded || !isVmActive) return false
        return runSerial {
            try {
                NativeApp.isJitProfilerActive()
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun startHangTrace() {
        if (!isNativeLoaded || !isVmActive) return
        runSerial {
            try {
                NativeApp.startHangTrace()
            } catch (_: Exception) { }
        }
    }

    suspend fun stopHangTrace() {
        if (!isNativeLoaded || !isVmActive) return
        runSerial {
            try {
                NativeApp.stopHangTrace()
            } catch (_: Exception) { }
        }
    }

    suspend fun isHangTraceActive(): Boolean {
        if (!isNativeLoaded || !isVmActive) return false
        return runSerial {
            try {
                NativeApp.isHangTraceActive()
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun shutdown() {
        if (!isNativeLoaded) return
        runSerial {
            if (shutdownRequested) return@runSerial
            shutdownRequested = true
            try {
                NativeApp.shutdown()
                isVmActive = false
                DocumentPathResolver.releasePreparedLaunchHandles()
            } finally {
                // A failed teardown must retain ownership and its descriptors.
                shutdownRequested = false
            }
        }
    }

    fun hasValidVm(): Boolean {
        if (!isNativeLoaded) return false
        return try {
            NativeApp.hasValidVm()
        } catch (_: Exception) {
            false
        }
    }

    fun isVmActive(): Boolean {
        // A status read never performs teardown. The native handle can outlive
        // a failed worker and still needs its audio/session/descriptors released.
        return isVmActive || (isNativeLoaded && NativeApp.hasOwnedVm())
    }

    fun getPadRumble(port: Int): FloatArray? {
        if (!isNativeLoaded) return null
        return try {
            NativeApp.getPadRumble(port)
        } catch (_: Exception) {
            null
        }
    }

    fun getGameTitle(path: String): String = getGameMetadata(path).title

    /**
     * [readDiscMetadata] opens the disc image to read its real serial. Bulk
     * library scans pass false: opening every image would make the first scan
     * dramatically slower, and the filename/title-index serial is enough to
     * list the library.
     */
    fun getGameMetadata(path: String, readDiscMetadata: Boolean = true): GameMetadata {
        val inferredMetadata = when {
            path.startsWith("content://") -> {
                val context = getContext()
                val displayName = context?.let { DocumentPathResolver.getDisplayName(it, path) } ?: path
                parseMetadataFromName(displayName)
            }
            else -> parseMetadataFromName(File(path).nameWithoutExtension)
        }
        val extensionSource = if (path.startsWith("content://")) {
            getContext()?.let { DocumentPathResolver.getDisplayName(it, path) } ?: path
        } else {
            path
        }
        val extension = extensionSource.substringAfterLast('.', "").lowercase()

        if (!readDiscMetadata || !isNativeLoaded) return inferredMetadata
        if (isVmActive) return inferredMetadata
        if (extension == "elf") return inferredMetadata

        getContext()?.let { context ->
            runCatching { GameMetadataReader.read(context, path) }.getOrNull()?.let { metadata ->
                return GameMetadata(
                    title = metadata.title?.let { normalizeNativeGameTitle(it, inferredMetadata.title) }
                        ?: inferredMetadata.title,
                    serial = metadata.serial ?: inferredMetadata.serial
                )
            }
        }

        val preparedPath = prepareMetadataPathForNative(path) ?: return inferredMetadata
        Log.i(TAG, "getGameMetadata native lookup path=$path vmActive=$isVmActive ext=$extension")
        return try {
            val rawTitle = NativeApp.getGameTitle(preparedPath.path).orEmpty()
            Log.i(TAG, "getGameMetadata native result=$rawTitle")
            val segments = rawTitle.split('|')
            val nativeTitle = segments.getOrNull(0).orEmpty()
            val nativeSerial = segments.getOrNull(1)?.takeIf { it.isNotBlank() }
            val nativeSerialWithCrc = segments.getOrNull(2)?.takeIf { it.isNotBlank() }
            if (isFdMetadataArtifact(preparedPath.path, nativeTitle, nativeSerial)) {
                return inferredMetadata
            }
            val title = nativeTitle
                .takeIf { it.isNotBlank() }
                ?.let { normalizeNativeGameTitle(it, inferredMetadata.title) }
                ?: inferredMetadata.title
            GameMetadata(
                title = title,
                serial = nativeSerial ?: inferredMetadata.serial,
                serialWithCrc = nativeSerialWithCrc ?: inferredMetadata.serialWithCrc
            )
        } catch (_: Exception) {
            inferredMetadata
        } finally {
            preparedPath.descriptor?.close()
        }
    }

    private fun prepareMetadataPathForNative(path: String): PreparedMetadataPath? {
        if (!path.startsWith("content://")) return PreparedMetadataPath(path)
        val context = getContext() ?: return null
        val directPath = DocumentPathResolver.resolveFilePath(context, path)
            ?.let(::File)
            ?.takeIf { it.isFile && it.canRead() }
            ?.absolutePath
        if (!directPath.isNullOrBlank()) return PreparedMetadataPath(directPath)

        return PreparedMetadataPath(path)
    }

    private fun isFdMetadataArtifact(path: String, nativeTitle: String, nativeSerial: String?): Boolean {
        if (!path.startsWith("/proc/self/fd/")) return false
        if (!nativeSerial.isNullOrBlank()) return false
        val trimmedTitle = nativeTitle.substringBefore('|').trim()
        return trimmedTitle.isBlank() || trimmedTitle.all(Char::isDigit)
    }

    private fun normalizeNativeGameTitle(rawTitle: String, fallbackTitle: String): String {
        return cleanGameDisplayTitle(rawTitle, fallbackTitle)
    }

    fun cleanGameDisplayTitle(rawTitle: String?, fallbackNameOrPath: String? = null): String {
        val fallbackDisplay = fallbackNameOrPath
            ?.takeIf { it.isNotBlank() }
            ?.let { value ->
                if (value.startsWith("content://")) {
                    getContext()?.let { context -> DocumentPathResolver.getDisplayName(context, value) }
                        ?: DocumentPathResolver.normalizeDisplayName(value)
                } else {
                    DocumentPathResolver.normalizeDisplayName(value)
                }
            }
            .orEmpty()
        val fallbackTitle = parseMetadataFromName(fallbackDisplay).title
        val trimmed = rawTitle.orEmpty().trim()
        val source = if (trimmed.isBlank() || looksLikeStoragePath(trimmed)) {
            fallbackDisplay.ifBlank { trimmed }
        } else {
            trimmed
        }
        val normalized = if (looksLikeStoragePath(source)) {
            DocumentPathResolver.normalizeDisplayName(source)
        } else {
            source
        }
        return parseMetadataFromName(normalized).title.ifBlank { fallbackTitle }
    }

    private fun looksLikeStoragePath(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.contains("%2F", ignoreCase = true) ||
            trimmed.contains("%3A", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("primary:", ignoreCase = true) ||
            trimmed.startsWith("home:", ignoreCase = true) ||
            trimmed.startsWith("raw:", ignoreCase = true) ||
            trimmed.contains("/storage/", ignoreCase = true)
    }

    fun parseMetadataFromName(rawName: String): GameMetadata {
        val ext = rawName.substringAfterLast('.', "").lowercase()
        val cleanName = if (ext in setOf("iso", "bin", "cue", "img", "mdf", "gz", "cso", "zso", "chd", "elf", "pbp", "prx", "plf", "zip")) {
            rawName.substringBeforeLast('.').trim()
        } else {
            rawName.trim()
        }
        val serial = extractSerialFromName(cleanName)
        val title = cleanName
            .replace(Regex("""(?i)\b([A-Z]{4})[-_. ]?(\d{3})[-_. ]?(\d{2})\b"""), " ")
            .replace(Regex("""(?i)\b([A-Z]{4})[-_. ]?(\d{5})\b"""), " ")
            .replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ")
            .replace(Regex("""\b(disc|disk|cd|dvd)\s*\d+\b""", RegexOption.IGNORE_CASE), " ")
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { cleanName }
        return GameMetadata(title = title, serial = serial, serialWithCrc = serial)
    }

    private fun extractSerialFromName(value: String): String? {
        val normalized = value.uppercase(Locale.ROOT)
        val fullPattern = Regex("""\b([A-Z]{4})[-_. ]?(\d{3})[-_. ]?(\d{2})\b""")
        val compactPattern = Regex("""\b([A-Z]{4})[-_. ]?(\d{5})\b""")
        return fullPattern.find(normalized)?.let { match ->
            "${match.groupValues[1]}-${match.groupValues[2]}${match.groupValues[3]}"
        } ?: compactPattern.find(normalized)?.let { match ->
            "${match.groupValues[1]}-${match.groupValues[2]}"
        }
    }

    suspend fun saveState(slot: Int): Boolean {
        if (!isNativeLoaded || !isVmActive) return false
        val saved = runSerial {
            try {
                NativeApp.saveStateToSlot(slot)
            } catch (_: Exception) {
                false
            }
        }
        if (saved) {
            val savePath = runCatching { NativeApp.getCurrentSaveStatePath(slot) }.getOrNull()
            if (!savePath.isNullOrBlank()) {
                captureSaveStatePreview(savePath)
            }
        }
        return saved
    }

    private suspend fun captureSaveStatePreview(savePath: String) {
        val surface = lastSurface
        val width = lastSurfaceWidth
        val height = lastSurfaceHeight
        if (surface == null || !surface.isValid || width <= 0 || height <= 0) return
        val bitmap = withContext(Dispatchers.Main) {
            runCatching {
                val frame = createSaveStatePreviewBitmap(width, height)
                val copied = suspendCancellableCoroutine { continuation ->
                    try {
                        PixelCopy.request(
                            surface,
                            frame,
                            { result ->
                                if (continuation.isActive) {
                                    continuation.resume(result == PixelCopy.SUCCESS)
                                }
                            },
                            Handler(Looper.getMainLooper())
                        )
                    } catch (_: Throwable) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                if (copied) {
                    frame
                } else {
                    frame.recycle()
                    null
                }
            }.getOrNull()
        } ?: return
        val preview = bitmap
        withContext(Dispatchers.IO) {
            runCatching {
                FileOutputStream("$savePath.png").use { output ->
                    preview.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                invalidateCoverImage("$savePath.png")
            }
            preview.recycle()
        }
    }

    suspend fun loadState(slot: Int): Boolean {
        if (!isNativeLoaded || !isVmActive) return false
        return runSerial {
            try {
                val success = NativeApp.loadStateFromSlot(slot)
                if (success) {
                    runCatching { rebindSurface() }
                    runCatching { NativeApp.resume() }
                }
                success
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Replaces the mounted PS2 disc without restarting the VM. Native code performs
     * the CDVD mutation on the CPU thread and restores the previous image on failure.
     */
    suspend fun changeDisc(path: String): Boolean {
        if (!isNativeLoaded || !isVmActive || path.isBlank()) return false
        return runSerial {
            try {
                NativeApp.logCrashBreadcrumb("disc swap requested pathType=${path.substringBefore(':', "file")}")
                NativeApp.changeDisc(path)
            } catch (error: Exception) {
                Log.e(TAG, "Disc swap failed", error)
                false
            }
        }
    }

    suspend fun setRenderer(gpuType: Int): Boolean {
        val resolvedRenderer = normalizeRenderer(gpuType)
        val cacheKey = "EmuCoreH/GS:Renderer"
        val rendererChanged = settingsCache[cacheKey] != resolvedRenderer.toString()
        NativeApp.logCrashBreadcrumb(
            "renderer change requested renderer=${rendererName(resolvedRenderer)}($resolvedRenderer) vmActive=$isVmActive"
        )
        Log.i(TAG, "Renderer change requested: ${rendererName(resolvedRenderer)}($resolvedRenderer) vmActive=$isVmActive")
        val switched = performRuntimeOps(rendererExecutionOps(resolvedRenderer))
        if (!switched) return false

        settingsCache[cacheKey] = resolvedRenderer.toString()
        if (isVmActive && rendererChanged) {
            // The core re-negotiates the renderer only on boot, so the in-game
            // switch is a session restart on the new backend. The restarted
            // session binds the SurfaceView window itself, so no extra surface
            // rebind is needed here (a second bind would churn the context).
            val restarted = runSerial { NativeApp.restartRenderer(resolvedRenderer) }
            isVmActive = NativeApp.hasOwnedVm()
            if (!restarted) {
                Log.e(TAG, "Renderer restart failed for ${rendererName(resolvedRenderer)}")
                return false
            }
        }
        return true
    }

    suspend fun setUpscaleMultiplier(multiplier: Float) {
        val normalized = normalizeUpscale(multiplier)
        settingsCache["EmuCoreH/GS:upscale_multiplier"] = normalized.toString()
        performRuntimeOps(listOf(upscaleOp(normalized), settingOp("EmuCoreH/GS", "upscale_multiplier", "float", normalized.toString())))
    }

    suspend fun setAspectRatio(type: Int) {
        val normalizedType = normalizeAspectRatio(type)
        val value = aspectRatioSettingValues.getValue(normalizedType)
        settingsCache["EmuCoreH/GS:AspectRatio"] = value
        performRuntimeOps(listOf(aspectOp(normalizedType)))
    }

    suspend fun setDisplayCrop(value: DisplayCrop) {
        val crop = value.sanitized()
        settingsCache["EmuCoreH/GS:CropLeft"] = crop.left.toString()
        settingsCache["EmuCoreH/GS:CropTop"] = crop.top.toString()
        settingsCache["EmuCoreH/GS:CropRight"] = crop.right.toString()
        settingsCache["EmuCoreH/GS:CropBottom"] = crop.bottom.toString()
        NativeApp.setDisplayCrop(crop)
    }

    suspend fun setLocalMultiplayerMode(mode: Int) {
        val normalized = mode.coerceIn(
            AppPreferences.LOCAL_MULTIPLAYER_OFF,
            AppPreferences.LOCAL_MULTIPLAYER_HORIZONTAL_CROP_SWAPPED
        )
        settingsCache["EmuCoreH/GS:LocalMultiplayerMode"] = normalized.toString()
        performRuntimeOps(
            listOf(settingOp("EmuCoreH/GS", "LocalMultiplayerMode", "int", normalized.toString()))
        )
    }

    suspend fun setCustomDriverPath(path: String) {
        // The current PPSSPP frontend uses the system EGL/OpenGL driver.
        settingsCache["EmuCoreH/GS:CustomDriverPath"] = ""
        performRuntimeOps(listOf(customDriverOp("")))
    }

    suspend fun setFrameLimitEnabled(enabled: Boolean) {
        if (!isNativeLoaded) return
        val value = enabled.toString()
        val cacheKey = "EmuCoreH/GS:FrameLimitEnable"
        if (settingsCache[cacheKey] == value) return
        runSerial {
            NativeApp.setFrameLimitEnabled(enabled)
        }
        settingsCache[cacheKey] = value
    }

    suspend fun setVSyncEnabled(enabled: Boolean) {
        setSetting("EmuCoreH/GS", "VsyncEnable", "bool", enabled.toString())
    }

    suspend fun setFastForwardSpeed(value: Float) {
        setSetting("Framerate", "TurboScalar", "float", sanitizeFastForwardSpeed(value).toString())
    }


    suspend fun setTargetFps(
        targetFps: Int,
        ntscFramerate: Float = AppPreferences.DEFAULT_NTSC_FRAMERATE,
        palFramerate: Float = AppPreferences.DEFAULT_PAL_FRAMERATE
    ) {
        performRuntimeOps(
            buildList {
                addAll(targetFpsOps(targetFps, ntscFramerate, palFramerate))
                add(settingOp("Framerate", "NominalScalar", "float", "1.0"))
            }
        )
    }

    fun setPadButton(padIndex: Int, index: Int, range: Int, pressed: Boolean) {
        if (!isNativeLoaded) return
        try {
            NativeApp.setPadButton(padIndex, index, range, pressed)
        } catch (_: Exception) { }
    }

    fun resetKeyStatus() {
        if (!isNativeLoaded || !isVmActive) return
        launchSerial {
            if (!isVmActive || !runCatching { NativeApp.hasValidVm() }.getOrDefault(false)) return@launchSerial
            try {
                NativeApp.resetKeyStatus()
            } catch (_: Exception) { }
        }
    }

    fun resetPadState(padIndex: Int) {
        if (!isNativeLoaded || !isVmActive) return
        launchSerial {
            if (!isVmActive || !runCatching { NativeApp.hasValidVm() }.getOrDefault(false)) return@launchSerial
            try {
                NativeApp.resetPadState(padIndex)
            } catch (_: Exception) { }
        }
    }

    suspend fun setPadVibration(enabled: Boolean) {
        setSetting("InputSources", "PadVibration", "bool", enabled.toString())
    }

    fun onSurfaceCreated(generation: Long) {
        if (!isNativeLoaded) return
        if (generation != _presentationSurfaceGeneration.value) return
        Log.i(TAG, "onSurfaceCreated: generation=$generation")
        NativeApp.setCrashContextString("emu_surface_state", "created")
        NativeApp.logCrashBreadcrumb("surfaceCreated")
        launchSerial {
            try {
                NativeApp.onNativeSurfaceCreated()
                Log.i(TAG, "onSurfaceCreated: native callback done")
            } catch (e: Exception) {
                Log.e(TAG, "onSurfaceCreated: native callback failed", e)
            }
        }
    }

    fun onSurfaceChanged(surface: Surface, width: Int, height: Int, generation: Long) {
        if (!isNativeLoaded) return
        if (generation != _presentationSurfaceGeneration.value) {
            Log.i(TAG, "Ignoring stale surfaceChanged generation=$generation")
            return
        }
        val eventVersion = ++surfaceEventVersion
        Log.i(TAG, "onSurfaceChanged: width=$width height=$height valid=${surface.isValid} generation=$generation eventVersion=$eventVersion")
        lastSurface = surface
        lastSurfaceWidth = width
        lastSurfaceHeight = height
        NativeApp.setCrashContextString("emu_surface_state", "changed")
        NativeApp.setCrashContextInt("emu_surface_width", width)
        NativeApp.setCrashContextInt("emu_surface_height", height)
        NativeApp.setCrashContextBool("emu_surface_valid", surface.isValid)
        NativeApp.logCrashBreadcrumb("surfaceChanged width=$width height=$height valid=${surface.isValid}")
        launchSerial {
            if (surfaceEventVersion != eventVersion) {
                Log.w(TAG, "onSurfaceChanged: eventVersion mismatch ($eventVersion != $surfaceEventVersion), skipping")
                return@launchSerial
            }
            try {
                NativeApp.onNativeSurfaceChanged(surface, width, height)
                Log.i(TAG, "onSurfaceChanged: native callback done")
            } catch (e: Exception) {
                Log.e(TAG, "onSurfaceChanged: native callback failed", e)
            }
        }
    }

    fun onSurfaceDestroyed(generation: Long) {
        if (!isNativeLoaded) return
        if (generation != _presentationSurfaceGeneration.value) {
            Log.i(TAG, "Ignoring stale surfaceDestroyed generation=$generation")
            return
        }
        val oldVersion = surfaceEventVersion
        ++surfaceEventVersion
        lastSurface = null
        lastSurfaceWidth = 0
        lastSurfaceHeight = 0
        Log.i(TAG, "onSurfaceDestroyed: called, version $oldVersion -> $surfaceEventVersion")
        NativeApp.setCrashContextString("emu_surface_state", "destroyed")
        NativeApp.logCrashBreadcrumb("surfaceDestroyed")
        runCatching { NativeApp.pause() }
        Log.i(TAG, "onSurfaceDestroyed: pause done, calling native destroy")
        // Android invalidates the BufferQueue as soon as this callback returns.
        // Detach GS synchronously so it cannot keep presenting to an abandoned Surface.
        try {
            NativeApp.onNativeSurfaceDestroyed()
            Log.i(TAG, "onSurfaceDestroyed: native destroy done")
        } catch (e: Exception) {
            Log.e(TAG, "onSurfaceDestroyed: native destroy failed", e)
        }
    }

    private fun rebindSurface() {
        val surface = lastSurface ?: return
        val width = lastSurfaceWidth
        val height = lastSurfaceHeight
        if (!surface.isValid || width <= 0 || height <= 0) {
            Log.w(TAG, "rebindSurface: skipped (valid=${surface.isValid} w=$width h=$height)")
            return
        }
        // The new renderer session already binds this very surface before
        // loading the game. Rebinding it again on menu resume destroys and
        // recreates Vulkan's swapchain after the first emulated frame.
        if (NativeApp.hasAttachedSurface(surface, width, height)) return
        Log.i(TAG, "rebindSurface: width=$width height=$height")
        NativeApp.logCrashBreadcrumb("rebindSurface width=$width height=$height")
        try {
            NativeApp.onNativeSurfaceChanged(surface, width, height)
        } catch (_: Exception) { }
    }

    suspend fun setSetting(section: String, key: String, type: String, value: String) {
        if (!isNativeLoaded) return
        val cacheKey = "$section:$key"
        if (settingsCache[cacheKey] == value) return
        if (performRuntimeOps(listOf(settingOp(section, key, type, value))))
            settingsCache[cacheKey] = value
    }

    suspend fun reloadPatches() {
        if (!isNativeLoaded) return
        runSerial { NativeApp.reloadPatches() }
    }

    fun getSetting(section: String, key: String, type: String): String? {
        if (!isNativeLoaded) return null
        return try {
            NativeApp.getSetting(section, key, type)
        } catch (_: Exception) {
            null
        }
    }

    private fun targetFpsOps(targetFps: Int, ntscFramerate: Float, palFramerate: Float): List<RuntimeOp> {
        // The frame pacer reads this key directly; it must be part of the
        // session-start batch or a manual rate is only honoured after the user
        // touches the in-game control.
        val targetOp = settingOp("EmuCoreH/GS", "TargetFps", "int", targetFps.coerceIn(0, 120).toString())
        if (targetFps <= 0) {
            return listOf(targetOp) + regionFramerateOps(ntscFramerate, palFramerate)
        }

        val manualFps = targetFps.coerceIn(20, 120).toFloat()
        return listOf(
            targetOp,
            settingOp("EmuCoreH/GS", "FramerateNTSC", "float", manualFps.toString()),
            settingOp("EmuCoreH/GS", "FrameratePAL", "float", manualFps.toString())
        )
    }

    private fun sanitizeFramerate(value: Float, fallback: Float): Float {
        return if (value.isFinite()) value.coerceIn(20f, 120f) else fallback
    }

    private fun sanitizeFastForwardSpeed(value: Float): Float {
        return if (value.isFinite()) {
            value.coerceIn(AppPreferences.MIN_FAST_FORWARD_SPEED, AppPreferences.MAX_FAST_FORWARD_SPEED)
        } else {
            AppPreferences.DEFAULT_FAST_FORWARD_SPEED
        }
    }
}
