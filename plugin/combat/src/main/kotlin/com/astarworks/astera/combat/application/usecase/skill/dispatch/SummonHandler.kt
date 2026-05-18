package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * SUMMON archetype handler — **deferred to Wave 6 mob-spawn track**.
 *
 * Spawning a vendor-neutral entity requires an `IMobSpawnService` port that
 * does not yet exist (Wave 6 mob track owns it). For Wave 5a the handler
 * emits a single WARN per cast so live behaviour is observable but harmless
 * — the dispatcher still returns control to whoever fired the SkillCast.
 *
 * Once Wave 6 lands an `IMobSpawnService`, swap the body for:
 * ```
 * mobSpawnService.spawn(
 *     typeKey = params.entityTypeKey,
 *     count = params.count,
 *     at = event.at,
 *     lifetimeTicks = params.lifetimeTicks,
 * )
 * ```
 */
public class SummonHandler(
    private val log: Logger = LoggerFactory.getLogger(SummonHandler::class.java),
) {
    public fun handle(event: SkillCast, params: SkillParams.SummonParams) {
        log.warn(
            "SUMMON dispatch deferred to Wave 6 mob spawn for skill {} (entityTypeKey={}, count={})",
            event.skillId.value,
            params.entityTypeKey,
            params.count,
        )
    }
}
