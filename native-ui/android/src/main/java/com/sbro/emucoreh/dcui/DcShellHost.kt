package com.sbro.emucoreh.dcui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Host callbacks and hardware gamepad/keyboard router for the Dreamcast BIOS native shell.
 * Native callbacks invoked on the GL thread are marshaled safely to the Android main Looper.
 */
object DcShellHost {
    const val KEY_UP = 0
    const val KEY_DOWN = 1
    const val KEY_LEFT = 2
    const val KEY_RIGHT = 3
    const val KEY_CONFIRM = 4
    const val KEY_BACK = 5
    const val KEY_OPTIONS = 6
    const val KEY_SECONDARY = 7
    const val KEY_PREV_TAB = 8
    const val KEY_NEXT_TAB = 9
    const val KEY_START = 10

    const val VMU_ACTION_SELECT_CARD = 0
    const val VMU_ACTION_CREATE_DEFAULTS = 1
    const val VMU_ACTION_DELETE_FILE = 2
    const val VMU_ACTION_FORMAT_CARD = 3

    private const val KEY_REPEAT_INTERVAL_MS = 120L
    private const val AXIS_REPEAT_INTERVAL_MS = 160L
    private const val AXIS_DEADZONE = 0.55f

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var gameLaunchListener: ((String) -> Unit)? = null

    @Volatile
    private var saveLaunchListener: ((String, Int) -> Unit)? = null

    @Volatile
    private var deleteSaveListener: ((Int) -> Unit)? = null

    @Volatile
    private var vmuActionListener: ((action: Int, cardIndex: Int, fileIndex: Int) -> Unit)? = null

    @Volatile
    private var openGameSettingsListener: ((Int) -> Unit)? = null

    @Volatile
    private var settingsListener: ((IntArray) -> Unit)? = null

    @Volatile
    private var exitListener: (() -> Unit)? = null

    @Volatile
    private var keyListener: ((Int) -> Unit)? = null

    @Volatile
    private var requestRenderListener: (() -> Unit)? = null

    @Volatile
    var swapConfirmButton: Boolean = false

    private var lastKeyRepeatTimeMs = 0L
    private var lastRepeatedKeyCode = -1
    private var activeAxisDirection = -1
    private var lastAxisRepeatTimeMs = 0L

    fun setGameLaunchListener(listener: ((String) -> Unit)?) {
        gameLaunchListener = listener
    }

    fun setSaveLaunchListener(listener: ((String, Int) -> Unit)?) {
        saveLaunchListener = listener
    }

    fun setDeleteSaveListener(listener: ((Int) -> Unit)?) {
        deleteSaveListener = listener
    }

    fun setVmuActionListener(listener: ((action: Int, cardIndex: Int, fileIndex: Int) -> Unit)?) {
        vmuActionListener = listener
    }

    fun setOpenGameSettingsListener(listener: ((Int) -> Unit)?) {
        openGameSettingsListener = listener
    }

    fun setSettingsListener(listener: ((IntArray) -> Unit)?) {
        settingsListener = listener
    }

    fun setExitListener(listener: (() -> Unit)?) {
        exitListener = listener
    }

    fun setKeyListener(listener: ((Int) -> Unit)?) {
        keyListener = listener
        if (listener == null) {
            activeAxisDirection = -1
            lastRepeatedKeyCode = -1
        }
    }

    fun setRequestRenderListener(listener: (() -> Unit)?) {
        requestRenderListener = listener
    }

    @JvmStatic
    fun onGameLaunch(gamePath: String) {
        mainHandler.post { gameLaunchListener?.invoke(gamePath) }
    }

    @JvmStatic
    fun onSaveLaunch(gamePath: String, slot: Int) {
        mainHandler.post { saveLaunchListener?.invoke(gamePath, slot) }
    }

    @JvmStatic
    fun onDeleteSave(index: Int) {
        mainHandler.post { deleteSaveListener?.invoke(index) }
    }

    @JvmStatic
    fun onVmuAction(action: Int, cardIndex: Int, fileIndex: Int) {
        mainHandler.post { vmuActionListener?.invoke(action, cardIndex, fileIndex) }
    }

    @JvmStatic
    fun onOpenGameSettings(index: Int) {
        mainHandler.post { openGameSettingsListener?.invoke(index) }
    }

    @JvmStatic
    fun onSettingsChanged(settings: IntArray) {
        val copy = settings.copyOf()
        mainHandler.post { settingsListener?.invoke(copy) }
    }

    @JvmStatic
    fun onExitRequested() {
        mainHandler.post { exitListener?.invoke() }
    }

    @JvmStatic
    fun onRequestRender() {
        requestRenderListener?.invoke()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val listener = keyListener ?: return false
        val mappedCode = mapKeyCode(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.keyCode == lastRepeatedKeyCode) {
                lastRepeatedKeyCode = -1
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) {
            return true
        }
        val now = SystemClock.uptimeMillis()
        if (event.repeatCount > 0) {
            if (!isRepeatableNavigation(mappedCode)) return true
            if (event.keyCode == lastRepeatedKeyCode && (now - lastKeyRepeatTimeMs) < KEY_REPEAT_INTERVAL_MS) {
                return true
            }
        }
        lastRepeatedKeyCode = event.keyCode
        lastKeyRepeatTimeMs = now
        listener(mappedCode)
        return true
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val listener = keyListener ?: return false
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
            (event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
        ) {
            return false
        }
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)

        val ax = if (abs(hatX) > abs(stickX)) hatX else stickX
        val ay = if (abs(hatY) > abs(stickY)) hatY else stickY

        val dir = when {
            ay < -AXIS_DEADZONE && abs(ay) >= abs(ax) -> KEY_UP
            ay > AXIS_DEADZONE && abs(ay) >= abs(ax) -> KEY_DOWN
            ax < -AXIS_DEADZONE && abs(ax) > abs(ay) -> KEY_LEFT
            ax > AXIS_DEADZONE && abs(ax) > abs(ay) -> KEY_RIGHT
            else -> -1
        }
        if (dir < 0) {
            activeAxisDirection = -1
            return false
        }
        val now = SystemClock.uptimeMillis()
        if (dir != activeAxisDirection || (now - lastAxisRepeatTimeMs) >= AXIS_REPEAT_INTERVAL_MS) {
            activeAxisDirection = dir
            lastAxisRepeatTimeMs = now
            listener(dir)
        }
        return true
    }

    private fun isRepeatableNavigation(code: Int): Boolean =
        code == KEY_UP || code == KEY_DOWN || code == KEY_LEFT || code == KEY_RIGHT ||
            code == KEY_PREV_TAB || code == KEY_NEXT_TAB

    private fun mapKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> KEY_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> KEY_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> KEY_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> KEY_RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER -> KEY_CONFIRM
        KeyEvent.KEYCODE_BUTTON_A -> if (swapConfirmButton) KEY_BACK else KEY_CONFIRM
        KeyEvent.KEYCODE_BUTTON_B -> if (swapConfirmButton) KEY_CONFIRM else KEY_BACK
        // KEYCODE_BACK is handled via Compose BackHandler (including Android 13-16 predictive back)
        // so mapping it here would fire KEY_BACK twice per system Back press.
        KeyEvent.KEYCODE_ESCAPE -> KEY_BACK
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_BUTTON_SELECT -> KEY_OPTIONS
        KeyEvent.KEYCODE_BUTTON_X -> KEY_SECONDARY
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_PAGE_UP -> KEY_PREV_TAB
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_PAGE_DOWN -> KEY_NEXT_TAB
        KeyEvent.KEYCODE_BUTTON_START -> KEY_START
        else -> null
    }
}
