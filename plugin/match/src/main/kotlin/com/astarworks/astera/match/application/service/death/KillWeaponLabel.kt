package com.astarworks.astera.match.application.service.death

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ProjectileOrigin
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * Localised weapon / skill label of a kill, shared by the death screen
 * ([DeathRespawnCoordinator]) and the kill feed so both name the weapon the same
 * way. `null` for environmental deaths (nothing to name).
 */
internal object KillWeaponLabel {

    fun of(
        viewer: PlayerId,
        source: DamageSource,
        items: IItemRegistry?,
        skills: ISkillRegistry?,
        messages: IMessageRenderer,
    ): String? = when (source) {
        is DamageSource.Item -> itemName(viewer, source.itemId, items, messages)
        is DamageSource.Skill -> skillName(viewer, source.skillId, skills, messages)
        is DamageSource.Projectile -> when (val origin = source.origin) {
            is ProjectileOrigin.FromItem -> itemName(viewer, origin.itemId, items, messages)
            is ProjectileOrigin.FromSkill -> skillName(viewer, origin.skillId, skills, messages)
        }
        else -> null
    }

    /** The skill's own display name; the raw id only when the registry does not know it. */
    private fun skillName(viewer: PlayerId, id: SkillId, skills: ISkillRegistry?, messages: IMessageRenderer): String =
        skills?.find(id)?.let { messages.renderText(viewer, it.name) }?.takeIf { it.isNotBlank() } ?: id.value

    private fun itemName(viewer: PlayerId, id: ItemId, items: IItemRegistry?, messages: IMessageRenderer): String? =
        items?.find(id)?.let { messages.renderText(viewer, it.displayName) }?.takeIf { it.isNotBlank() }
}
