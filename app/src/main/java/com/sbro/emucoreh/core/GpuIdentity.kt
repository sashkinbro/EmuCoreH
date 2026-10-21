package com.sbro.emucoreh.core

enum class GpuVendor {
    ADRENO,
    MALI,
    POWERVR,
    XCLIPSE,
    UNKNOWN
}

enum class AdrenoFamily {
    A6XX,
    A7XX,
    A8XX
}

data class GpuModelInfo(
    val vendor: GpuVendor,
    val displayName: String,
    val adrenoFamily: AdrenoFamily? = null
)
