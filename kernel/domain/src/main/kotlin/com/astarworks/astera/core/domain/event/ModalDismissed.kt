package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.guidepth.ModalHandle
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Cross-plugin event (Wave 5gui-depth, ADR-0114): a modal / popup / overlay
 * left a player's screen.
 *
 * Fired from
 * [com.astarworks.astera.core.application.port.outbound.guidepth.IGuiModalGateway.dismissModal]
 * (explicit), `dismissAllForPlayer` (bulk), `Notification.dismissAfterTicks`
 * auto-dismiss (Paper adapter only — in-memory deliberately scheduler-free),
 * or when the player disconnects mid-modal.
 *
 * @property reasonKind snake_case discriminator —
 *   `"explicit" / "bulk_dismiss" / "auto_expired" / "player_quit"` (matching
 *   the `reasonKind` pattern from `CinematicEnded` / `IslandUnloaded`).
 */
public data class ModalDismissed(
    val playerId: PlayerId,
    val handle: ModalHandle,
    val reasonKind: String,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
