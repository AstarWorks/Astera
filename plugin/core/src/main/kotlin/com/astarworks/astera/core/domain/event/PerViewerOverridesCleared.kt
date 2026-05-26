package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.perviewer.ClearReason
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when a per-viewer override sweep clears every staged override for
 * one player on one gateway (Wave 5y-ext, ADR-0112).
 *
 * Cleared families:
 *  - `"npc"` — sweep on [com.astarworks.astera.core.application.port.outbound.perviewer.npc.IPerViewerNpcStateGateway]
 *  - `"block"` — sweep on [com.astarworks.astera.core.application.port.outbound.perviewer.block.IPerViewerBlockStateGateway]
 *  - `"gui"` — sweep on [com.astarworks.astera.core.application.port.outbound.perviewer.gui.IPerViewerGuiStateGateway]
 *
 * One event per (gateway-family × viewer) sweep — the auto-clear on
 * `PlayerQuitServer` thus emits up to 3 events (one per family), one per
 * gateway that actually held state for that viewer. Gateways that held no
 * state for the viewer do not emit.
 *
 * Subscribers: replay capture (resync spectator view), audit log writer,
 * HUD reset, mini-map refresh.
 *
 * # Why a string family discriminator?
 *
 * Sealed-class-per-family would push every subscriber to either ignore
 * unrelated variants or import every family. The cleared-event payload is
 * deliberately small (viewer + reason + family) so the cost of a string
 * compare is negligible; new families added in future per-viewer waves
 * (Wave 5gui-depth tooltip overrides, e.g.) simply mint a new constant.
 */
public data class PerViewerOverridesCleared(
    val viewer: PlayerId,
    val gatewayFamily: String,
    val reason: ClearReason,
    val clearedCount: Int,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    init {
        require(gatewayFamily in KNOWN_FAMILIES) {
            "PerViewerOverridesCleared.gatewayFamily must be one of $KNOWN_FAMILIES, got '$gatewayFamily'"
        }
        require(clearedCount >= 1) {
            "PerViewerOverridesCleared.clearedCount must be >= 1 (gateways that held no state must not emit), got $clearedCount"
        }
    }

    public companion object {
        public const val FAMILY_NPC: String = "npc"
        public const val FAMILY_BLOCK: String = "block"
        public const val FAMILY_GUI: String = "gui"

        public val KNOWN_FAMILIES: Set<String> = setOf(FAMILY_NPC, FAMILY_BLOCK, FAMILY_GUI)
    }
}
