package com.astarworks.astera.core.domain.entity

/**
 * Two-way click discriminator for NPC right/left interactions.
 *
 * Distinct from [com.astarworks.astera.core.domain.skill.SkillTrigger] (which
 * also carries `LEFT_CLICK_AIR` / `RIGHT_CLICK_BLOCK` variants for the items
 * → skills chain): NPC interaction is **always** "click an entity" so the
 * block / air axis collapses out — keep the enum tiny and obvious.
 *
 * Used by [com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter]
 * and the [com.astarworks.astera.core.domain.event.NpcInteracted] domain event.
 */
public enum class NpcInteractionClick {
    LEFT,
    RIGHT,
}
