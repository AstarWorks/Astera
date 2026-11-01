package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IOreDigSight
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Holding the left button on an ore node. A node never starts vanilla cracking, so the server sees ONE dig-start per click: without this,
 * holding lands a single hit and an 8-hit node never breaks. [begin] takes the click's hit and keeps a per-player repeating task (on the
 * player's own scheduler) that re-hits the same node while the player keeps swinging and keeps aiming at it. The task only calls
 * [OreNodeService.mine], whose per-player hit cooldown is the one rate limit, so holding, clicking and mixing all hit at the same cadence.
 * A refusal (locked, too weak, wrong tool, regrowing), the break, release, looking away or leaving ends the hold.
 */
public class OreDigHold(
    private val nodes: () -> OreNodeService?,
    private val sight: IOreDigSight,
    private val scheduler: IScheduler,
    /** Called once per break (the star grant path). */
    private val onMined: (PlayerId) -> Unit,
) {
    private class Hold(val world: WorldId, val pos: Vec3, @Volatile var lastSwing: Long) {
        lateinit var handle: IScheduler.TaskHandle
    }

    private val holds = ConcurrentHashMap<PlayerId, Hold>()

    /** The player started digging [pos]: hits once now, and keeps hitting while they hold. */
    public fun begin(player: PlayerId, world: WorldId, pos: Vec3): OreNodeService.Outcome {
        val outcome = attempt(player, world, pos)
        if (outcome is OreNodeService.Outcome.Hit || outcome is OreNodeService.Outcome.Cooldown) {
            val current = holds[player]
            if (current != null && current.world == world && sameCell(current.pos, pos)) current.lastSwing = scheduler.currentTick()
            else startHold(player, world, pos)
        } else {
            release(player)
        }
        return outcome
    }

    /** An arm swing of [player] (a held dig swings every tick); keeps their hold alive. */
    public fun swung(player: PlayerId) {
        holds[player]?.lastSwing = scheduler.currentTick()
    }

    /** The player released the button or looked away. */
    public fun release(player: PlayerId) {
        holds.remove(player)?.handle?.cancel()
    }

    private fun startHold(player: PlayerId, world: WorldId, pos: Vec3) {
        release(player)
        val hold = Hold(world, pos, scheduler.currentTick())
        holds[player] = hold
        hold.handle = scheduler.runRepeatingTicks(ExecutionScope.Entity(player.uuid), TICK_PERIOD, TICK_PERIOD) { tick(player, hold) }
        hold.handle.onRetired { holds.remove(player, hold) }
    }

    private fun tick(player: PlayerId, hold: Hold) {
        if (holds[player] !== hold) { hold.handle.cancel(); return }
        val stale = scheduler.currentTick() - hold.lastSwing > SWING_GRACE_TICKS
        if (stale || !sight.aimsAt(player, hold.world, hold.pos)) { stop(player, hold); return }
        when (attempt(player, hold.world, hold.pos)) {
            is OreNodeService.Outcome.Hit, OreNodeService.Outcome.Cooldown -> Unit
            else -> stop(player, hold)
        }
    }

    private fun stop(player: PlayerId, hold: Hold) {
        holds.remove(player, hold)
        hold.handle.cancel()
    }

    private fun attempt(player: PlayerId, world: WorldId, pos: Vec3): OreNodeService.Outcome {
        val outcome = nodes()?.mine(player, world, pos, sight.heldItem(player)) ?: return OreNodeService.Outcome.NotANode
        if (outcome is OreNodeService.Outcome.Mined) onMined(player)
        return outcome
    }

    private fun sameCell(a: Vec3, b: Vec3) = floor(a.x) == floor(b.x) && floor(a.y) == floor(b.y) && floor(a.z) == floor(b.z)

    public companion object {
        /** The task runs every 2 ticks and lets the node's hit cooldown decide, so the cadence is exactly the cooldown, not a multiple of it. */
        public const val TICK_PERIOD: Long = 2L

        /** A held dig swings every tick; this many silent ticks (latency jitter included) mean the button was released. */
        public const val SWING_GRACE_TICKS: Long = 8L
    }
}
