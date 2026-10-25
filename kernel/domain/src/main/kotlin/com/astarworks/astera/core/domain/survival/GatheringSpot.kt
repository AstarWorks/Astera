package com.astarworks.astera.core.domain.survival

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Harvestable resource node placed in the survival world (Wave 5pve-survival
 * / ADR-0107).
 *
 * Author-defined in `content/gathering/<id>.yaml`; loaded into
 * `IGatheringSpotRegistry` at server start. The Wave 6 Paper-side
 * gathering listener picks the matching spot by world + position +
 * trigger family, calls `HarvestGatheringSpotUseCase`, and emits a
 * `GatheringSpotHarvested` event with the rolled yields.
 *
 * # Position vs. spawn area
 *
 * [position] is the spot's canonical block coord. Spawn radius / multi-
 * spawner setups are deferred to Phase 4 (idea md §"observation note":
 * iron-vein-cluster wants a randomised offset). Foundation wave models
 * point-spots only — extension is additive on this record.
 *
 * # Cooldown
 *
 * [cooldownTicks] is the per-spot global cooldown (20 ticks = 1 s).
 * Per-player cooldown ([GatheringProgress]) layers on top in the
 * repository. Per-spot is enforced first so a party of 5 cannot drain a
 * single iron vein by pulse-clicking; per-player ensures a single player
 * cannot lock the rotation by camping (`min(cooldownTicks,
 * perPlayerCooldownTicks)` semantics, see [HarvestGatheringSpotUseCase]).
 *
 * # Why no `enabled` flag
 *
 * Disabling a spot = removing it from the registry. Carrying a runtime
 * disable bit would let two layers of "off" disagree (YAML enabled=false
 * vs. admin /disable). Removal is the SSOT — `/astera-survival reload`
 * is the off switch.
 */
public data class GatheringSpot(
    val id: GatheringSpotId,
    val displayName: LocalizedText,
    val kind: GatheringSpotKind,
    val trigger: GatheringSkillTrigger,
    val world: WorldId,
    val position: Vec3,
    val yields: List<GatheringYield>,
    val cooldownTicks: Long,
) {
    init {
        require(cooldownTicks > 0L) {
            "GatheringSpot.cooldownTicks must be > 0, was $cooldownTicks"
        }
        require(yields.isNotEmpty()) {
            "GatheringSpot.yields must not be empty (spot would never drop)"
        }
        require(kind.matches(trigger)) {
            "GatheringSpot.trigger ($trigger) is not valid for kind ($kind)"
        }
    }
}

/**
 * True if [trigger] is a legal action against this [GatheringSpotKind].
 * Encodes the kind ↔ trigger compatibility matrix in one place so both the
 * registry validator and the harvest use case agree.
 */
public fun GatheringSpotKind.matches(trigger: GatheringSkillTrigger): Boolean =
    when (this) {
        GatheringSpotKind.Fishing -> trigger is GatheringSkillTrigger.FishingRodCast
        GatheringSpotKind.Mining -> trigger is GatheringSkillTrigger.PickaxeSwing
        GatheringSpotKind.Logging -> trigger is GatheringSkillTrigger.AxeSwing
        GatheringSpotKind.Digging -> trigger is GatheringSkillTrigger.ShovelDig
    }
