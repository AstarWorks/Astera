package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Domain event: a skill cast reduced a block's HP to 0 and consumed it
 * (Wave 5x, ADR-0090).
 *
 * Terminal counterpart of [BlockDamagedBySkill]. Carries the pre-resolved
 * [drops] list (one entry per dropped item instance — multi-count drops
 * appear repeated) so subscribers can spawn item entities or credit the
 * breaker's loot tracker without re-running the drop roll.
 *
 * Differs from Wave 5d's [BlockBroken]:
 *  - No `matchId` — custom blocks live in the open world too.
 *  - Carries the [skillId] that fired the killing hit.
 *  - Carries the resolved [drops] inline (Wave 5d had loot routed through a
 *    separate listener chain).
 *
 * Both events will coexist after Paper-sweep: BlockBroken stays the
 * listener-driven path (vanilla pickaxe breaks via BukkitEventAdapter),
 * BlockBrokenBySkill is the skill-driven path.
 */
public data class BlockBrokenBySkill(
    val world: WorldId,
    val position: Vec3,
    val skillId: SkillId,
    val attackerId: PlayerId,
    val blockType: BlockType,
    val drops: List<ItemId>,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
