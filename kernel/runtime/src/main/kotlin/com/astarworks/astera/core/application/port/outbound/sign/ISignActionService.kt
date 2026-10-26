package com.astarworks.astera.core.application.port.outbound.sign

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.sign.SignAction
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: bind one [SignAction] per `(world, position)` and look
 * it up at dispatch time (Wave 5block-embed, ADR-0120).
 *
 * # Why
 *
 * Block audit found no sign-action service: players who place a `[ASTERA]`
 * magic sign get no behaviour. This port is the **routing table** for
 * signs, mirroring [com.astarworks.astera.core.application.port.outbound.block.IBlockInteractionRouter]
 * but specialised for the sign authoring pattern (4 text lines, single
 * action per sign — distinct right/left channels would clash with
 * vanilla sign-editing UX).
 *
 * # Bindings
 *
 * One binding per `(world, position)`. Re-binding the same position
 * overwrites the prior action. `unbindSign` returns `true` if anything
 * was removed.
 *
 * # Threading
 *
 * Main-thread only; the in-memory impl is not thread-safe.
 *
 * # Producer
 *
 * Phase 1 produces bindings via [SignTextParser] from sign-change events
 * (Wave 6b Bukkit `SignChangeEvent` adapter). The [ParseAndBindSignOnSignChangedListener]
 * skeleton documents the future flow; the actual event listener stays
 * empty in Wave 5block-embed because the producer event lives in
 * `:plugin:items` / Wave 6b adapter territory.
 */
public interface ISignActionService {

    public fun bindSignAction(world: WorldId, position: Vec3, action: SignAction)

    public fun unbindSign(world: WorldId, position: Vec3): Boolean

    public fun lookup(world: WorldId, position: Vec3): SignAction?
}
