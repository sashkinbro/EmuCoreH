package com.sbro.emucoreh.core

data class GameMetadata(
    val title: String,
    val serial: String? = null,
    val serialWithCrc: String? = null,
    /** NTSC/PAL label derived from the disc's IP.BIN area symbols. */
    val region: String? = null
)
