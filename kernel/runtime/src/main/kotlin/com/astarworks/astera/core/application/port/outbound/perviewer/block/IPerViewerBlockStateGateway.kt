package com.astarworks.astera.core.application.port.outbound.perviewer.block

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import com.astarworks.astera.core.domain.perviewer.PerViewerOverrideError
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port — per-viewer custom block override (Wave 5y-ext, ADR-0112).
 *
 * Where [com.astarworks.astera.core.application.port.outbound.IBlockStateGateway]
 * mutates the world block state for every viewer, this gateway stages
 * **per-viewer** packet-level overrides without touching the underlying world.
 * Use cases:
 *
 *  - **Spy ability hidden door** — show a normal stone block as a passage
 *    only for the player holding the spy gadget.
 *  - **Tutorial highlight** — replace the texture of a quest-target block
 *    with a glow / arrow glyph only for the student.
 *  - **Spectator camo** — render a defender's mine field as plain dirt for
 *    spectators while attackers see the live mines.
 *  - **Accessibility overlay** — swap low-contrast block textures to a
 *    high-contrast glyph variant per-viewer based on settings.
 *
 * # Mutator semantics
 *
 * Three orthogonal override axes; each axis is its own per-viewer slot, so
 * a [overrideBlockType] followed by [overrideTexture] yields **the texture
 * applied on top of the swapped type** (Wave 6 packet order: type packet
 * first, then texture-glyph packet). [hideBlock] is exclusive — once hidden
 * the type / texture slots are ignored at egress (gateway still records
 * them so a future [showBlock] restores them in one step).
 *
 * # Position keying
 *
 * Per-(world, position) tuple. The position is **block-space** rounded
 * (use the integer floor of [Vec3] coordinates); the gateway does not
 * snap on the caller's behalf because some use cases want to override the
 * sub-block (e.g. Display entity floating in air) and Wave 6's adapter
 * routes those through a different packet family.
 *
 * # Persistence
 *
 * **Session-scoped only.** No DB table; cleared on player quit via the
 * gateway's `PlayerQuitServer` subscriber. Persistent per-viewer block
 * overrides are intentionally out of scope (would require a tile-entity
 * style replay journal; deferred to a future wave).
 *
 * Implementations are NOT thread-safe.
 */
public interface IPerViewerBlockStateGateway {

    /**
     * Override the rendered block type for [viewer] at the given coordinates.
     * Other viewers continue to see the world block.
     */
    public fun overrideBlockType(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
        blockType: BlockType,
    ): Result<Unit, PerViewerOverrideError>

    /**
     * Override the rendered texture glyph for [viewer]. The Wave 6 adapter
     * resolves [textureRef] through `ICustomFontRegistry` (Wave 5j) at
     * packet send time. Useful when the underlying block type stays the
     * same but the visual differs (highlight / cracked / glow overlay).
     */
    public fun overrideTexture(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
        textureRef: HudGlyphRef,
    ): Result<Unit, PerViewerOverrideError>

    /**
     * Hide the block entirely for [viewer]. Implementations send a
     * `Block Change` packet with `minecraft:air` (or its equivalent in
     * the chosen packet library). Idempotent re-hide is a no-op.
     */
    public fun hideBlock(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
    ): Result<Unit, PerViewerOverrideError>

    /**
     * Reverse a [hideBlock]. If no hide is staged this is success without
     * an event published. Restores any [overrideBlockType] /
     * [overrideTexture] slot that was masked during the hide.
     */
    public fun showBlock(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
    ): Result<Unit, PerViewerOverrideError>

    /**
     * Drop every override axis (type + texture + hide) for one (viewer,
     * position) tuple. Idempotent.
     */
    public fun clearOverride(
        viewer: PlayerId,
        world: WorldId,
        position: Vec3,
    ): Result<Unit, PerViewerOverrideError>

    /**
     * Clear every per-viewer block override for [viewer] across every
     * world. Returns the number of positions that held an override.
     *
     * Wired to `PlayerQuitServer` by the gateway's internal subscriber.
     */
    public fun clearAllOverridesForViewer(viewer: PlayerId): Int
}
