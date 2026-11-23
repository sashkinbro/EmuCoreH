package com.sbro.emucoreh.dcui

/**
 * JNI bridge for the native GLES 3.0 Sega Dreamcast BIOS shell renderer.
 * Nothing in this module touches any emulation core.
 */
object DcShellNative {
    init {
        System.loadLibrary("emucoreh_dcui")
    }

    external fun nativeInit(): Boolean

    external fun nativeResize(width: Int, height: Int)

    external fun nativeSetSafeInsets(leftPx: Float, topPx: Float, rightPx: Float, bottomPx: Float)

    /**
     * Renders one frame and returns true if continuous animation should keep rendering
     * on the next vsync, or false when Static mode is idle and can pause on RENDERMODE_WHEN_DIRTY.
     */
    external fun nativeDraw(): Boolean

    external fun nativeShutdown()

    external fun nativeKey(key: Int)

    external fun nativeTouchDown(normX: Float, normY: Float)

    external fun nativeTouchMove(normX: Float, normY: Float)

    external fun nativeTouchUp(normX: Float, normY: Float)

    external fun nativeTouchCancel()

    external fun nativeTouchTwoFingerBack()

    external fun nativeTouchLongPress(normX: Float, normY: Float)

    external fun nativeSetStrings(keys: Array<String>, values: Array<String>)

    external fun nativeSetConsoleTabs(
        tabIds: Array<String>,
        tabLabels: Array<String>,
        accentColors: IntArray,
    )

    external fun nativeSetGames(
        titles: Array<String>,
        paths: Array<String>,
        serials: Array<String>,
        covers: Array<String>,
        sizes: Array<String>,
        dates: Array<String>,
        platformIds: Array<String>,
        platformLabels: Array<String>,
        saveCounts: IntArray,
        latestSaveSlots: IntArray,
        discCounts: IntArray,
        discLabelsPacked: Array<String>,
        discPathsPacked: Array<String>,
        recentRanks: IntArray,
    )

    external fun nativeSetSaves(
        titles: Array<String>,
        slots: Array<String>,
        slotNums: IntArray,
        dates: Array<String>,
        sizes: Array<String>,
        serials: Array<String>,
        platformLabels: Array<String>,
        previews: Array<String>,
        savePaths: Array<String>,
        gamePaths: Array<String>,
    )

    external fun nativeSetVmuCards(
        portNames: Array<String>,
        fileNames: Array<String>,
        sizes: Array<String>,
        dates: Array<String>,
        freeBlocks: IntArray,
        totalBlocks: IntArray,
        formattedFlags: BooleanArray,
        fileCounts: IntArray,
    )

    external fun nativeSetVmuFiles(
        cardIndex: Int,
        fileNames: Array<String>,
        descriptions: Array<String>,
        dates: Array<String>,
        blockCounts: IntArray,
        iconPaths: Array<String>,
    )

    external fun nativeSetSettings(
        theme: Int,
        aspectMode: Int,
        motionMode: Int,
        scanlinesMode: Int,
        bootAnimMode: Int,
        keyTone: Boolean,
        ambientSound: Boolean,
        swapConfirm: Boolean,
        touchControls: Boolean,
        time24h: Boolean,
        dateFormat: Int,
        keepScreenOn: Boolean,
    )

    external fun nativeSetSystemStatus(
        batteryLevel: Int,
        batteryCharging: Boolean,
        wifiConnected: Boolean,
        bluetoothConnected: Boolean,
        audioMuted: Boolean,
        freeStorage: String,
        deviceName: String,
        appVersion: String,
        biosSummary: String,
        biosReadyCount: Int,
        biosTotalCount: Int,
    )

    external fun nativeSetTouchMode(active: Boolean, showTutorial: Boolean)

    external fun nativeSetLoading(gamesLoading: Boolean, savesLoading: Boolean, vmuLoading: Boolean)

    external fun nativeTriggerTransition(kind: Int)

    external fun nativeGetState(): IntArray

    external fun nativeSetState(
        screen: Int,
        mainIndex: Int,
        tabIndex: Int,
        gameIndex: Int,
        fileTab: Int,
        vmuCardIndex: Int,
        saveIndex: Int,
    )

    external fun nativeNotifyImageDecoded(path: String, width: Int, height: Int, rgba: ByteArray?)
}
