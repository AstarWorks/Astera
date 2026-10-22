package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationPillarTargets
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Bukkit side of Annihilation pillar damage by block breaking (WP10).
 *
 * A pillar block never breaks the vanilla way: every break attempt on a
 * standing pillar block is cancelled and instead deals the match's
 * `pillarBreakDamage` to the pillar through [AnnihilationPillarTargets] (which
 * applies the match / own-team / phase rules). Explosions never take a pillar
 * block out of the world either — the pillar's HP is the only thing that
 * removes it.
 *
 * Skill damage does not come through here: it reaches the pillar via Combat's
 * structure-aware targeting.
 */
public class PaperPillarBlockListener(
    private val pillars: AnnihilationPillarTargets,
) : Listener {

    public fun start(plugin: JavaPlugin): PaperPillarBlockListener {
        plugin.server.pluginManager.registerEvents(this, plugin)
        return this
    }

    @EventHandler(priority = EventPriority.HIGH)
    public fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        val verdict = pillars.onBlockBreak(
            player = PlayerId(event.player.uniqueId),
            world = WorldId(block.world.name),
            blockPosition = Vec3(block.x.toDouble(), block.y.toDouble(), block.z.toDouble()),
        )
        if (verdict is AnnihilationPillarTargets.BlockBreakVerdict.Pillar) {
            event.isCancelled = true
            event.isDropItems = false
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { isPillarBlock(it.world.name, it.x, it.y, it.z) }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { isPillarBlock(it.world.name, it.x, it.y, it.z) }
    }

    private fun isPillarBlock(world: String, x: Int, y: Int, z: Int): Boolean =
        pillars.targetAtBlock(WorldId(world), Vec3(x.toDouble(), y.toDouble(), z.toDouble())) != null
}
