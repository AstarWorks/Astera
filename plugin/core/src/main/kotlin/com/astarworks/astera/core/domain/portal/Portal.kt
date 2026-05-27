package com.astarworks.astera.core.domain.portal

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Authored definition of an in-world portal (Wave 5block-embed, ADR-0120).
 *
 * Portals are first-class because the Block audit found Astera had no
 * unified portal abstraction — only ad-hoc teleport blocks per stage.
 * A single [Portal] record carries source geometry, target destination,
 * and activation rule so:
 *
 *  - Stage authors can drop a YAML entry into `content/portals/` (Wave
 *    6 onwards) without writing Kotlin.
 *  - The [IPortalGateway] indexes by `(world, position)` so the runtime
 *    activation listeners can lookup-then-teleport in O(positions).
 *  - Replay capture has a stable [PortalId] to emit instead of a raw
 *    teleport (clearer replay narration).
 *
 * # Source shape
 *
 * [sourceShape] is sealed so the same [Portal] type covers both block-
 * style portals (one trigger position, e.g. a nether-style frame center)
 * and region-style portals (an AABB the player must enter, e.g. an
 * arena entrance hallway). Activation rules apply uniformly to both
 * shapes.
 *
 * # Target
 *
 * [targetWorld] + [targetPosition] are the discrete teleport destination.
 * Cross-world targets are supported by design (lobby ↔ arena).
 *
 * # Activation
 *
 * [activationRule] decides which player input fires the portal:
 *
 *  - `WalkOn` — the player physically enters the source shape
 *    (subscribes `PlayerMoved`).
 *  - `RightClick` — the player right-clicks a source-shape block
 *    (subscribes `PlayerInteractedBlock`).
 *  - `SneakAct` — the player crouches *and* right-clicks (subset of
 *    `RightClick` filtered by a Phase 1 sneak-state lookup; Wave 6b
 *    adapter detail).
 *  - `Both` — accept both walk-on and right-click. Useful for portals
 *    in tight stage spaces where the player may activate by accident
 *    while running through.
 */
public data class Portal(
    public val id: PortalId,
    public val sourceWorld: WorldId,
    public val sourcePosition: Vec3,
    public val sourceShape: PortalShape,
    public val targetWorld: WorldId,
    public val targetPosition: Vec3,
    public val activationRule: PortalActivationRule,
)

/**
 * The footprint of a portal's source surface (Wave 5block-embed, ADR-0120).
 *
 * Sealed because the activation listeners need an exhaustive `when` to
 * pick the right hit-test (point equality for [Block], AABB contains for
 * [Region]).
 */
public sealed interface PortalShape {

    /**
     * Single source block at [position] in the portal's `sourceWorld`.
     * The portal triggers when a `PlayerInteractedBlock`/`PlayerMoved`
     * event references exactly this coordinate.
     */
    public data class Block(public val position: Vec3) : PortalShape

    /**
     * AABB source [region] in the portal's `sourceWorld`. The portal
     * triggers when the player's new position falls inside the AABB.
     */
    public data class Region(public val region: com.astarworks.astera.core.domain.stage.Region) : PortalShape
}

/**
 * Which player input fires a [Portal] (Wave 5block-embed, ADR-0120).
 *
 * Sealed for the same exhaustive-`when` reason as [PortalShape].
 */
public sealed interface PortalActivationRule {

    /** Triggers on `PlayerMoved` whose destination falls inside the shape. */
    public data object WalkOn : PortalActivationRule

    /**
     * Triggers on `PlayerInteractedBlock` with action `RightClick` at the
     * shape's position. (For [PortalShape.Region], any block inside the
     * region fires.)
     */
    public data object RightClick : PortalActivationRule

    /**
     * Triggers on `PlayerInteractedBlock` with action `RightClick` AND the
     * player is sneaking at the time. Wave 6b adapter responsibility to
     * gate by sneak state.
     */
    public data object SneakAct : PortalActivationRule

    /** Accepts either [WalkOn] or [RightClick]. */
    public data object Both : PortalActivationRule

    /** True if this rule accepts the WalkOn trigger pathway. */
    public fun acceptsWalkOn(): Boolean = this is WalkOn || this is Both

    /** True if this rule accepts the RightClick trigger pathway. */
    public fun acceptsRightClick(): Boolean = this is RightClick || this is SneakAct || this is Both
}
