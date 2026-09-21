package com.sbro.emucoreh.core

import android.app.GameManager
import android.app.GameState
import android.content.Context
import android.os.Build
import android.util.Log

internal enum class AndroidGamePhase {
    Idle,
    Loading,
    Gameplay
}

internal fun resolveAndroidGamePhase(
    isStarting: Boolean,
    isRunning: Boolean,
    isPaused: Boolean,
    showMenu: Boolean
): AndroidGamePhase = when {
    isStarting -> AndroidGamePhase.Loading
    isRunning && !isPaused && !showMenu -> AndroidGamePhase.Gameplay
    else -> AndroidGamePhase.Idle
}

/** Reports coarse emulator state to Android so the system can schedule it as an active game. */
internal class AndroidGamePerformance(context: Context) {
    private val appContext = context.applicationContext
    private var lastPhase: AndroidGamePhase? = null

    @Synchronized
    internal fun update(phase: AndroidGamePhase) {
        if (phase == lastPhase) return
        lastPhase = phase
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        runCatching {
            val gameManager = appContext.getSystemService(GameManager::class.java) ?: return
            val state = when (phase) {
                AndroidGamePhase.Idle -> GameState(false, GameState.MODE_NONE)
                AndroidGamePhase.Loading -> GameState(true, GameState.MODE_NONE)
                AndroidGamePhase.Gameplay ->
                    GameState(false, GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE)
            }
            gameManager.setGameState(state)
        }.onFailure { error ->
            Log.w(TAG, "Unable to report Android game state: $phase", error)
        }
    }

    private companion object {
        const val TAG = "AndroidGamePerformance"
    }
}
