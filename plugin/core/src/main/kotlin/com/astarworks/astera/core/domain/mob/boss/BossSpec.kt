package com.astarworks.astera.core.domain.mob.boss

import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.mob.raid.RaidLockPolicy

/**
 * Specification for a raid-locked boss encounter (Wave 5pve-boss, ADR-0098).
 *
 * A boss is **not** a regular [com.astarworks.astera.core.domain.mob.MobSpec] —
 * it is a phase-driven encounter with a raid-lock policy, a dedicated loot
 * table, and exactly-once kill semantics (tracked in the V30 `boss_kills`
 * audit table). The boss reuses an existing [MobSpec] for its base body
 * (HP / attributes / appearance / equipment / base skills) via [baseMobType] —
 * boss-specific behaviour is layered through [phases] + [transitions].
 *
 * # Why baseMobType, not duplicating MobSpec fields
 *
 * ADR-0085's MobSpec already carries the full attribute / appearance / loot
 * vocabulary. Duplicating those fields in BossSpec would create two SSoTs
 * for "how does this entity look + behave by default" and cause drift.
 * Instead, the boss spec **composes** an existing mob template and overrides
 * only the encounter-level fields (raid lock, phase transitions, dedicated
 * loot table, display name). The lifecycle service is responsible for
 * resolving [baseMobType] against `IMobRegistry` at spawn time and applying
 * boss-only state on top.
 *
 * # Why a dedicated lootTable separate from MobSpec.lootTable
 *
 * Boss kills produce **encounter-level** loot (raid-shared, deterministic
 * rare drops, weekly first-clear bonus). Regular mob kills produce
 * **per-mob** loot. Reusing `MobSpec.lootTable` would force the boss to
 * either share the wrapped mob's regular drop table (gameplay-wrong: a Boss
 * Skeleton can't drop the same items as a regular Skeleton) or shadow it
 * silently. Modeling [lootTable] explicitly here keeps the two surfaces
 * separate and documents intent.
 *
 * @property id Boss-scoped registry identifier (e.g. `astera:scorched-titan`).
 * @property displayName Inline localized boss nameplate.
 * @property baseMobType The underlying [MobSpec] template this boss is built
 *   on. The lifecycle service resolves it against `IMobRegistry` to obtain
 *   appearance / base attributes / equipment / base skills.
 * @property phases Ordered phase list. Must contain at least one phase
 *   (the initial phase at index 0) and indices must be unique + ascending.
 * @property transitions Phase-transition triggers. Evaluated in declaration
 *   order; each transition's `fromPhase` must reference a phase that exists
 *   in [phases].
 * @property raidLock Raid-lock policy enforced by the lifecycle service
 *   before allowing damage from a player. Defaults to [RaidLockPolicy.OPEN].
 * @property lootTable Encounter-level loot table id. Defaults to
 *   [LootTableId.EMPTY] (Phase 1 ships the type, content/loot/ comes later).
 * @property recordKill If true, the lifecycle service writes a row to the
 *   V30 `boss_kills` audit table on death. Default true — turn off for
 *   training-room / tutorial boss copies where audit rows would skew stats.
 */
public data class BossSpec(
    public val id: BossId,
    public val displayName: LocalizedText,
    public val baseMobType: MobTypeId,
    public val phases: List<BossPhase>,
    public val transitions: List<BossPhaseTransition> = emptyList(),
    public val raidLock: RaidLockPolicy = RaidLockPolicy.OPEN,
    public val lootTable: LootTableId = LootTableId.EMPTY,
    public val recordKill: Boolean = true,
) {
    init {
        require(phases.isNotEmpty()) { "BossSpec($id): phases must not be empty" }
        require(phases.first().index == 0) {
            "BossSpec($id): first phase must have index 0, got ${phases.first().index}"
        }
        val indices = phases.map { it.index }
        require(indices == indices.sorted()) {
            "BossSpec($id): phase indices must be ascending, got $indices"
        }
        require(indices.size == indices.toSet().size) {
            "BossSpec($id): phase indices must be unique, got $indices"
        }
        val phaseSet = indices.toSet()
        transitions.forEach { t ->
            require(t.fromPhase in phaseSet) {
                "BossSpec($id): transition references unknown fromPhase ${t.fromPhase}; known = $phaseSet"
            }
            require(t.toPhase in phaseSet) {
                "BossSpec($id): transition references unknown toPhase ${t.toPhase}; known = $phaseSet"
            }
        }
    }

    /** Total number of phases the boss can occupy. */
    public val phaseCount: Int get() = phases.size

    /** Initial phase (always index 0). */
    public val initialPhase: BossPhase get() = phases.first()

    /** Look up a phase by its [BossPhase.index], or null if not declared. */
    public fun phase(index: Int): BossPhase? = phases.firstOrNull { it.index == index }
}
