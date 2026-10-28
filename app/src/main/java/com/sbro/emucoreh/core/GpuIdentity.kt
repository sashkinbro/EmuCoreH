package com.sbro.emucoreh.core

enum class GpuVendor {
    ADRENO,
    MALI,
    POWERVR,
    XCLIPSE,
    UNKNOWN
}

enum class AdrenoFamily(val catalogToken: String) {
    A6XX("6xx"),
    A7XX("7xx"),
    A8XX("8xx")
}

enum class GpuTier { GREEN, YELLOW, RED, UNKNOWN }

enum class GpuInfoSource { GL_RENDERER, SOC_CATALOG, UNKNOWN }

data class GpuModelInfo(
    val vendor: GpuVendor,
    val displayName: String,
    val adrenoFamily: AdrenoFamily? = null
)

data class DeviceGpuSnapshot(
    val model: GpuModelInfo?,
    val tier: GpuTier,
    val renderer: String?,
    val source: GpuInfoSource
)
