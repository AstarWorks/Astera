package com.astarworks.astera.combat.application.service.training

import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.ITrainingDummyShells
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.lobby.TrainingDummyKind
import com.astarworks.astera.core.domain.lobby.TrainingDummySpot
import java.util.Locale

/**
 * Keeps the lobby training dummies alive (F3-11, ADR-0200): (re)spawns missing bodies, moves the moving
 * target along its track, undoes knockback, refills idle dummies, keeps the HP / DPS tag current and shows
 * a short-lived floating number for every hit. The dummy state itself lives in [TrainingDummyRegistry].
 *
 * The lobby world is created after Combat enables, so [start] only schedules the loop; bodies appear as
 * soon as [shells] can spawn them. Main thread only.
 */
public class TrainingRangeService(
    private val range: () -> ILobbyTrainingRange?,
    private val dummies: TrainingDummyRegistry,
    private val shells: ITrainingDummyShells,
    private val displays: IDisplayEntityGateway?,
    private val scheduler: IScheduler,
    private val messages: IMessageRenderer?,
) {
    private var loop: IScheduler.TaskHandle? = null
    private var registered: Boolean = false
    private var numberCounter: Int = 0
    private var spawnBlockedUntil: Long = 0L

    /** Starts the maintenance loop (every [PERIOD_TICKS] ticks). */
    public fun start() {
        loop?.cancel()
        loop = scheduler.runRepeatingTicks(ExecutionScope.Global, initialDelayTicks = PERIOD_TICKS, periodTicks = PERIOD_TICKS) { tick(scheduler.currentTick()) }
    }

    public fun stop() {
        loop?.cancel()
        loop = null
        shells.removeAll()
        dummies.all().forEach(dummies::unbind)
    }

    /** One maintenance pass at [now]; public so tests can drive it without a scheduler. */
    public fun tick(now: Long) {
        val training = range() ?: return
        if (!registered) {
            training.plan.dummies.forEach { dummies.register(TrainingDummy(it)) }
            registered = true
        }
        for (dummy in dummies.all()) {
            scheduler.run(dummy.bodyId?.let { ExecutionScope.Entity(it.uuid) } ?: regionScope(training.world, dummy.spot.position), kotlin.time.Duration.ZERO) {
                val id = dummy.bodyId
                if (id == null || !shells.isAlive(id)) {
                    dummies.unbind(dummy)
                    if (now < spawnBlockedUntil) return@run
                    val spawned = shells.spawn(training.world, dummy.spot, homeOf(dummy.spot, now))
                    if (spawned == null) {
                        // World (or chunk) not ready: retry in a second instead of every pass.
                        spawnBlockedUntil = now + RESPAWN_PERIOD_TICKS
                        return@run
                    }
                    dummies.bind(dummy, spawned)
                } else {
                    keepInPlace(dummy, id, now)
                }
                if (dummy.regenerate(now)) dummy.shownLabel = null
                refreshLabel(dummy, now)

            }
        }
    }

    /** A hit landed on [dummy]: floating number plus an immediate tag refresh. */
    public fun onHit(dummy: TrainingDummy, resolved: ResolvedDamage, @Suppress("UNUSED_PARAMETER") source: DamageSource) {
        val training = range() ?: return
        val body = dummy.bodyId?.let { shells.positionOf(it) } ?: dummy.spot.position
        showNumber(training, body, resolved)
        refreshLabel(dummy, scheduler.currentTick())
    }

    /** Where [spot] belongs at [now]: its post, or its place on the track. */
    public fun homeOf(spot: TrainingDummySpot, now: Long): Vec3 {
        val track = spot.track ?: return spot.position
        val (x, z) = track.positionAt(now)
        return Vec3(x, spot.y, z)
    }

    private fun keepInPlace(dummy: TrainingDummy, id: com.astarworks.astera.core.domain.player.PlayerId, now: Long) {
        val home = homeOf(dummy.spot, now)
        val at = shells.positionOf(id) ?: return
        if (dummy.spot.kind == TrainingDummyKind.MOVING || at.distance(home) > DRIFT_TOLERANCE) shells.moveTo(id, home)
    }

    private fun refreshLabel(dummy: TrainingDummy, now: Long) {
        val id = dummy.bodyId ?: return
        val renderer = messages ?: return
        val lines = listOf(
            renderer.render(null, nameKey(dummy.spot), nameArgs(dummy.spot)),
            renderer.render(
                null,
                K_STATS,
                mapOf(
                    "hp" to dummy.hp.toInt().toString(),
                    "max" to dummy.maxHp.toInt().toString(),
                    "dps" to String.format(Locale.ROOT, "%.1f", dummy.dps(now)),
                ),
            ),
        )
        if (lines == dummy.shownLabel) return
        dummy.shownLabel = lines
        shells.setLabel(id, lines)
    }

    private fun showNumber(training: ILobbyTrainingRange, body: Vec3, resolved: ResolvedDamage) {
        val gateway = displays ?: return
        val amount = String.format(Locale.ROOT, "%.1f", resolved.finalAmount)
        val text = if (resolved.wasCritical) "<gold><bold>-$amount!</bold></gold>" else "<red>-$amount</red>"
        val n = numberCounter++
        // Fan the numbers out a little so a burst of hits stays readable.
        val at = Vec3(body.x + ((n % 3) - 1) * 0.35, body.y + NUMBER_HEIGHT + (n % 2) * 0.25, body.z + (((n / 3) % 3) - 1) * 0.35)
        val spec = DisplaySpec(
            kind = DisplayKind.Text(listOf(LocalizedText(mapOf(LocaleCode.EN to text, LocaleCode.JA to text))), backgroundArgb = 0),
            lifetimeTicks = NUMBER_LIFETIME_TICKS,
            billboard = DisplayBillboard.CENTER,
        )
        val handle = gateway.spawn(training.world, at, spec, listOf(text)) ?: return
        scheduler.runLaterTicks(regionScope(training.world, at), NUMBER_LIFETIME_TICKS) { gateway.remove(handle) }
    }

    private fun nameKey(spot: TrainingDummySpot): MessageKey = when (spot.kind) {
        TrainingDummyKind.RANGE -> K_NAME_RANGE
        TrainingDummyKind.MELEE -> K_NAME_MELEE
        TrainingDummyKind.MOVING -> K_NAME_MOVER
    }

    private fun nameArgs(spot: TrainingDummySpot): Map<String, String> =
        spot.distance?.let { mapOf("distance" to it.toString()) } ?: emptyMap()

    public companion object {
        public const val PERIOD_TICKS: Long = 2L

        /** Missing bodies are retried once per second, not every pass. */
        public const val RESPAWN_PERIOD_TICKS: Long = 20L
        public const val NUMBER_LIFETIME_TICKS: Long = 20L
        public const val NUMBER_HEIGHT: Double = 2.4
        public const val DRIFT_TOLERANCE: Double = 0.25

        public val K_NAME_RANGE: MessageKey = MessageKey("astera.training.dummy.name.range")
        public val K_NAME_MELEE: MessageKey = MessageKey("astera.training.dummy.name.melee")
        public val K_NAME_MOVER: MessageKey = MessageKey("astera.training.dummy.name.mover")
        public val K_STATS: MessageKey = MessageKey("astera.training.dummy.stats")
    }
}
