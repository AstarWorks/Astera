package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Domain event: a skill cast reduced a block's HP without breaking it
 * (Wave 5x, ADR-0090).
 *
 * Companion to [BlockBrokenBySkill] (the "HP hit 0" terminal event) and
 * [CustomBlockPlaced] (the world-side placement of a custom block).
 * Published by `IBlockStateGateway.applyDamageFromSkill` after every
 * non-zero, non-fatal damage application; HUD particle effects /
 * announcements / analytics subscribe.
 *
 * Distinct from [BlockBroken] (Wave 5d) which fires from the listener-driven
 * break path; that event still belongs to the per-match journal, while this
 * one is open-world-friendly (no `matchId` — published from anywhere a
 * skill can land).
 */
public data class BlockDamagedBySkill(
    val world: WorldId,
    val position: Vec3,
    val skillId: SkillId,
    val attackerId: PlayerId,
    val blockType: BlockType,
    val amount: Int,
    val newDurability: Int,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    init {
        require(amount >= 0) {
            "BlockDamagedBySkill.amount must be >= 0, got $amount"
        }
        require(newDurability >= 0) {
            "BlockDamagedBySkill.newDurability must be >= 0, got $newDurability"
        }
    }
}
