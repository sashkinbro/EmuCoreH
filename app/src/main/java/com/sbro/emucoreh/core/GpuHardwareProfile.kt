package com.sbro.emucoreh.core

import android.os.Build

object GpuHardwareProfiles {
    const val ADRENO = 0
    const val MALI = 1
    const val POWERVR = 2

    private var cachedDetectedProfile: Int? = null
    private var cachedMediaTekHardware: Boolean? = null

    fun normalize(profile: Int): Int = when (profile) {
        MALI -> MALI
        POWERVR -> POWERVR
        ADRENO -> ADRENO
        else -> MALI
    }

    fun isMediatekProfile(profile: Int): Boolean {
        return normalize(profile) == MALI || normalize(profile) == POWERVR
    }

    // Pass only the SoC-vendor hint. "mediatek" intentionally parses as an automatic GPU override:
    // the native renderer still uses GL_RENDERER/VkPhysicalDeviceProperties for the actual GPU,
    // which matters because older MediaTek generations can use PowerVR instead of Mali.
    fun coreOverrideFor(@Suppress("UNUSED_PARAMETER") profile: Int): String =
        if (isMediaTekHardware()) "mediatek" else "auto"

    fun isMediaTekHardware(): Boolean {
        cachedMediaTekHardware?.let { return it }
        return hasMediaTekSocHints(buildHardwareHints()).also { cachedMediaTekHardware = it }
    }

    fun detectHardwareProfile(): Int {
        cachedDetectedProfile?.let { return it }
        val profile = classifyHardwareProfile(buildHardwareHints())
        cachedDetectedProfile = profile
        return profile
    }

    private fun buildHardwareHints(): String {
        val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else { "" }
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else { "" }
        val hardware = listOf(Build.HARDWARE, Build.BOARD, Build.DEVICE)
            .filterNot { it.isNullOrBlank() }
            .joinToString(" / ")
        val deviceModel = listOf(Build.MANUFACTURER, Build.MODEL)
            .filterNot { it.isNullOrBlank() }
            .joinToString(" ")
        val hints = listOf(socManufacturer, socModel, hardware, deviceModel)
            .joinToString(" ")
            .lowercase()
        return hints
    }

    internal fun hasMediaTekSocHints(rawHints: String): Boolean {
        val hints = rawHints.lowercase()
        return listOf("mediatek", "mtk", "dimensity", "helio").any(hints::contains) ||
            Regex("""\bmt\d{4}[a-z]*\b""").containsMatchIn(hints)
    }

    internal fun classifyHardwareProfile(rawHints: String): Int {
        val hints = rawHints.lowercase()
        return when {
            listOf("powervr", "imgtec", "imagination technologies").any(hints::contains) -> POWERVR
            listOf("qualcomm", "qcom", "snapdragon", "adreno").any(hints::contains) -> ADRENO
            hasMediaTekSocHints(hints) -> MALI
            listOf("samsung", "exynos", "xclipse").any(hints::contains) -> MALI
            listOf("mali", "immortalis", "arm").any(hints::contains) -> MALI
            else -> MALI
        }
    }

    /** Human-readable GPU name for UI/statistics (e.g. "Adreno 750"). */
    fun gpuDisplayName(): String {
        adrenoModelForSoc(MobileSocNameMapper.currentDeviceName())?.let { return "Adreno $it" }
        return when (detectHardwareProfile()) {
            POWERVR -> "PowerVR"
            MALI -> "Mali"
            else -> "Adreno"
        }
    }

    private val SOC_GPU_MODELS = listOf(
        listOf("8 elite gen 5") to "840",
        listOf("8 gen 5") to "829",
        listOf("8 elite") to "830",
        listOf("8s gen 4") to "825",
        listOf("8 gen 3") to "750",
        listOf("8s gen 3") to "735",
        listOf("8 gen 2") to "740",
        listOf("8+ gen 1", "8 gen 1") to "730",
        listOf("7 gen 4") to "722",
        listOf("7+ gen 3") to "732",
        listOf("7s gen 3") to "710",
        listOf("7 gen 3") to "720",
        listOf("7+ gen 2", "7s gen 2") to "710",
        listOf("6 gen 4") to "810",
        listOf("6 gen 3", "6 gen 1") to "710",
        listOf("888") to "660",
        listOf("865") to "650",
        listOf("855") to "640",
        listOf("845") to "630",
        listOf("7 gen 1") to "644",
        listOf("780g", "778g") to "642",
        listOf("765") to "620",
        listOf("750g") to "619",
        listOf("730", "720g") to "618",
        listOf("695", "690", "680", "665", "662") to "6xx"
    )

    private fun adrenoModelForSoc(socName: String): String? {
        val normalized = socName.lowercase()
        if (!normalized.contains("snapdragon")) return null
        return SOC_GPU_MODELS.firstOrNull { (tokens, _) -> tokens.any(normalized::contains) }?.second
    }
}
