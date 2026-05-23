package com.astarworks.astera.stats.application.usecase.progression

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.ISkillProgressionRepository
import com.astarworks.astera.core.application.port.outbound.ISkillXpCurveRegistry
import com.astarworks.astera.core.domain.event.SkillLevelUp
import com.astarworks.astera.core.domain.event.SkillXpGained
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillProgression

/**
 * Award XP on a single progression axis (Wave 5multi-skill, ADR-0101).
 *
 * Flow:
 *  1. Resolve current progression (or [SkillProgression.initial] if untouched).
 *  2. Apply [amount] (must be `> 0` per [SkillXpGained] invariant); negative
 *     or zero is silently dropped to keep callers cheap.
 *  3. Re-derive level from the registry's [com.astarworks.astera.core.domain.progression.SkillXpCurve].
 *  4. Persist via [ISkillProgressionRepository.upsert].
 *  5. Publish [SkillXpGained] always; publish [SkillLevelUp] iff level changed.
 *
 * The use case has no external side-effects beyond the repository write +
 * event bus publish. Listeners apply perks / send HUD pings / update
 * advancements off the published events.
 *
 * Thread-safety: the repository's upsert is the single mutator and is
 * expected to be atomic at the `(playerId, kind)` row. Concurrent gains on
 * the same axis may race, but the last writer wins and both [SkillXpGained]
 * events fire (the second carries the later total). Per ADR-0101 the
 * Phase 1 use case accepts this; a per-player serialisation lane can land
 * later if metric noise becomes a problem.
 *
 * Returns the post-gain [SkillProgression] for synchronous callers (admin
 * commands / unit tests). Callers that drive XP from a listener typically
 * ignore the return.
 */
public class AwardSkillXpUseCase(
    private val repository: ISkillProgressionRepository,
    private val curves: ISkillXpCurveRegistry,
    private val eventBus: IDomainEventBus,
) {

    public fun execute(playerId: PlayerId, kind: SkillKind, amount: Long): SkillProgression? {
        if (amount <= 0L) return repository.find(playerId, kind)

        val current = repository.find(playerId, kind) ?: SkillProgression.initial(playerId, kind)
        val curve = curves.curveOf(kind)
        val newTotal = current.xp + amount
        val newLevel = curve.levelFor(newTotal).coerceAtLeast(current.level)
        val updated = SkillProgression(playerId, kind, xp = newTotal, level = newLevel)
        repository.upsert(updated)

        eventBus.publish(
            SkillXpGained(
                playerId = playerId,
                kind = kind,
                amount = amount,
                newTotal = newTotal,
                newLevel = newLevel,
            ),
        )
        if (newLevel > current.level) {
            eventBus.publish(
                SkillLevelUp(
                    playerId = playerId,
                    kind = kind,
                    oldLevel = current.level,
                    newLevel = newLevel,
                    newTotal = newTotal,
                ),
            )
        }
        return updated
    }
}
