package com.astarworks.astera.core.domain.mob.encounter

import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.mob.MobRegionId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.mob.boss.BossId

/**
 * Named PvE area with its own spawn pool, difficulty scaling, and optional
 * boss anchor (Wave 5pve-boss, ADR-0098).
 *
 * Phase 1 PoC scope: the zone is **declarative** — it lists which mobs may
 * spawn in it, the maximum concurrent count, the difficulty curve, and the
 * optional boss tied to the zone. The spawn ladder + boss lifecycle
 * service consume the zone description; this type holds no runtime state.
 *
 * # Why MobRegionId, not a fully fleshed-out region domain
 *
 * The same forward-coupling note as [com.astarworks.astera.core.domain.mob.MobSpec.spawnConditions]
 * applies: Wave 5n introduces a server-wide `RegionId` for world env / region
 * tagging, but that wave is in-flight on parallel branches. We point at
 * [MobRegionId] now and migrate via typealias / supersede ADR when Wave 5n
 * lands.
 *
 * # Why boss is nullable + zone-level (not per-mob)
 *
 * One zone may have a single dedicated boss (Annihilation `blazing-pit` →
 * `scorched-titan`) or none (HSB Slayer tier-1 zones are pure mob grinders).
 * Modelling boss as a zone field instead of decorating one of the mob entries
 * keeps the boss-spawn trigger (zone-entered / quest-step / weekly reset) in
 * one place. Multi-boss zones land via a supersede ADR.
 *
 * @property id Stable zone identifier.
 * @property displayName Inline localized zone name (HUD nameplate).
 * @property region Underlying region tag (see [MobRegionId]).
 * @property mobPool Mob templates that may spawn in this zone. Each entry
 *   carries a per-mob weight so the spawn ladder can do weighted selection.
 * @property maxConcurrent Hard cap on concurrent mobs in the zone. The
 *   spawn ladder skips spawn attempts beyond this number.
 * @property difficulty Scaling curve applied to mob HP / damage at spawn
 *   time. Defaults to [DifficultyCurve.Flat] (no scaling).
 * @property boss Optional boss anchored to this zone. Spawn trigger is
 *   decided by the lifecycle service (Phase 1: on-demand admin spawn;
 *   Phase 4: weekly reset / quest step / first-clear gate).
 * @property deathPenalty Rule applied when a player dies inside the zone
 *   (Wave 5pve-content, ADR-0099). Defaults to [DeathPenaltyRule.SafeDeath]
 *   so existing pre-ADR-0099 zones keep their previous behaviour.
 */
public data class EncounterZone(
    public val id: EncounterZoneId,
    public val displayName: LocalizedText,
    public val region: MobRegionId,
    public val mobPool: List<EncounterMobEntry>,
    public val maxConcurrent: Int,
    public val difficulty: DifficultyCurve = DifficultyCurve.Flat,
    public val boss: BossId? = null,
    public val deathPenalty: DeathPenaltyRule = DeathPenaltyRule.SafeDeath,
) {
    init {
        require(maxConcurrent >= 0) {
            "EncounterZone($id).maxConcurrent must be >= 0: $maxConcurrent"
        }
        if (mobPool.isNotEmpty()) {
            val typeIds = mobPool.map { it.mobTypeId }
            require(typeIds.size == typeIds.toSet().size) {
                "EncounterZone($id): duplicate MobTypeId entries in mobPool: $typeIds"
            }
        }
    }

    /** Sum of weights across the [mobPool]. Used by weighted random selection. */
    public val totalWeight: Int get() = mobPool.sumOf { it.weight }
}

/**
 * One row in an [EncounterZone.mobPool]: a mob template id + spawn weight.
 *
 * Weights are integers (not doubles) so deterministic weighted selection is
 * trivial: `roll = rng % totalWeight` then walk the list accumulating
 * weights. Negative / zero weights are rejected — if you want a mob to not
 * spawn, omit it from the pool.
 */
public data class EncounterMobEntry(
    public val mobTypeId: MobTypeId,
    public val weight: Int,
) {
    init {
        require(weight > 0) {
            "EncounterMobEntry($mobTypeId).weight must be > 0: $weight"
        }
    }
}
