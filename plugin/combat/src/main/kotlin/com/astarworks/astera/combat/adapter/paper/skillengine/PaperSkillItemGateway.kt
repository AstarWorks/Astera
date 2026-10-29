package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import org.bukkit.Server
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * Paper [ISkillItemGateway] (R40). Astera items are found in the holder's inventory by their PDC `item_id` (kept
 * 1:1). The per-skill cooldown goes to [ICooldownTracker] under the key `CastSkillUseCase` uses; a gated `use: skill:<id>` call
 * spends the called skill's own cooldown and resource cost. Trackers are looked up lazily: Items / Stats register them after
 * Combat wires.
 */
public class PaperSkillItemGateway(
    private val server: Server,
    private val skills: () -> ISkillRegistry? = { null },
    private val cooldowns: () -> ICooldownTracker? = { null },
    private val resources: () -> IResourcePoolService? = { null },
) : ISkillItemGateway {
    override fun consume(cast: CastRef, holder: UUID, item: String, count: Int): Boolean {
        val inv = server.getPlayer(holder)?.inventory ?: return false
        var left = count
        for (slot in 0 until inv.size) {
            if (left <= 0) break
            val stack = inv.getItem(slot) ?: continue
            if (idOf(stack) != item) continue
            val take = minOf(left, stack.amount)
            if (take >= stack.amount) inv.setItem(slot, null) else stack.amount -= take
            left -= take
        }
        return left <= 0
    }

    override fun hasItem(cast: CastRef, holder: UUID, item: String): Boolean {
        val inv = server.getPlayer(holder)?.inventory ?: return false
        return (0 until inv.size).any { slot -> inv.getItem(slot)?.let { idOf(it) == item } == true }
    }

    override fun cooldown(cast: CastRef, skill: String, ticks: Int) {
        if (ticks <= 0) return
        val tracker = cooldowns() ?: error("skill engine: no ICooldownTracker; the cooldown of '$skill' cannot start")
        tracker.start(PlayerId(cast.caster), CooldownKey.Skill(skill), ticks.toLong())
    }

    override fun applyGate(cast: CastRef, skill: String, cooldown: Boolean, cost: Boolean) {
        val registry = skills() ?: error("skill engine: no skill registry; the gate of '$skill' cannot apply")
        val spec = registry.find(SkillId(skill)) ?: error("skill engine: gated call of unknown skill '$skill'")
        if (cooldown) cooldown(cast, skill, spec.cooldownTicks)
        if (cost) {
            val type = spec.resourceType
            val amount = spec.resourceCost ?: 0
            if (type != null && amount > 0) {
                val pool = resources() ?: error("skill engine: no IResourcePoolService; the cost of '$skill' cannot be spent")
                pool.spendResource(PlayerId(cast.caster), type, amount)
            }
        }
    }

    private fun idOf(stack: ItemStack): String? {
        val pdc = stack.itemMeta?.persistentDataContainer ?: return null
        val key = pdc.keys.firstOrNull { it.key == "item_id" } ?: return null
        return pdc.get(key, PersistentDataType.STRING)
    }
}
