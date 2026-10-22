package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID

/**
 * Host-neutral display-entity output for `Mechanic.SpawnDisplay` (WP5): a
 * block / item / text display (Paper `BlockDisplay` / `ItemDisplay` /
 * `TextDisplay`). The caller owns the lifetime: it calls [remove] once
 * `DisplaySpec.lifetimeTicks` elapsed (and on shutdown).
 */
public interface IDisplayEntityGateway {

    /**
     * Spawns a display at [at]. [renderedLines] is the already-localized text
     * for `DisplayKind.Text` (empty for the other kinds). Returns an opaque
     * handle, or `null` when the world is not loaded or the kind is unsupported.
     */
    public fun spawn(world: WorldId, at: Vec3, spec: DisplaySpec, renderedLines: List<String>): DisplayHandle?

    /** Removes a display; unknown / already removed handles are ignored. */
    public fun remove(handle: DisplayHandle)

    /** Opaque id of a spawned display. */
    @JvmInline
    public value class DisplayHandle(public val id: UUID)
}
