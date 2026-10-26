package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.guidepth.AnimationHandle
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Cross-plugin event (Wave 5gui-depth, ADR-0114): a slot animation has just
 * begun.
 *
 * Fired by
 * [com.astarworks.astera.core.application.port.outbound.guidepth.IGuiAnimationController.animateSlot]
 * immediately after the in-memory state records the handle.
 *
 * @property playerId the player whose GUI is animating.
 * @property guiId the GUI screen carrying the animation.
 * @property slotIndex which slot is animating.
 * @property handle handle returned by the call site for later cancellation.
 * @property durationTicks total animation length (from the [SlotAnimation]).
 * @property animationKind tag identifying which sealed variant fired —
 *   `"fade" / "slide" / "bounce" / "pulse"` (snake_case discriminator pattern,
 *   matching `IslandUnloaded.reasonKind`).
 */
public data class SlotAnimationStarted(
    val playerId: PlayerId,
    val guiId: GuiId,
    val slotIndex: SlotIndex,
    val handle: AnimationHandle,
    val durationTicks: Long,
    val animationKind: String,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
