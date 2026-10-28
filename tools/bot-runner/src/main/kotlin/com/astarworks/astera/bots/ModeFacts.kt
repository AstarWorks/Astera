package com.astarworks.astera.bots

/** Where the TNT is (carry-tnt) and which team's player holds it; [carrierTeam] null = lying on the ground / not known. */
data class TntFact(val x: Double, val z: Double, val carrierTeam: String? = null, val carrierName: String? = null)

/** A clam (mini TNT) lying at a pickup spot; [id] is the stage region id (`clam-<n>`). */
data class ClamFact(val id: String, val x: Double, val z: Double)

/** The team's power TNT (carry-mini-tnt): who holds it and where. */
data class PowerTntFact(val team: String, val holder: String?, val x: Double, val z: Double)

/** carry-tnt: the next stop of [team]'s carrier: the checkpoint it still has to hold, or the goal pedestal once none is left. */
data class WaypointFact(val x: Double, val z: Double, val goal: Boolean)

/** A player holding objective items (jewels / clams) and where it stands; [team] null = unknown. */
data class CarrierFact(val name: String, val team: String?, val x: Double, val z: Double, val count: Int)

/** An enemy player the bot can see right now ([name] null when the Tab list does not show it). */
data class Sighting(val name: String?, val x: Double, val z: Double)

/** A player of the bot's own team as the bot sees it; [x]/[z] are NaN when it is out of sight. Dead players are `alive = false`. */
data class Mate(val name: String, val x: Double, val z: Double, val alive: Boolean = true) {
    fun known() = !x.isNaN() && !z.isNaN()
}

/**
 * Read-only facts about the running mode that the planner's roles depend on. This is the bots' own small adapter: the mode
 * rules (`*GameMode.kt`) are not read from here. Every query may answer null / empty for "I cannot tell"; the planner then
 * falls back to what it can infer itself. [ObservedModeFacts] fills what the client can see; [SnapshotModeFacts] reads the
 * mode-state query (`IObjectiveStateQuery`) wherever the bot runner shares a process with the server (game-sim).
 */
interface ModeFacts {
    /** carry-tnt: the TNT. */
    fun tnt(): TntFact? = null

    /** carry-tnt: remaining HP of the shield around the TNT (null = unknown, <= 0 = broken). */
    fun shieldHp(): Double? = null

    /** capture-wool: the team that owns [zone] (`wool-<x>`), null = neutral or unknown. */
    fun zoneOwner(zone: String): String? = null

    /** carry-mini-tnt: the clams that are lying around now (null = unknown, use the stage points). */
    fun clams(): List<ClamFact>? = null

    /** carry-mini-tnt: whether the basket barrier of [team] is open (scoring possible); null = unknown. */
    fun barrierOpen(team: String): Boolean? = null

    /** carry-mini-tnt: the power TNT currently in somebody's hands, if any. */
    fun powerTnt(): PowerTntFact? = null

    /** carry-mini-tnt: every power TNT in somebody's hands (both teams can hold one at once). */
    fun powerTnts(): List<PowerTntFact> = listOfNotNull(powerTnt())

    /** carry-mini-tnt / steal-jewelry: how many objective items [name] holds right now; null = unknown. */
    fun carriedBy(name: String): Int? = null

    /** carry-tnt: where [team]'s carrier has to go next (checkpoint or goal); null = unknown. */
    fun nextWaypoint(team: String): WaypointFact? = null

    /** steal-jewelry: jewels still on the pedestal of [team]; null = unknown. */
    fun jewelsOn(team: String): Int? = null

    /** steal-jewelry: every player that carries a jewel. */
    fun jewelCarriers(): List<CarrierFact> = emptyList()

    /** annihilation: whether the pillar of [team] / [tier] (1..3) still stands; null = unknown. */
    fun pillarStanding(team: String, tier: Int): Boolean? = null

    /** Enemy players in sight. */
    fun enemies(): List<Sighting> = emptyList()

    companion object {
        val NONE: ModeFacts = object : ModeFacts {}
    }
}

/** A player a client sees with the glowing flag set (the carry-tnt carrier glows); [name] null when the Tab list does not show it. */
data class GlowingPlayer(val name: String?, val x: Double, val z: Double)

/**
 * What a protocol bot can observe on its own: loose objective markers (a dropped TNT), enemy sightings, whether a block
 * of the stage still stands (pillars), and the carry-tnt state the mode shows every viewer: the boss bars ("<team> is
 * carrying the TNT", "TNT shield <n> HP") and the glowing carrier. Everything else stays unknown until the server-side query is wired in.
 */
class ObservedModeFacts(
    private val modeId: String,
    /** True/false when the world cell is known solid / air, null when its chunk is not loaded. */
    private val solidAt: (Int, Int, Int) -> Boolean?,
    private val pillarBlock: (String, Int) -> Triple<Int, Int, Int>?,
    /** Where the TNT waits (`tnt-spawn`); the position reported for a carrier that is out of sight. */
    private val tntSpawn: Pair<Double, Double>? = null,
) : ModeFacts {
    @Volatile private var markers: List<Pair<Double, Double>> = emptyList()
    @Volatile private var seen: List<Sighting> = emptyList()
    @Volatile private var bars: List<String> = emptyList()
    @Volatile private var glowing: List<GlowingPlayer> = emptyList()
    @Volatile private var lastCarrier: Pair<Double, Double>? = null

    fun update(looseMarkers: List<Pair<Double, Double>>, sightings: List<Sighting>) {
        markers = looseMarkers
        seen = sightings
    }

    /** [barTitles]: plain titles of the boss bars on screen; [glowingPlayers]: every player with the glowing flag, self included. */
    fun updateRainmaker(barTitles: List<String>, glowingPlayers: List<GlowingPlayer>) {
        bars = barTitles
        glowing = glowingPlayers
    }

    override fun tnt(): TntFact? {
        if (modeId != "carry-tnt") return null
        val team = bars.firstNotNullOfOrNull { CARRIED_BAR.find(it)?.groupValues?.get(1) }
        if (team == null) {
            lastCarrier = null
            return markers.singleOrNull()?.let { TntFact(it.first, it.second) }
        }
        val carrier = glowing.minByOrNull { if (it.name != null) 0 else 1 }
        val at = carrier?.let { it.x to it.z } ?: lastCarrier ?: tntSpawn ?: return null
        lastCarrier = at
        return TntFact(at.first, at.second, team, carrier?.name)
    }

    override fun shieldHp(): Double? =
        if (modeId == "carry-tnt") bars.firstNotNullOfOrNull { SHIELD_BAR.find(it)?.groupValues?.get(1)?.toDoubleOrNull() } else null

    override fun enemies(): List<Sighting> = seen

    override fun pillarStanding(team: String, tier: Int): Boolean? =
        pillarBlock(team, tier)?.let { (x, y, z) -> solidAt(x, y, z) }

    private companion object {
        /** en / ja of `objective.bar.carry-tnt.carried` and `.shield` (content/languages): the team token is the scoreboard team id. */
        val CARRIED_BAR = Regex("(\\w+) (?:is carrying the TNT|がTNTを運搬中)")
        val SHIELD_BAR = Regex("(?:TNT shield|TNT シールド)\\s+(\\d+(?:\\.\\d+)?)\\s*HP")
    }
}

/** A role a bot plays for its team in a mode. One enum so a trace and a scenario can name it. */
enum class Role {
    // carry-tnt
    SHIELD_BREAKER, CARRIER, ESCORT, INTERCEPTOR, GOAL_GUARD,

    // capture-wool
    HOLDER, CONTESTER, RETAKER,

    // carry-mini-tnt
    COLLECTOR, RUSHER, THROWER, BASKET_GUARD,

    // steal-jewelry
    THIEF, PEDESTAL_GUARD,

    // annihilation
    PILLAR_ATTACKER, PILLAR_DEFENDER,
}
