package com.sbro.emucoreh.data

enum class AppFontChoice(val preferenceValue: Int) {
    SYSTEM(0),
    RUBIK(1),
    EXO_2(2),
    CUSTOM(3);

    companion object {
        fun fromPreference(value: Int?): AppFontChoice =
            entries.firstOrNull { it.preferenceValue == value } ?: SYSTEM
    }
}

enum class HomeBackgroundType(val preferenceValue: Int) {
    NONE(0),
    IMAGE(1),
    GIF(2),
    VIDEO(3),
    BUILT_IN(4);

    companion object {
        fun fromPreference(value: Int?): HomeBackgroundType =
            entries.firstOrNull { it.preferenceValue == value } ?: NONE
    }
}

/** Bundled artwork choices. Values are persisted, so append new entries instead of reordering. */
enum class HomeBackgroundPreset(val preferenceValue: Int) {
    OLYMPUS(0),
    NEON_RACING(1),
    TROPICAL_RUINS(2),
    COLOSSUS_VALLEY(3),
    STEALTH_JUNGLE(4),
    GOTHIC_CITY(5),
    WEST_COAST(6),
    SAMURAI_NIGHT(7),
    CRYSTAL_PILGRIMAGE(8);

    companion object {
        fun fromPreference(value: Int?): HomeBackgroundPreset =
            entries.firstOrNull { it.preferenceValue == value } ?: OLYMPUS
    }
}

/** Artwork rendered only in the unused side gutters around an aspect-correct game frame. */
enum class EmulationSideArtwork(val preferenceValue: Int) {
    NONE(0),
    OLYMPUS(1),
    NIGHT_RACING(2),
    JUNGLE(3),
    COLOSSUS(4),
    CUSTOM(5),
    GOTHIC(6),
    STEALTH(7),
    SAMURAI(8),
    WEST_COAST(9),
    CRYSTAL(10);

    companion object {
        fun fromPreference(value: Int?): EmulationSideArtwork =
            entries.firstOrNull { it.preferenceValue == value } ?: NONE
    }
}

/** Visual treatment only; controller geometry and input hit targets never depend on this value. */
enum class TouchControlVisualStyle(val preferenceValue: Int) {
    CLASSIC(0),
    LEGACY(1),
    MODERN(2),
    ARCADE(3),
    MINIMAL(4);

    companion object {
        fun fromPreference(value: Int?): TouchControlVisualStyle =
            entries.firstOrNull { it.preferenceValue == value } ?: CLASSIC
    }
}

/** Visual feedback only; input hit targets and controller geometry never depend on this value. */
enum class TouchControlPressEffect(val preferenceValue: Int) {
    GROW(0),
    SHRINK(1),
    SPRING(2),
    GLOW(3);

    companion object {
        fun fromPreference(value: Int?): TouchControlPressEffect =
            entries.firstOrNull { it.preferenceValue == value } ?: GROW
    }
}

/** Changes the structure of the in-game menu without changing its actions or saved content order. */
enum class GameMenuLayoutStyle(val preferenceValue: Int) {
    SIDEBAR(0),
    DASHBOARD(1),
    COMMAND_CENTER(2),
    COMPACT(3);

    companion object {
        fun fromPreference(value: Int?): GameMenuLayoutStyle =
            entries.firstOrNull { it.preferenceValue == value } ?: SIDEBAR
    }
}

/** Visual and density treatment for the application navigation drawer. */
enum class DrawerVisualStyle(val preferenceValue: Int) {
    CLASSIC(0),
    COMPACT(1),
    GLASS(2),
    CONSOLE(3);

    companion object {
        fun fromPreference(value: Int?): DrawerVisualStyle =
            entries.firstOrNull { it.preferenceValue == value } ?: CLASSIC
    }
}

enum class GameMenuTabId {
    SESSION,
    CONTROLS,
    EMULATION,
    CHEATS,
    GRAPHICS,
    ACHIEVEMENTS
}

enum class DrawerItemId(val required: Boolean = false) {
    LIBRARY(required = true),
    CATALOG_SEARCH,
    HUB,
    LAUNCH_GAME,
    LAUNCH_BIOS,
    GAME_SETTINGS,
    DATA_TRANSFER,
    RESET_SETTINGS,
    MEMORY_CARDS,
    TEXTURE_MANAGER,
    CHEAT_MANAGER,
    ACHIEVEMENTS,
    SAVE_STATES,
    APP_SETTINGS(required = true),
    SUPPORTED_FORMATS,
    FEEDBACK,
    DISCORD,
    PROFILE
}

enum class GameMenuSectionId(val tab: GameMenuTabId) {
    SAVE_STATES(GameMenuTabId.SESSION),
    AUTO_SAVE(GameMenuTabId.SESSION),
    QUICK_ACTIONS(GameMenuTabId.SESSION),
    SESSION_DEBUG_TOOLS(GameMenuTabId.SESSION),
    AUTOMATION(GameMenuTabId.SESSION),
    GAME_PROFILE(GameMenuTabId.SESSION),
    CONTROLS_GENERAL(GameMenuTabId.CONTROLS),
    CONTROLS_TOUCH(GameMenuTabId.CONTROLS),
    CONTROLS_GAMEPAD(GameMenuTabId.CONTROLS),
    EMULATION_PERFORMANCE(GameMenuTabId.EMULATION),
    EMULATION_SPEED(GameMenuTabId.EMULATION),
    EMULATION_CPU(GameMenuTabId.EMULATION),
    EMULATION_AUDIO(GameMenuTabId.EMULATION),
    EMULATION_CHEATS(GameMenuTabId.CHEATS),
    GRAPHICS_DISPLAY(GameMenuTabId.GRAPHICS),
    GRAPHICS_RENDERING(GameMenuTabId.GRAPHICS),
    GRAPHICS_SCREEN(GameMenuTabId.GRAPHICS)
}

/**
 * Tabs and sections backed by the current PS1 runtime or by frontend-owned state.
 *
 * The remaining enum values are retained solely so preferences imported from the
 * earlier PS2 frontend can be decoded and migrated without crashing. They must
 * not be offered by the EmuCoreH UI until there is a real core implementation.
 */
val SupportedGameMenuTabs: List<GameMenuTabId> = listOf(
    GameMenuTabId.SESSION,
    GameMenuTabId.CONTROLS,
    GameMenuTabId.EMULATION,
    GameMenuTabId.CHEATS,
    GameMenuTabId.GRAPHICS,
    GameMenuTabId.ACHIEVEMENTS
)

val SupportedGameMenuSections: List<GameMenuSectionId> = listOf(
    GameMenuSectionId.SAVE_STATES,
    GameMenuSectionId.AUTO_SAVE,
    GameMenuSectionId.QUICK_ACTIONS,
    GameMenuSectionId.AUTOMATION,
    GameMenuSectionId.CONTROLS_GENERAL,
    GameMenuSectionId.CONTROLS_TOUCH,
    GameMenuSectionId.CONTROLS_GAMEPAD,
    GameMenuSectionId.EMULATION_PERFORMANCE,
    GameMenuSectionId.EMULATION_CPU,
    GameMenuSectionId.EMULATION_AUDIO,
    GameMenuSectionId.EMULATION_CHEATS,
    GameMenuSectionId.GRAPHICS_DISPLAY
)

val DefaultGameMenuTabOrder: List<GameMenuTabId> = SupportedGameMenuTabs
val DefaultGameMenuSectionOrder: List<GameMenuSectionId> = SupportedGameMenuSections

fun gameMenuSectionsForTab(
    tab: GameMenuTabId,
    order: List<GameMenuSectionId> = DefaultGameMenuSectionOrder
): List<GameMenuSectionId> = order.filter { it.tab == tab && it in SupportedGameMenuSections }

fun sanitizeGameMenuTabOrder(raw: String?): List<GameMenuTabId> {
    val stored = raw.orEmpty()
        .split(',')
        .mapNotNull { token ->
            GameMenuTabId.entries.firstOrNull { it.name == token.trim().uppercase() }
        }
        .distinct()
        .filter(SupportedGameMenuTabs::contains)
    return (stored + DefaultGameMenuTabOrder).distinct()
}

fun sanitizeHiddenGameMenuTabs(raw: String?): Set<GameMenuTabId> = raw.orEmpty()
    .split(',')
    .mapNotNull { token ->
        GameMenuTabId.entries.firstOrNull { it.name == token.trim().uppercase() }
    }
    .filter { it in SupportedGameMenuTabs && it != GameMenuTabId.SESSION }
    .toSet()

fun sanitizeHiddenDrawerItems(raw: String?): Set<DrawerItemId> = raw.orEmpty()
    .split(',')
    .mapNotNull { token ->
        DrawerItemId.entries.firstOrNull { it.name == token.trim().uppercase() }
    }
    .filterNot(DrawerItemId::required)
    .toSet()

fun sanitizeGameMenuSectionOrder(raw: String?): List<GameMenuSectionId> {
    val stored = raw.orEmpty()
        .split(',')
        .mapNotNull { token ->
            GameMenuSectionId.entries.firstOrNull { it.name == token.trim().uppercase() }
        }
        .distinct()
        .filter(SupportedGameMenuSections::contains)
    return SupportedGameMenuTabs.flatMap { tab ->
        val storedForTab = stored.filter { it.tab == tab }
        val defaultsForTab = DefaultGameMenuSectionOrder.filter { it.tab == tab }
        (storedForTab + defaultsForTab).distinct()
    }
}

fun sanitizeHiddenGameMenuSections(raw: String?): Set<GameMenuSectionId> = raw.orEmpty()
    .split(',')
    .mapNotNull { token ->
        GameMenuSectionId.entries.firstOrNull { it.name == token.trim().uppercase() }
    }
    .filter(SupportedGameMenuSections::contains)
    .toSet()
