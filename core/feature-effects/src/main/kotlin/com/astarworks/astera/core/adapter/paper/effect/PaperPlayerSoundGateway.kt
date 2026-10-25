package com.astarworks.astera.core.adapter.paper.effect

import com.astarworks.astera.core.application.port.outbound.effect.IPlayerSoundGateway
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.SoundCategory
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [IPlayerSoundGateway] (Wave 6d, ADR-0161).
 *
 * Overlays the default no-op [com.astarworks.astera.core.application.service.effect.LoggingPlayerSoundGateway]
 * at priority 100, routing through Bukkit's
 * `Player.playSound(Location, String, SoundCategory, Float, Float)` overload
 * so only the target viewer hears the cue. Closes the Wave 5effects
 * (ADR-0109) gateway-boundary contract.
 *
 * # Why the `String` key overload
 *
 * [SoundSpec.key] follows the vendor-neutral `namespace:name` form
 * (e.g. `minecraft:entity.lightning_bolt.thunder`). Resolving to a
 * Bukkit `Sound` enum would require a registry lookup that fails on any
 * key the enum doesn't ship — including data-pack sounds. The `String`
 * overload delegates lookup to the client, matching the resource-pack
 * source-of-truth pattern.
 *
 * # Sound category
 *
 * Defaults to [SoundCategory.MASTER] since [SoundSpec] does not carry a
 * category in Wave 5effects scope. A future spec field can swap this if
 * combat / ambient / UI separation becomes useful for player audio
 * preferences.
 *
 * # Location resolution
 *
 *  - If [position] + [world] supplied → build [Location] from those.
 *  - If either missing → fall back to `player.location`. This matches
 *    the port KDoc convention ("`null` defers to the player's current
 *    location / world").
 *
 * # Failure modes
 *
 * - Target offline → silent no-op (per port contract: fire-and-forget,
 *   no error type since `playForViewer` returns `Unit`).
 * - World unloaded (with [world] supplied) → debug log + use player's
 *   current world. The sound still plays at the requested coordinates
 *   in the player's frame.
 *
 * # ADR-0149 §C
 *
 * N/A — does not touch the player damage / health pipeline.
 *
 * # Registration
 *
 * Registered at priority 100 in `AsteraCorePlugin.onEnable` (overlays
 * `LoggingPlayerSoundGateway` priority 0).
 */
public class PaperPlayerSoundGateway(
    private val server: Server,
) : IPlayerSoundGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun playForViewer(
        playerId: PlayerId,
        sound: SoundSpec,
        position: Vec3?,
        world: WorldId?,
    ) {
        val player = server.getPlayer(playerId.uuid)
        if (player == null) {
            if (log.isDebugEnabled) {
                log.debug(
                    "PaperPlayerSoundGateway: player {} offline; skipping sound {}",
                    playerId, sound.key,
                )
            }
            return
        }

        val location: Location = if (position != null) {
            val bukkitWorld = world?.let { server.getWorld(it.value) } ?: player.world
            if (world != null && server.getWorld(world.value) == null && log.isDebugEnabled) {
                log.debug(
                    "PaperPlayerSoundGateway: world {} not loaded; using player's current world for sound {}",
                    world, sound.key,
                )
            }
            Location(bukkitWorld, position.x, position.y, position.z)
        } else {
            player.location
        }

        player.playSound(
            location,
            sound.key,
            SoundCategory.MASTER,
            sound.volume.toFloat(),
            sound.pitch.toFloat(),
        )
    }
}
