package com.sbro.emucoreh.core

/** Set definitions from the pinned Supermodel Games.xml, without game payloads. */
internal data class Model3Game(val name: String, val parent: String, val title: String, val version: String, val inputs: Set<String>)

internal object Model3Romsets {
    // Old set names verified by all four program CROM CRCs against the pinned definitions.
    private val legacyNames = mapOf(
        "bass" to "getbassur", "dirtdvlsa" to "dirtdvlsau", "lostwsgo" to "lostwsgp",
        "scuda" to "scud", "scudj" to "scuddx", "srally2x" to "srally2dx", "vs299b" to "vs299j"
    )
    val games: List<Model3Game> = listOf(
        Model3Game("bassdx", "", "Sega Bass Fishing", "USA, Deluxe", setOf("common", "fishing")),
        Model3Game("getbassdx", "bassdx", "Get Bass: Sega Bass Fishing", "Japan, Deluxe", setOf("common", "fishing")),
        Model3Game("getbassur", "bassdx", "Get Bass: Sega Bass Fishing", "Japan, Upright", setOf("common", "fishing")),
        Model3Game("getbass", "bassdx", "Get Bass: Sega Bass Fishing", "Japan, Standard", setOf("common", "fishing")),
        Model3Game("daytona2", "", "Daytona USA 2 - Battle on the Edge", "Japan, Revision A", setOf("common", "vehicle", "shift4", "vr4")),
        Model3Game("dayto2pe", "daytona2", "Daytona USA 2 - Power Edition", "Japan", setOf("common", "vehicle", "shift4", "vr4")),
        Model3Game("dirtdvls", "", "Dirt Devils", "Export, Revision A", setOf("common", "vehicle", "shiftupdown", "viewchange")),
        Model3Game("dirtdvlsu", "dirtdvls", "Dirt Devils", "USA, Revision A", setOf("common", "vehicle", "shiftupdown", "viewchange")),
        Model3Game("dirtdvlsau", "dirtdvls", "Dirt Devils", "Australia, Revision A", setOf("common", "vehicle", "shiftupdown", "viewchange")),
        Model3Game("dirtdvlsj", "dirtdvls", "Dirt Devils", "Japan, Revision A", setOf("common", "vehicle", "shiftupdown", "viewchange")),
        Model3Game("dirtdvlsg", "dirtdvls", "Dirt Devils", "Export, Ver. G?", setOf("common", "vehicle", "shiftupdown", "viewchange")),
        Model3Game("eca", "", "Emergency Call Ambulance", "Export", setOf("common", "vehicle", "shiftupdown", "viewchange")),
        Model3Game("ecap", "eca", "Emergency Call Ambulance", "US location test?", setOf("common", "vehicle", "shiftupdown", "viewchange")),
        Model3Game("ecau", "eca", "Emergency Call Ambulance", "USA", setOf("common", "vehicle", "shiftupdown", "viewchange")),
        Model3Game("ecaj", "eca", "Emergency Call Ambulance", "Japan", setOf("common", "vehicle", "shiftupdown", "viewchange")),
        Model3Game("fvipers2", "", "Fighting Vipers 2", "Japan, Revision A", setOf("common", "joystick1", "joystick2", "fighting")),
        Model3Game("fvipers2o", "fvipers2", "Fighting Vipers 2", "?", setOf("common", "joystick1", "joystick2", "fighting")),
        Model3Game("harley", "", "Harley-Davidson and L.A. Riders", "Export, Revision B", setOf("common", "vehicle", "shiftupdown", "harley", "viewchange")),
        Model3Game("harleya", "harley", "Harley-Davidson and L.A. Riders", "Export, Revision A", setOf("common", "vehicle", "shiftupdown", "harley", "viewchange")),
        Model3Game("lamachin", "", "L.A. Machineguns: Rage of the Machines", "Japan", setOf("common", "analog_gun1", "analog_gun2")),
        Model3Game("lemans24", "", "Le Mans 24", "Japan, Revision B", setOf("common", "vehicle", "vr4", "shiftupdown")),
        Model3Game("lostwsga", "", "The Lost World", "Japan, Revision A", setOf("common", "gun1", "gun2")),
        Model3Game("lostwsgp", "lostwsga", "The Lost World", "Location Test", setOf("common", "gun1", "gun2")),
        Model3Game("magtruck", "", "Magical Truck Adventure", "Japan", setOf("common", "magtruck")),
        Model3Game("mgtrkbad", "magtruck", "Magical Truck Adventure", "Japan", setOf("common", "magtruck")),
        Model3Game("oceanhun", "", "The Ocean Hunter", "Japan, Revision A", setOf("common", "analog_gun1", "analog_gun2")),
        Model3Game("oceanhuna", "oceanhun", "The Ocean Hunter", "Japan", setOf("common", "analog_gun1", "analog_gun2")),
        Model3Game("scud", "", "Scud Race", "Export, Twin/DX", setOf("common", "vehicle", "shift4", "vr4")),
        Model3Game("scudau", "scud", "Scud Race", "Australia, Twin/DX", setOf("common", "vehicle", "shift4", "vr4")),
        Model3Game("scuddx", "scud", "Scud Race", "Export, Deluxe, Revision A", setOf("common", "vehicle", "shift4", "vr4")),
        Model3Game("scuddxo", "scud", "Scud Race", "Export, Deluxe", setOf("common", "vehicle", "shift4", "vr4")),
        Model3Game("scudplus", "scud", "Scud Race Plus", "Export, Twin/DX, Revision A", setOf("common", "vehicle", "shift4", "vr4")),
        Model3Game("scudplusa", "scud", "Scud Race Plus", "Export, Twin/DX", setOf("common", "vehicle", "shift4", "vr4")),
        Model3Game("skichamp", "", "Ski Champ", "Japan", setOf("common", "ski")),
        Model3Game("spikeofe", "", "Spikeout Final Edition", "Export", setOf("common", "joystick1", "spikeout")),
        Model3Game("spikeout", "", "Spikeout", "Export, Revision C", setOf("common", "joystick1", "spikeout")),
        Model3Game("srally2", "", "Sega Rally 2", "Export", setOf("common", "vehicle", "shift4", "handbrake", "viewchange")),
        Model3Game("srally2p", "srally2", "Sega Rally 2", "Prototype", setOf("common", "vehicle", "shift4", "handbrake", "viewchange")),
        Model3Game("srally2pa", "srally2", "Sega Rally 2", "Prototype Version A", setOf("common", "vehicle", "shift4", "handbrake", "viewchange")),
        Model3Game("srally2dx", "srally2", "Sega Rally 2", "Export, Deluxe", setOf("common", "vehicle", "shift4", "handbrake", "viewchange")),
        Model3Game("swtrilgy", "", "Star Wars Trilogy Arcade", "Export, Revision A", setOf("common", "analog_joystick")),
        Model3Game("swtrilgya", "swtrilgy", "Star Wars Trilogy Arcade", "Export", setOf("common", "analog_joystick")),
        Model3Game("swtrilgyp", "swtrilgy", "Star Wars Trilogy Arcade", "Location Test, 16.09.98", setOf("common", "analog_joystick")),
        Model3Game("vf3", "", "Virtua Fighter 3", "Japan, Revision D", setOf("common", "joystick1", "joystick2", "fighting")),
        Model3Game("vf3a", "vf3", "Virtua Fighter 3", "Japan, Revision A", setOf("common", "joystick1", "joystick2", "fighting")),
        Model3Game("vf3c", "vf3", "Virtua Fighter 3", "Japan, Revision C", setOf("common", "joystick1", "joystick2", "fighting")),
        Model3Game("vf3tb", "vf3", "Virtua Fighter 3 Team Battle", "Japan", setOf("common", "joystick1", "joystick2", "fighting")),
        Model3Game("von2", "", "Virtual On 2: Oratorio Tangram", "Japan, Revision B", setOf("common", "twin_joysticks")),
        Model3Game("von254g", "von2", "Virtual On 2: Oratorio Tangram", "Japan, Ver 5.4g", setOf("common", "twin_joysticks")),
        Model3Game("von2a", "von2", "Virtual On 2: Oratorio Tangram", "Japan, Revision A", setOf("common", "twin_joysticks")),
        Model3Game("von2o", "von2", "Virtual On 2: Oratorio Tangram", "Japan", setOf("common", "twin_joysticks")),
        Model3Game("vs2", "", "Virtua Striker 2", "Step 2.0, Export, USA", setOf("common", "joystick1", "joystick2", "soccer")),
        Model3Game("vs215", "vs2", "Virtua Striker 2", "Step 1.5, Export, USA", setOf("common", "joystick1", "joystick2", "soccer")),
        Model3Game("vs215o", "vs2", "Virtua Striker 2", "Step 1.5, Japan (test?)", setOf("common", "joystick1", "joystick2", "soccer")),
        Model3Game("vs298", "", "Virtua Striker 2 '98", "Step 2.0, Japan", setOf("common", "joystick1", "joystick2", "soccer")),
        Model3Game("vs29815", "vs298", "Virtua Striker 2 '98", "Step 1.5, Japan", setOf("common", "joystick1", "joystick2", "soccer")),
        Model3Game("vs2v991", "", "Virtua Striker 2 '99.1", "Export, USA, Revision B", setOf("common", "joystick1", "joystick2", "soccer")),
        Model3Game("vs299a", "vs2v991", "Virtua Striker 2 '99", "Export, USA, Revision A", setOf("common", "joystick1", "joystick2", "soccer")),
        Model3Game("vs299", "vs2v991", "Virtua Striker 2 '99", "Export, USA", setOf("common", "joystick1", "joystick2", "soccer")),
        Model3Game("vs299j", "vs2v991", "Virtua Striker 2 '99.1", "Japan, Revision B", setOf("common", "joystick1", "joystick2", "soccer")),
        Model3Game("vs29915", "vs2v991", "Virtua Striker 2 '99.1", "Step 1.5 Export, USA, Revision B", setOf("common", "joystick1", "joystick2", "soccer")),
        Model3Game("vs29915a", "vs2v991", "Virtua Striker 2 '99", "Step 1.5 Export, USA", setOf("common", "joystick1", "joystick2", "soccer")),
        Model3Game("vs29915j", "vs2v991", "Virtua Striker 2 '99.1", "Step 1.5 Japan, Revision B", setOf("common", "joystick1", "joystick2", "soccer"))
    )
    /** Canonical native filename first, followed by stable, explicitly registered legacy names. */
    fun sourceNames(game: Model3Game): List<String> =
        listOf("${game.name}.zip") + legacyNames.entries.filter { it.value == game.name }.map { "${it.key}.zip" }

    fun find(fileName: String): Model3Game? {
        if (!fileName.endsWith(".zip", true)) return null
        val raw = fileName.substringAfterLast('/').substringBeforeLast('.').lowercase(java.util.Locale.ROOT)
        val name = legacyNames[raw] ?: raw
        return games.firstOrNull { it.name.equals(name, true) }
    }
}
