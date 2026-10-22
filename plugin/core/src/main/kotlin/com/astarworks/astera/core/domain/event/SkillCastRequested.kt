package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.PhaseOverrides
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTrigger
import java.time.Instant

/**
 * Cross-plugin event (Wave _items-1, ADR-0073): an item-driven skill cast was
 * requested.
 *
 * Items publish this on the [com.astarworks.astera.core.application.port.outbound.IDomainEventBus]
 * when a player input fires one of the item's [com.astarworks.astera.core.domain.item.ItemSkillBinding]s.
 * Combat subscribes (because Combat softdepends on Items per ADR-0073 §wiring)
 * and runs the skill resolution + effect pipeline.
 *
 * Going through an event keeps Combat from depending directly on the Items
 * plugin's adapter shape — only the cross-plugin domain event surface is
 * shared, in line with ADR-0022.
 *
 * # Location
 *
 * [origin] is a `Vec3` (core.domain.geometry); the world the player is in
 * lives separately on the player session — the event itself only carries the
 * raw position so subscribers don't have to round-trip through the player
 * adapter for ranged effects (knockback origin, projectile spawn).
 *
 * @property cooldownOverrideTicks Carries forward the override from
 *   [com.astarworks.astera.core.domain.item.ItemSkillBinding.cooldownOverrideTicks]
 *   so the combat-side cooldown service can apply the right number. `null`
 *   means "use the skill's default cooldown".
 *
 * @property phaseOverrides Wave _skills-2 (ADR-0077): carries the binding's
 *   per-phase mechanic decoration into Combat so `MechanicDispatcher` can
 *   apply [com.astarworks.astera.core.domain.item.PhaseOverrides.applyTo] to
 *   the skill's base [com.astarworks.astera.core.domain.skill.phase.PhaseMechanics]
 *   before dispatch. Defaults to
 *   [com.astarworks.astera.core.domain.item.PhaseOverrides.EMPTY] for
 *   non-item-driven casts (e.g. system-fired skills).
 *
 * @property trigger the input of the binding that fired (`null` for casts that are not input-driven);
 *   the cooldown HUD files the cooldown under this input (ADR-0195).
 */
public data class SkillCastRequested(
    public val actorId: PlayerId,
    public val skillId: SkillId,
    public val origin: Vec3,
    public val sourceItemId: ItemId,
    public val cooldownOverrideTicks: Int?,
    public val phaseOverrides: PhaseOverrides = PhaseOverrides.EMPTY,
    public val trigger: SkillTrigger? = null,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
