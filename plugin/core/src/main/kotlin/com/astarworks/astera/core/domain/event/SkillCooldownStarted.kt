package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTrigger
import java.time.Instant

/**
 * A skill went on cooldown (ADR-0197). Published by `CastSkillUseCase` right
 * after the cooldown tracker started, so item-side adapters can draw the
 * native hotbar sweep without importing combat.
 *
 * @property sourceItemId the item whose binding cast the skill, `null` for a cast without an item
 * @property trigger the input of that binding (`null` for system casts)
 * @property durationTicks the effective cooldown (binding override applied); `0` for a reload skill without cooldown
 * @property reloadTicks non-null when the skill is a reload: the item is unusable that long
 */
public data class SkillCooldownStarted(
    public val playerId: PlayerId,
    public val skillId: SkillId,
    public val sourceItemId: ItemId?,
    public val trigger: SkillTrigger?,
    public val durationTicks: Int,
    public val reloadTicks: Int?,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
