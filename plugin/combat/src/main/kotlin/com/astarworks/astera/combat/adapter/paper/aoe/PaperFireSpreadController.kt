package com.astarworks.astera.combat.adapter.paper.aoe

import com.astarworks.astera.combat.application.port.outbound.aoe.IFireSpreadController
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.GameRule
import org.bukkit.Material
import org.bukkit.Server
import org.slf4j.LoggerFactory
import kotlin.math.floor

/**
 * Paper concrete of [IFireSpreadController] (Wave 6d, ADR-0161) — closes
 * the Wave 5m (ADR-0088) AOE-port gateway-boundary contract.
 *
 * # Three methods
 *
 *  - [setFireSpread] → `World.setGameRule(GameRule.DO_FIRE_TICK, enabled)`.
 *  - [isFireSpreadEnabled] → `World.getGameRuleValue(GameRule.DO_FIRE_TICK)`,
 *    defaulting to `true` (vanilla default for new worlds) when the world
 *    is unloaded or the rule lookup returns `null`.
 *  - [spreadFire] → walk a spherical region around `source`, set [Material.FIRE]
 *    on AIR blocks, count fires placed.
 *
 * # Radius cap
 *
 * Caps the radius at [RADIUS_MAX] (16 blocks) to bound the block-walk cost
 * (`O(r³)` worst case → ~33,000 block reads at r=16). A skill yaml that
 * requests a larger radius is clamped silently and a debug log is emitted.
 * Larger AOE shapes belong in `IAreaEffectGateway` (a future server-side
 * particle / box-bounded variant), not here.
 *
 * # Spherical not cubic
 *
 * `dx² + dy² + dz² ≤ radius²` keeps the ignition front round — matches
 * player intuition for "fire spreads within R blocks" and avoids
 * surprise corner-of-cube ignitions at √3·R reach.
 *
 * # AIR-only ignition
 *
 * Sets `block.type = Material.FIRE` **only** when the current block is
 * AIR. Replacing stone / wood / wool with fire is destructive and matches
 * neither vanilla fire-spread semantics nor any reasonable skill design.
 * Counts only blocks that transitioned AIR → FIRE.
 *
 * # Threading
 *
 * `Block.setType` MUST be called on the main thread. The port has no
 * `Result` return for off-main failure, so off-main calls log a WARN and
 * return `0`. Production callers (combat skill use-cases) run sync from
 * event handlers per the port KDoc's threading section.
 *
 * # ADR-0149 §C
 *
 * N/A — does not touch player damage / health. Entities standing on placed
 * fire blocks take vanilla fire damage via the regular tick pipeline,
 * which routes through Wave 6a `PaperDamageGateway` upstream.
 *
 * # Registration
 *
 * Registered at priority 100 in `AsteraCombatPlugin.onEnable` (overlays
 * `InMemoryFireSpreadController` priority 0).
 */
public class PaperFireSpreadController(
    private val server: Server,
) : IFireSpreadController {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun isFireSpreadEnabled(world: WorldId): Boolean {
        val bukkitWorld = server.getWorld(world.value) ?: return true
        return bukkitWorld.getGameRuleValue(GameRule.DO_FIRE_TICK) ?: true
    }

    override fun setFireSpread(world: WorldId, enabled: Boolean) {
        val bukkitWorld = server.getWorld(world.value)
        if (bukkitWorld == null) {
            if (log.isDebugEnabled) {
                log.debug(
                    "PaperFireSpreadController: world {} not loaded; setFireSpread({}) ignored",
                    world, enabled,
                )
            }
            return
        }
        bukkitWorld.setGameRule(GameRule.DO_FIRE_TICK, enabled)
    }

    override fun spreadFire(world: WorldId, source: Vec3, radius: Int): Int {
        if (server.getWorld(world.value)?.let { org.bukkit.Bukkit.isOwnedByCurrentRegion(org.bukkit.Location(it, source.x, source.y, source.z)) } != true) {
            log.warn(
                "PaperFireSpreadController.spreadFire invoked off-main (world={}, source={}); " +
                    "Block.setType requires main thread — returning 0.",
                world, source,
            )
            return 0
        }
        val bukkitWorld = server.getWorld(world.value)
        if (bukkitWorld == null) {
            if (log.isDebugEnabled) {
                log.debug(
                    "PaperFireSpreadController: world {} not loaded; spreadFire returns 0",
                    world,
                )
            }
            return 0
        }
        if (radius <= 0) return 0
        val clamped = radius.coerceAtMost(RADIUS_MAX)
        if (clamped != radius && log.isDebugEnabled) {
            log.debug(
                "PaperFireSpreadController: radius {} clamped to {} (RADIUS_MAX)",
                radius, RADIUS_MAX,
            )
        }

        val cx = floor(source.x).toInt()
        val cy = floor(source.y).toInt()
        val cz = floor(source.z).toInt()
        val rSquared = clamped * clamped

        var ignited = 0
        for (dx in -clamped..clamped) {
            val dxSq = dx * dx
            for (dy in -clamped..clamped) {
                val dySq = dy * dy
                if (dxSq + dySq > rSquared) continue
                for (dz in -clamped..clamped) {
                    if (dxSq + dySq + dz * dz > rSquared) continue
                    val block = bukkitWorld.getBlockAt(cx + dx, cy + dy, cz + dz)
                    if (block.type == Material.AIR) {
                        block.type = Material.FIRE
                        ignited++
                    }
                }
            }
        }
        return ignited
    }

    public companion object {
        /**
         * Hard cap on `spreadFire` radius. At r=16 the inner loop visits
         * `(2r+1)³ ≈ 35,937` blocks — already a noticeable main-thread
         * stall. Anything larger should use an `IAreaEffectGateway`
         * (region-based) port instead.
         */
        public const val RADIUS_MAX: Int = 16
    }
}
