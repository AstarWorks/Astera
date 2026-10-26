package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Published when a per-viewer block override is staged on
 * [com.astarworks.astera.core.application.port.outbound.perviewer.block.IPerViewerBlockStateGateway]
 * (Wave 5y-ext, ADR-0112).
 *
 * Fires for every change kind — type swap, texture override, hide / show —
 * with the post-state encoded as an exclusive triple. Exactly one of
 * [overrideType] / [overrideTexture] / [hidden] is non-default per emission;
 * the init block enforces this so subscribers cannot ambiguously decode the
 * payload.
 *
 *  - `overrideType != null` — viewer sees a different block kind
 *  - `overrideTexture != null` — viewer sees the same block kind but a
 *    swapped texture glyph
 *  - `hidden == true` — viewer sees nothing (server-side block stays placed
 *    for everyone else)
 *
 * Subscribers: replay capture, anticheat (sanity check on impossible block
 * sees), accessibility HUD (toggle high-contrast block overlay).
 *
 * Cleared variant emits a separate sibling event with all three fields back
 * to default — that is reported via [PerViewerOverridesCleared] at sweep
 * time and an explicit no-arg clear is rare enough that we don't add a
 * dedicated `BlockOverrideRemoved` event; tests assert on the cleared-via-
 * sweep path only.
 */
public data class BlockOverrideApplied(
    val viewer: PlayerId,
    val world: WorldId,
    val position: Vec3,
    val overrideType: BlockType? = null,
    val overrideTexture: HudGlyphRef? = null,
    val hidden: Boolean = false,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(viewer.uuid)

    init {
        val nonDefault = listOf(
            overrideType != null,
            overrideTexture != null,
            hidden,
        ).count { it }
        require(nonDefault == 1) {
            "BlockOverrideApplied requires exactly one of overrideType / overrideTexture / hidden " +
                "to be set per emission (got $nonDefault). Use PerViewerOverridesCleared for sweeps."
        }
    }
}
