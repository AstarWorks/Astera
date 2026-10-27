package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.IToolBlockGateway
import com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.GameRules
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Server
import org.bukkit.Sound
import org.bukkit.inventory.ItemStack
import java.util.Base64

/** Vanilla mining visuals and loot, including the world's tile-drop rule. */
public class PaperToolBlockGateway(
    private val server: Server,
    private val items: () -> IItemRegistry?,
    private val materializer: PaperItemMaterializer,
) : IToolBlockGateway {
    private fun block(world: WorldId, cell: Vec3) = server.getWorld(world.value)?.getBlockAt(cell.x.toInt(), cell.y.toInt(), cell.z.toInt())

    override fun surface(world: WorldId, cell: Vec3): IToolBlockGateway.Surface? = block(world, cell)?.let {
        IToolBlockGateway.Surface(it.blockData.asString, it.type.hardness.toDouble())
    }

    override fun heldTool(player: PlayerId, kind: String?, tier: String?): String? {
        val held = server.getPlayer(player.uuid)?.inventory?.itemInMainHand?.takeIf { !it.isEmpty && !it.type.isAir }
        val usable = held?.takeIf { kind == null || kind == "NONE" || it.type.name.endsWith("_$kind") }
        val stack = usable ?: standIn(kind, tier) ?: held ?: return null
        return runCatching { Base64.getEncoder().encodeToString(stack.serializeAsBytes()) }.getOrNull()
    }

    private fun standIn(kind: String?, tier: String?): ItemStack? {
        if (kind == null || kind == "NONE" || tier == null || tier == "NONE") return null
        val prefix = if (tier == "WOOD") "WOODEN" else tier
        return Material.matchMaterial("${prefix}_$kind")?.let { ItemStack(it) }
    }

    override fun crack(world: WorldId, cell: Vec3, progress: Double) {
        val b = block(world, cell) ?: return
        val source = listOf(world.value, b.x, b.y, b.z).hashCode()
        for (viewer in b.world.players) {
            if (viewer.location.distanceSquared(b.location) <= 32.0 * 32.0) {
                viewer.sendBlockDamage(b.location, progress.toFloat(), source)
            }
        }
    }

    override fun clink(world: WorldId, cell: Vec3) {
        val b = block(world, cell) ?: return
        b.world.playSound(b.location.add(0.5, 0.5, 0.5), Sound.BLOCK_ANVIL_HIT, 0.5f, 1.5f)
    }

    override fun breakBlock(world: WorldId, cell: Vec3, player: PlayerId, toolData: String?) {
        val b = block(world, cell) ?: return
        val tool = toolData?.let { ItemStack.deserializeBytes(Base64.getDecoder().decode(it)) } ?: ItemStack(Material.AIR)
        val drops = if (b.world.getGameRuleValue(GameRules.BLOCK_DROPS) != false) b.getDrops(tool, server.getPlayer(player.uuid)) else emptyList()
        val centre = b.location.add(0.5, 0.5, 0.5)
        b.type = Material.AIR
        drops.forEach { b.world.dropItem(centre, it) }
    }

    override fun effect(world: WorldId, cell: Vec3, data: String, placing: Boolean) {
        val b = block(world, cell) ?: return
        val visual = server.createBlockData(data)
        val centre = b.location.add(0.5, 0.5, 0.5)
        b.world.spawnParticle(Particle.BLOCK, centre, if (placing) 4 else 16, 0.2, 0.2, 0.2, visual)
        b.world.playSound(centre, if (placing) visual.soundGroup.placeSound else visual.soundGroup.breakSound, visual.soundGroup.volume, visual.soundGroup.pitch)
    }

    override fun dropCustom(world: WorldId, cell: Vec3, player: PlayerId, item: ItemId) {
        val b = block(world, cell) ?: return
        if (b.world.getGameRuleValue(GameRules.BLOCK_DROPS) == false) return
        val spec = items()?.find(item) ?: return
        b.world.dropItem(b.location.add(0.5, 0.5, 0.5), materializer.buildFor(spec, player, 1))
    }
}
