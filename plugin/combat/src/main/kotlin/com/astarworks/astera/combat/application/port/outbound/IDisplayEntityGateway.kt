package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.DisplayTransform
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

    /**
     * Sets the display's `transformation` and lets the client interpolate to it over
     * [interpolationTicks] (`interpolation_delay = 0`; 0 = apply immediately).
     */
    public fun update(handle: DisplayHandle, transform: DisplayTransform, interpolationTicks: Int)

    /** Swaps the shown block of a block display. */
    public fun setBlock(handle: DisplayHandle, blockKey: String)

    /** Swaps the (already localized / MiniMessage) lines of a text display. */
    public fun setText(handle: DisplayHandle, renderedLines: List<String>)

    /** Moves the display; the client smooths the move over [teleportTicks] (`teleport_duration`). */
    public fun teleport(handle: DisplayHandle, at: Vec3, yaw: Float, pitch: Float, teleportTicks: Int)

    /** Removes a display; unknown / already removed handles are ignored. */
    public fun remove(handle: DisplayHandle)

    /** Opaque id of a spawned display. */
    @JvmInline
    public value class DisplayHandle(public val id: UUID)
}
