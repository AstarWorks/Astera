package com.astarworks.astera.items.adapter.paper

import com.astarworks.astera.core.adapter.api.event.IMcEvent
import com.astarworks.astera.core.adapter.api.event.IMcEvent.InputKind
import com.astarworks.astera.core.adapter.paper.entity.PaperPlayer
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.slf4j.LoggerFactory

/**
 * Paper half of the item input bridge (WP1): translates Bukkit input events
 * into vendor-neutral [IMcEvent.PlayerInput]s and hands them to [sink]
 * (`ItemSkillTriggerListener.handle`). When the sink reports the input as
 * consumed by an item binding, the vanilla side effect is cancelled:
 *
 *  - interact (click air/block): no block break / placement / crossbow load
 *  - attack on an entity: no vanilla melee damage (the bound skill deals it)
 *  - drop key: the item stays in the hand (Q = reload, not a drop)
 *  - bow / crossbow release: no arrow
 *
 * Only Astera items (PDC `astera:item_id`) are ever consumed; vanilla stacks
 * pass through untouched.
 */
public class PaperItemInputListener(
    private val itemIdResolver: PaperItemIdResolver,
    private val sink: (IMcEvent) -> Boolean,
) : Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventHandler
    public fun onInteract(e: PlayerInteractEvent) {
        if (e.hand != EquipmentSlot.HAND) return
        val kind = when (e.action) {
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> InputKind.LEFT_CLICK
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> InputKind.RIGHT_CLICK
            else -> return
        }
        if (dispatch(e.player, kind, e.item)) e.isCancelled = true
    }

    /** Left click on an entity does not raise an interact event; it arrives as damage. */
    @EventHandler
    public fun onAttack(e: EntityDamageByEntityEvent) {
        val attacker = e.damager as? Player ?: return
        if (e.cause != DamageCause.ENTITY_ATTACK) return
        if (dispatch(attacker, InputKind.LEFT_CLICK, attacker.inventory.itemInMainHand)) e.isCancelled = true
    }

    @EventHandler
    public fun onDrop(e: PlayerDropItemEvent) {
        if (dispatch(e.player, InputKind.DROP, e.itemDrop.itemStack)) e.isCancelled = true
    }

    @EventHandler
    public fun onShoot(e: EntityShootBowEvent) {
        val shooter = e.entity as? Player ?: return
        if (dispatch(shooter, InputKind.SHOOT, e.bow)) e.isCancelled = true
    }

    @EventHandler
    public fun onQuit(e: PlayerQuitEvent) {
        sink(IMcEvent.PlayerQuit(PaperPlayer(e.player)))
    }

    private fun dispatch(player: Player, kind: InputKind, stack: ItemStack?): Boolean {
        // Spectators (death cam / eliminated teams) never trigger item skills.
        if (player.gameMode == GameMode.SPECTATOR) return false
        val itemId = itemIdResolver.itemIdOf(stack) ?: return false
        val consumed = sink(IMcEvent.PlayerInput(PaperPlayer(player), kind, player.isSneaking, itemId))
        log.debug("input {} {} item={} consumed={}", player.name, kind, itemId, consumed)
        return consumed
    }
}
