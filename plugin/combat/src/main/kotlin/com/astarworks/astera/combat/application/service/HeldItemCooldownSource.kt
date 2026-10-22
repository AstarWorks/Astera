package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.application.port.outbound.ICooldownIconSource
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.hud.status.CooldownHudGlyphs
import com.astarworks.astera.core.domain.hud.status.CooldownHudLayout
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * The cooldown icon row's data (ADR-0197): for the item the player holds, one
 * entry per bound input, with the remaining ticks of the bound skill read from
 * the [ICooldownTracker] (cooldowns are keyed by skill, so two items sharing a
 * skill share its clock).
 *
 * A reload skill's cooldown is as long as its reload (`pistol-reload`: both 30
 * ticks), so the Q icon follows the reload.
 */
public class HeldItemCooldownSource(
    private val items: IItemRegistry,
    private val resourcePool: IResourcePoolService,
    private val cooldownTracker: ICooldownTracker,
) : ICooldownIconSource {

    override fun entriesFor(playerId: PlayerId): List<CooldownHudLayout.Entry> {
        val held = resourcePool.currentPool(playerId)?.activeWeapon ?: return emptyList()
        val spec = items.find(held) ?: return emptyList()
        return spec.skillBindings
            .filter { CooldownHudGlyphs.supports(it.trigger) }
            .distinctBy { it.trigger }
            .map { binding ->
                val remaining = cooldownTracker.remainingTicks(playerId, CooldownKey.Skill(binding.skillId.value))
                CooldownHudLayout.Entry(binding.trigger, remaining)
            }
    }
}
