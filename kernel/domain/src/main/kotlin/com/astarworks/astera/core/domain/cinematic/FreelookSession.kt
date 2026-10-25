package com.astarworks.astera.core.domain.cinematic

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Free-look mode session: player movement is locked to [anchorPosition],
 * but the player retains free look (mouse / pitch / yaw) (Wave 5cinematic,
 * ADR-0110).
 *
 * Used by tutorial side-scenes ("look around the lobby") and pre-match
 * staging ("look at the stage while spawning in"). Distinct from the
 * Wave 5h spectator camera (which sets the player to spectator gamemode and
 * untethers them entirely) — free-look keeps the player as their normal
 * gamemode + entity, just movement-frozen.
 *
 * # Pure value object
 *
 * Session lifecycle lives in
 * [com.astarworks.astera.core.application.port.outbound.cinematic.IFreelookGateway].
 * The session value is what the gateway returns + stores per player.
 */
public data class FreelookSession(
    public val playerId: PlayerId,
    public val anchorPosition: Vec3,
)
