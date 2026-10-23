package com.sbro.emucoreh.core

/** Serializes host visibility with starting audio, without taking the core lifecycle lock. */
internal class HostPlaybackGate {
    @Volatile var isForeground: Boolean = true
        private set

    @Synchronized fun setForeground(foreground: Boolean, pauseOutput: () -> Unit) {
        if (isForeground == foreground) return
        isForeground = foreground
        if (!foreground) pauseOutput()
    }

    @Synchronized fun runIfForeground(playOutput: () -> Unit): Boolean {
        if (!isForeground) return false
        playOutput()
        return true
    }
}
