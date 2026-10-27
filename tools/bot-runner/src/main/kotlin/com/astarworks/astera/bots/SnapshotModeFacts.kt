package com.astarworks.astera.bots

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.gamemode.siege.ObjectiveSnapshot

/** A player as the bot's host knows it: [name], [team] (null = none) and the position in the same frame as the stage regions. */
data class PlayerView(val name: String, val team: String?, val x: Double, val z: Double)

/**
 * [ModeFacts] backed by the mode's own state query (`IObjectiveStateQuery.objectiveSnapshot`), for hosts that share a process
 * with the server (game-sim). The snapshot says what the client could never tell: who owns a zone, who carries the TNT, the
 * shield HP, which clams lie free, whether a barrier is open, who holds a power TNT, how many jewels are left and who steals them.
 * [players] resolves a [PlayerId] to name, team and position; [offsetX]/[offsetZ] move stage-local snapshot coordinates into
 * the frame of the planner's regions. What a snapshot does not carry (enemy sightings, pillars) comes from [fallback].
 */
class SnapshotModeFacts(
    private val snapshot: () -> ObjectiveSnapshot?,
    private val players: (PlayerId) -> PlayerView?,
    private val offsetX: Double = 0.0,
    private val offsetZ: Double = 0.0,
    private val fallback: ModeFacts = ModeFacts.NONE,
) : ModeFacts by fallback {

    private fun Vec3.wx() = x + offsetX
    private fun Vec3.wz() = z + offsetZ

    private inline fun <reified T : ObjectiveSnapshot> now(): T? = snapshot() as? T

    override fun tnt(): TntFact? {
        val s = now<ObjectiveSnapshot.Rainmaker>() ?: return null
        val pos = s.position ?: return null
        val carrier = s.carrier?.let { players(it) }
        return TntFact(pos.wx(), pos.wz(), if (s.carrier == null) null else carrier?.team ?: UNKNOWN_TEAM, carrier?.name)
    }

    override fun shieldHp(): Double? = now<ObjectiveSnapshot.Rainmaker>()?.shieldHp

    override fun nextWaypoint(team: String): WaypointFact? {
        val s = now<ObjectiveSnapshot.Rainmaker>() ?: return null
        val next = s.nextWaypoint.entries.firstOrNull { it.key.value == team }?.value ?: return null
        val goal = s.goal.entries.firstOrNull { it.key.value == team }?.value
        return WaypointFact(next.wx(), next.wz(), goal != null && goal.x == next.x && goal.z == next.z)
    }

    override fun zoneOwner(zone: String): String? = now<ObjectiveSnapshot.Zones>()?.zones?.get(zone)?.owner?.value

    override fun clams(): List<ClamFact>? {
        val s = now<ObjectiveSnapshot.Clams>() ?: return null
        val free = s.freeClams.map { (id, at) -> ClamFact(id, at.wx(), at.wz()) }
        // A dropped pile is a pickup too; the planner walks to its position like to any free clam.
        return free + s.piles.mapIndexed { i, (at, _) -> ClamFact("pile-$i", at.wx(), at.wz()) }
    }

    override fun barrierOpen(team: String): Boolean? =
        now<ObjectiveSnapshot.Clams>()?.baskets?.entries?.firstOrNull { it.key.value == team }?.value?.open

    override fun powerTnts(): List<PowerTntFact> {
        val s = now<ObjectiveSnapshot.Clams>() ?: return emptyList()
        return s.powerHolders.mapNotNull { id -> players(id)?.let { PowerTntFact(it.team ?: UNKNOWN_TEAM, it.name, it.x, it.z) } }
    }

    override fun powerTnt(): PowerTntFact? = powerTnts().firstOrNull()

    override fun carriedBy(name: String): Int? {
        val carried = when (val s = snapshot()) {
            is ObjectiveSnapshot.Clams -> s.carried
            is ObjectiveSnapshot.Jewels -> s.carried
            else -> return null
        }
        return carried.entries.filter { players(it.key)?.name == name }.sumOf { it.value }
    }

    override fun jewelsOn(team: String): Int? = now<ObjectiveSnapshot.Jewels>()?.onPedestal?.entries?.firstOrNull { it.key.value == team }?.value

    override fun jewelCarriers(): List<CarrierFact> {
        val s = now<ObjectiveSnapshot.Jewels>() ?: return emptyList()
        return s.carried.mapNotNull { (id, n) -> if (n > 0) players(id)?.let { CarrierFact(it.name, it.team, it.x, it.z, n) } else null }
    }

    private companion object {
        const val UNKNOWN_TEAM = "?"
    }
}
