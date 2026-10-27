package com.astarworks.astera.bots

/** Where the TNT is (carry-tnt) and which team's player holds it; [carrierTeam] null = lying on the ground / not known. */
data class TntFact(val x: Double, val z: Double, val carrierTeam: String? = null, val carrierName: String? = null)

/** A clam (mini TNT) lying at a pickup spot; [id] is the stage region id (`clam-<n>`). */
data class ClamFact(val id: String, val x: Double, val z: Double)

/** The team's power TNT (carry-mini-tnt): who holds it and where. */
data class PowerTntFact(val team: String, val holder: String?, val x: Double, val z: Double)

/** An enemy player the bot can see right now ([name] null when the Tab list does not show it). */
data class Sighting(val name: String?, val x: Double, val z: Double)

/** A player of the bot's own team as the bot sees it; [x]/[z] are NaN when it is out of sight. Dead players are `alive = false`. */
data class Mate(val name: String, val x: Double, val z: Double, val alive: Boolean = true) {
    fun known() = !x.isNaN() && !z.isNaN()
}

/**
 * Read-only facts about the running mode that the planner's roles depend on. This is the bots' own small adapter: the mode
 * rules (`*GameMode.kt`) are not read from here. Every query may answer null / empty for "I cannot tell"; the planner then
 * falls back to what it can infer itself. Today [ObservedModeFacts] fills what the client can see; the manager wires a
 * second implementation to the mode-state query of the Splatoon fidelity work.
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

    /** annihilation: whether the pillar of [team] / [tier] (1..3) still stands; null = unknown. */
    fun pillarStanding(team: String, tier: Int): Boolean? = null

    /** Enemy players in sight. */
    fun enemies(): List<Sighting> = emptyList()

    companion object {
        val NONE: ModeFacts = object : ModeFacts {}
    }
}

/**
 * What a protocol bot can observe on its own: loose objective markers (a dropped TNT), enemy sightings and whether a block
 * of the stage still stands (pillars). Everything else stays unknown until the server-side query is wired in.
 */
class ObservedModeFacts(
    private val modeId: String,
    /** True/false when the world cell is known solid / air, null when its chunk is not loaded. */
    private val solidAt: (Int, Int, Int) -> Boolean?,
    private val pillarBlock: (String, Int) -> Triple<Int, Int, Int>?,
) : ModeFacts {
    @Volatile private var markers: List<Pair<Double, Double>> = emptyList()
    @Volatile private var seen: List<Sighting> = emptyList()

    fun update(looseMarkers: List<Pair<Double, Double>>, sightings: List<Sighting>) {
        markers = looseMarkers
        seen = sightings
    }

    override fun tnt(): TntFact? =
        if (modeId == "carry-tnt") markers.singleOrNull()?.let { TntFact(it.first, it.second) } else null

    override fun enemies(): List<Sighting> = seen

    override fun pillarStanding(team: String, tier: Int): Boolean? =
        pillarBlock(team, tier)?.let { (x, y, z) -> solidAt(x, y, z) }
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
