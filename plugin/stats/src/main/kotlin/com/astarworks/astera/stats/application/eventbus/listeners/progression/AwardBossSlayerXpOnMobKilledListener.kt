package com.astarworks.astera.stats.application.eventbus.listeners.progression

import com.astarworks.astera.core.domain.event.MobKilled
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.stats.application.usecase.progression.AwardSkillXpUseCase

/**
 * Awards [SkillKind.BossSlayer] XP to a player on every credited mob kill
 * (Wave 5multi-skill, ADR-0101).
 *
 * Phase 1 caveat: until Wave 12a ships a boss flag on `MobSpec`, **every**
 * mob kill feeds BossSlayer at the same flat rate. Once boss flagging
 * lands, this listener should gate on the flag and route normal mobs to
 * a separate axis (or drop them entirely if PvE adds its own Hunter axis).
 *
 * Skipped:
 *  - Unattributed kills (`killerId == null`): environment / suicide
 */
public class AwardBossSlayerXpOnMobKilledListener(
    private val awardXp: AwardSkillXpUseCase,
    private val xpPerKill: Long = BOSS_SLAYER_XP_PER_KILL,
) {
    public fun handle(event: MobKilled) {
        if (!event.context.countsForProgression) return // ADR-0235: lobby / wild mob kills give nothing yet
        val killer = event.killerId ?: return
        awardXp.execute(killer, SkillKind.BossSlayer, xpPerKill)
    }

    public companion object {
        public const val BOSS_SLAYER_XP_PER_KILL: Long = 50L
    }
}
