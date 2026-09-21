package com.sbro.emucoreh.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.sbro.emucoreh.MainActivity
import com.sbro.emucoreh.R
import com.sbro.emucoreh.data.GameItem

object GameLaunchShortcut {
    const val ACTION_LAUNCH_GAME = "com.sbro.emucoreh.action.LAUNCH_GAME"
    const val EXTRA_GAME_PATH = "com.sbro.emucoreh.extra.GAME_PATH"
    const val EXTRA_SAVE_SLOT = "com.sbro.emucoreh.extra.SAVE_SLOT"
    const val EXTRA_BOOT_BIOS = "com.sbro.emucoreh.extra.BOOT_BIOS"
    const val EXTRA_BOOT_SMOKE_PROBE = "com.sbro.emucoreh.extra.BOOT_SMOKE_PROBE"
    const val EXTRA_AUTOTEST_MODE = "com.sbro.emucoreh.extra.AUTOTEST_MODE"
    const val EXTRA_RENDERER = "com.sbro.emucoreh.extra.RENDERER"
    const val EXTRA_GS_DUMP_FRAMES = "com.sbro.emucoreh.extra.GS_DUMP_FRAMES"
    const val EXTRA_GS_DUMP_DELAY_MS = "com.sbro.emucoreh.extra.GS_DUMP_DELAY_MS"

    // Must match the data scheme declared for MainActivity in AndroidManifest.
    private const val SCHEME = "emucoreh"
    private const val HOST = "launch"

    // Launcher frontends commonly pass the game as a "ROM"/"gamePath" extra.
    private const val EXTRA_GAME_PATH_LEGACY = "ROM"
    private const val EXTRA_GAME_PATH_ALT = "gamePath"

    data class LaunchRequest(
        val gamePath: String? = null,
        val saveSlot: Int? = null,
        val bootBios: Boolean = false,
        val bootSmokeProbe: Boolean = false,
        val autotestMode: Boolean = false,
        val renderer: Int? = null,
        val gsDumpFrames: Int? = null,
        val gsDumpDelayMs: Int? = null
    )

    fun requestPinnedShortcut(
        context: Context,
        game: GameItem,
        saveSlot: Int? = null
    ): Boolean {
        val normalizedSaveSlot = normalizeSaveSlot(saveSlot)
        val shortcutId = buildString {
            append("game:")
            append(game.path.hashCode())
            normalizedSaveSlot?.let {
                append(":")
                append(it)
            }
        }
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_LAUNCH_GAME
            putExtra(EXTRA_GAME_PATH, game.path)
            normalizedSaveSlot?.let { putExtra(EXTRA_SAVE_SLOT, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(game.title.take(40))
            .setLongLabel(game.title)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(launchIntent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    fun parseLaunchRequest(intent: Intent?): LaunchRequest? {
        intent ?: return null
        val data = intent.data
        val gamePathFromData = when {
            data == null -> null
            data.scheme == SCHEME && data.host == HOST -> data.getQueryParameter("gamePath")
            data.scheme == "content" || data.scheme == "file" -> data.toString()
            // Some launchers send a bare absolute path via -d without a scheme.
            data.scheme.isNullOrBlank() && data.toString().startsWith("/") -> data.toString()
            else -> null
        }
        val gamePath = gamePathExtra(intent) ?: gamePathFromData
        val saveSlot = when {
            intent.hasExtra(EXTRA_SAVE_SLOT) -> normalizeSaveSlot(intent.getIntExtra(EXTRA_SAVE_SLOT, -1))
            data?.scheme == SCHEME && data.host == HOST -> normalizeSaveSlot(data.getQueryParameter("saveSlot")?.toIntOrNull())
            else -> null
        }
        val bootBios = intent.getBooleanExtra(EXTRA_BOOT_BIOS, false) ||
            (data?.scheme == SCHEME && data.host == HOST && data.getQueryParameter("bootBios") == "true")
        val bootSmokeProbe = intent.getBooleanExtra(EXTRA_BOOT_SMOKE_PROBE, false) ||
            (data?.scheme == SCHEME && data.host == HOST && data.getQueryParameter("bootSmoke") == "true")
        val autotestMode = intent.getBooleanExtra(EXTRA_AUTOTEST_MODE, false) ||
            (data?.scheme == SCHEME && data.host == HOST && data.getQueryParameter("autotest") == "true")
        if (gamePath.isNullOrBlank() && !bootBios) return null
        return LaunchRequest(
            gamePath = gamePath,
            saveSlot = saveSlot,
            bootBios = bootBios,
            bootSmokeProbe = bootSmokeProbe,
            autotestMode = autotestMode,
            renderer = optionalIntExtra(intent, EXTRA_RENDERER)
                ?: optionalIntQuery(data, "renderer"),
            gsDumpFrames = optionalIntExtra(intent, EXTRA_GS_DUMP_FRAMES)
                ?: optionalIntQuery(data, "gsDumpFrames"),
            gsDumpDelayMs = optionalIntExtra(intent, EXTRA_GS_DUMP_DELAY_MS)
                ?: optionalIntQuery(data, "gsDumpDelayMs")
        )
    }

    fun clearLaunchRequest(intent: Intent?) {
        intent ?: return
        intent.removeExtra(EXTRA_GAME_PATH)
        intent.removeExtra(EXTRA_GAME_PATH_ALT)
        intent.removeExtra(EXTRA_GAME_PATH_LEGACY)
        intent.removeExtra(EXTRA_SAVE_SLOT)
        intent.removeExtra(EXTRA_BOOT_BIOS)
        intent.removeExtra(EXTRA_BOOT_SMOKE_PROBE)
        intent.removeExtra(EXTRA_AUTOTEST_MODE)
        intent.removeExtra(EXTRA_RENDERER)
        intent.removeExtra(EXTRA_GS_DUMP_FRAMES)
        intent.removeExtra(EXTRA_GS_DUMP_DELAY_MS)
        if (intent.data?.scheme == SCHEME && intent.data?.host == HOST) {
            intent.data = null
        }
    }

    private fun gamePathExtra(intent: Intent): String? {
        intent.getStringExtra(EXTRA_GAME_PATH)?.takeIf { it.isNotBlank() }?.let { return it }
        intent.getStringExtra(EXTRA_GAME_PATH_ALT)?.takeIf { it.isNotBlank() }?.let { return it }
        intent.getStringExtra(EXTRA_GAME_PATH_LEGACY)?.takeIf { it.isNotBlank() }?.let { return it }
        @Suppress("DEPRECATION")
        val legacyUri = intent.getParcelableExtra(EXTRA_GAME_PATH_LEGACY) as? Uri
        return legacyUri?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun optionalIntExtra(intent: Intent, key: String): Int? {
        return if (intent.hasExtra(key)) intent.getIntExtra(key, Int.MIN_VALUE) else null
    }

    private fun optionalIntQuery(data: Uri?, key: String): Int? {
        if (data?.scheme != SCHEME || data.host != HOST) return null
        return data.getQueryParameter(key)?.toIntOrNull()
    }

    private fun optionalBooleanExtra(intent: Intent, key: String): Boolean? {
        return if (intent.hasExtra(key)) intent.getBooleanExtra(key, false) else null
    }

    private fun optionalBooleanQuery(data: Uri?, key: String): Boolean? {
        if (data?.scheme != SCHEME || data.host != HOST) return null
        return when (data.getQueryParameter(key)?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }

    private fun normalizeSaveSlot(slot: Int?): Int? {
        return when (slot) {
            null -> null
            in 1..10 -> slot
            in 0..9 -> slot + 1
            else -> null
        }
    }
}
