package com.astarworks.astera.core.adapter.paper.world

import com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/**
 * Turns a match participant's left-click on a custom cover block (`content/blocks/`, ADR-0090) into one tool swing
 * ([HitCustomBlockUseCase]). Cover blocks are plain vanilla blocks placed by the stage, identified by their unique
 * base material, so vanilla digging is cancelled on them (a survival player must not instant-break cover) and only the
 * field tools hurt them. Outside a match nothing here applies.
 */
public class PaperCoverToolListener(
    plugin: JavaPlugin,
    private val registry: ICustomBlockRegistry,
    private val matches: () -> IMatchRepository?,
    private val useCase: () -> HitCustomBlockUseCase?,
) : Listener {

    private val itemIdKey: NamespacedKey = NamespacedKey(plugin, PaperItemMaterializer.ITEM_ID_KEY)

    public fun start(plugin: JavaPlugin) {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler(priority = EventPriority.HIGH)
    public fun onSwing(event: PlayerInteractEvent) {
        if (event.action != Action.LEFT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        val player = event.player
        if (!isCoverInMatch(player, block)) return
        event.setUseInteractedBlock(Event.Result.DENY)
        event.isCancelled = true
        val held = heldItemId(player) ?: return
        useCase()?.hit(PlayerId(player.uniqueId), WorldId(block.world.name), Vec3(block.x.toDouble(), block.y.toDouble(), block.z.toDouble()), held)
    }

    /** Survival dig start: never let vanilla chew through cover. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onDig(event: BlockDamageEvent) {
        if (isCoverInMatch(event.player, event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onBreak(event: BlockBreakEvent) {
        if (isCoverInMatch(event.player, event.block)) event.isCancelled = true
    }

    private fun isCoverInMatch(player: Player, block: Block): Boolean =
        registry.resolveByBaseMaterial(block.type.name) != null &&
            matches()?.findMatchOf(PlayerId(player.uniqueId)) != null

    private fun heldItemId(player: Player): ItemId? {
        val raw = player.inventory.itemInMainHand.itemMeta?.persistentDataContainer
            ?.get(itemIdKey, PersistentDataType.STRING) ?: return null
        return runCatching { ItemId(raw) }.getOrNull()
    }
}
