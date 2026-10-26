package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.boss.BossPhaseTransition
import com.astarworks.astera.core.domain.mob.runtime.MobId
import java.time.Instant

/**
 * Published by `IBossLifecycleService` when a boss instance transitions
 * from one phase to the next (Wave 5pve-boss, ADR-0098).
 *
 * Fires **after** internal phase state is updated. Subscribers can read
 * the boss's current phase by inspecting [toPhase]; the previous phase is
 * [fromPhase].
 *
 * Subscribers:
 *  - HUD raid bar: update the phase label (`Enraged Phase 2/3`).
 *  - Combat (Wave 6b): re-evaluate periodic skill timers for the new phase.
 *  - Announcer: broadcast phase-specific banner ("The titan enrages!").
 *  - Audit: append phase change to replay log.
 *
 * The [trigger] carries the [BossPhaseTransition] subclass that fired —
 * subscribers that care about *why* (HP threshold vs future
 * mechanic-driven trigger) can branch without re-deriving.
 *
 * @property mobId Live boss instance.
 * @property bossId Registry id of the boss.
 * @property fromPhase Phase the boss was in before this transition.
 * @property toPhase Phase the boss is now in.
 * @property trigger The transition that fired.
 * @property atTick Server tick at transition time.
 */
public data class BossPhaseChanged(
    public val mobId: MobId,
    public val bossId: BossId,
    public val fromPhase: Int,
    public val toPhase: Int,
    public val trigger: BossPhaseTransition,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global

    init {
        require(toPhase > fromPhase) {
            "BossPhaseChanged: toPhase ($toPhase) must be > fromPhase ($fromPhase)"
        }
    }
}
