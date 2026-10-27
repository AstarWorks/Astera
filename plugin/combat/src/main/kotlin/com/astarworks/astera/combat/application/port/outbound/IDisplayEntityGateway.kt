package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.effect.Audience
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
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

    /**
     * Overrides the `item_model` of an item display's stack with [itemModel] (`namespace:path`); `null` restores the stack the
     * display was spawned with. The same entity, via entity metadata (late-tracking viewers get the current stack).
     */
    public fun setItemModel(handle: DisplayHandle, itemModel: String?)

    /** Swaps the (already localized / MiniMessage) lines of a text display. */
    public fun setText(handle: DisplayHandle, renderedLines: List<String>)

    /**
     * Restyles a text display: background ARGB, `text_opacity` (0..255) and, when [textRgb] is set, the text colour.
     * The client does not interpolate these, so animated colour / alpha is one call per sample.
     */
    public fun setTextStyle(handle: DisplayHandle, backgroundArgb: Int, textOpacity: Int, textRgb: Int?)

    /** Moves the display; the client smooths the move over [teleportTicks] (`teleport_duration`). */
    public fun moveTo(handle: DisplayHandle, at: Vec3, yaw: Float, pitch: Float, teleportTicks: Int)

    /** Spawn at the native riding origin with a corrected initial transform. Null requests ordinary follow fallback. */
    public fun spawnAttached(
        world: WorldId,
        spec: DisplaySpec,
        renderedLines: List<String>,
        anchor: PlayerId,
        offset: Vec3,
    ): DisplayHandle? = null

    /** Whether this live display is still riding the requested anchor in the requested world. */
    public fun isAttached(handle: DisplayHandle, anchor: PlayerId, world: WorldId): Boolean = false

    /**
     * Sets `view_range`. The client reads it every frame (not per tick), so `0` hides an already ticked display
     * and the original value shows it again in the same frame as any other packet of that server tick.
     */
    public fun setViewRange(handle: DisplayHandle, viewRange: Float) {}

    /** Whether [player] wears a slim (Alex) skin; selects the arm marker of the skin-rig bones (ADR-0276). */
    public fun isSlimSkin(player: PlayerId): Boolean = false

    /** Removes a display; unknown / already removed handles are ignored. */
    public fun remove(handle: DisplayHandle)

    /** Opaque id of a spawned display. */
    @JvmInline
    public value class DisplayHandle(public val id: UUID)
}
