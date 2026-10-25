package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.guidepth.ModalHandle
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Cross-plugin event (Wave 5gui-depth, ADR-0114): a modal / popup / overlay
 * appeared on a player's screen.
 *
 * Fired by
 * [com.astarworks.astera.core.application.port.outbound.guidepth.IGuiModalGateway.showModal]
 * immediately after the modal is registered in the gateway's in-memory state.
 * Pairs with [ModalDismissed].
 *
 * @property modalKind snake_case discriminator —
 *   `"confirmation" / "notification" / "quickbar_selector" / "tooltip_overlay" / "custom"`
 *   (matching the `reasonKind` pattern from `CinematicEnded`).
 */
public data class ModalShown(
    val playerId: PlayerId,
    val handle: ModalHandle,
    val modalKind: String,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
