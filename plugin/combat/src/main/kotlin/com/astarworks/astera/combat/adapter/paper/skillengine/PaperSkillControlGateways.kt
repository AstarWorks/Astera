package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillCooldownGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillCasterAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillScreenGateway
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.effect.camera.ShakeDirection
import com.astarworks.astera.core.domain.effect.camera.ShakeProfile
import com.astarworks.astera.core.domain.effect.camera.ShakeShape
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** `set_cooldown` op: starts the player's Astera cooldown for a skill id (the HUD and the cast gate read the same tracker). */
public class PaperSkillCooldownGateway(private val tracker: () -> ICooldownTracker?) : ISkillCooldownGateway {
    override fun set(entity: UUID, skillId: String, ticks: Int) {
        val t = tracker() ?: return
        if (ticks <= 0) t.reset(PlayerId(entity), CooldownKey.Skill(skillId))
        else t.start(PlayerId(entity), CooldownKey.Skill(skillId), ticks.toLong())
    }
}

/**
 * Caster animation: the vanilla player model only has an arm swing and a crouch, so "crouch" holds sneak for a short while
 * and every other clip name plays a main-hand swing.
 */
public class PaperSkillCasterAnimGateway(
    private val server: Server,
    private val scheduler: IScheduler,
) : ISkillCasterAnimGateway {
    override fun play(caster: UUID, anim: String, speed: Double) {
        val player = server.getPlayer(caster) ?: return
        if (anim.equals("crouch", ignoreCase = true)) {
            player.isSneaking = true
            val hold = (10 / speed.coerceAtLeast(0.1)).toLong().coerceAtLeast(2)
            scheduler.runLaterTicks(ExecutionScope.Entity(caster), hold) { server.getPlayer(caster)?.isSneaking = false }
        } else {
            player.swingMainHand()
        }
    }
}

/** Titles, action bar and camera shake through the combat feedback gateway; a shake is a zero-sum series of relative kicks. */
public class PaperSkillScreenGateway(
    private val feedback: IPlayerFeedbackGateway,
    private val scheduler: IScheduler,
) : ISkillScreenGateway {
    override fun title(viewer: UUID, text: String, subtitle: String?, ticks: Int) {
        feedback.sendTitle(PlayerId(viewer), text, subtitle, 5, ticks.coerceAtLeast(1), 10)
    }

    override fun actionBar(viewer: UUID, text: String) {
        feedback.postActionBar(PlayerId(viewer), text)
    }

    override fun cameraShake(viewer: UUID, intensity: Double, ticks: Int) {
        val seed = viewer.leastSignificantBits xor ticks.toLong()
        val pulses = ShakeProfile.plan(ShakeShape.DECAY, ShakeDirection.NONE, intensity, ticks, seed = seed)
        for (p in pulses) {
            if (p.tick <= 0) feedback.cameraKick(PlayerId(viewer), p.dx, p.dy)
            else scheduler.runLaterTicks(ExecutionScope.Entity(viewer), p.tick.toLong()) {
                feedback.cameraKick(PlayerId(viewer), p.dx, p.dy)
            }
        }
    }
}

/** `give_item` / `consume_item`: Astera items are found in the inventory by their PDC `item_id`. */
public class PaperSkillItemGateway(
    private val server: Server,
    private val build: (ItemId, PlayerId, Int) -> ItemStack?,
) : ISkillItemGateway {
    override fun give(entity: UUID, itemId: String, count: Int) {
        val player = server.getPlayer(entity) ?: return
        val stack = build(ItemId(itemId), PlayerId(entity), count) ?: return
        player.inventory.addItem(stack).values.forEach { player.world.dropItem(player.location, it) }
    }

    override fun consume(entity: UUID, itemId: String, count: Int): Int {
        val inv = server.getPlayer(entity)?.inventory ?: return 0
        var left = count
        for (slot in 0 until inv.size) {
            if (left <= 0) break
            val stack = inv.getItem(slot) ?: continue
            if (idOf(stack) != itemId) continue
            val take = minOf(left, stack.amount)
            if (take >= stack.amount) inv.setItem(slot, null) else stack.amount -= take
            left -= take
        }
        return count - left
    }

    private fun idOf(stack: ItemStack): String? {
        val pdc = stack.itemMeta?.persistentDataContainer ?: return null
        val key = pdc.keys.firstOrNull { it.key == "item_id" } ?: return null
        return pdc.get(key, PersistentDataType.STRING)
    }
}
