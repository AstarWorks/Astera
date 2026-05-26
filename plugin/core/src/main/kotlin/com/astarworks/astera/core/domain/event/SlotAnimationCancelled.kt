package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.guidepth.AnimationHandle
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Cross-plugin event (Wave 5gui-depth, ADR-0114): a slot animation was
 * cancelled before its natural completion.
 *
 * Fires from
 * [com.astarworks.astera.core.application.port.outbound.guidepth.IGuiAnimationController.cancel]
 * and also when the controller implicitly cancels an existing handle (e.g.,
 * GUI closed mid-flight). Mutually exclusive with [SlotAnimationCompleted].
 *
 * @property reasonKind snake_case discriminator —
 *   `"explicit" / "gui_closed" / "superseded"` (matching the `reasonKind`
 *   pattern from `CinematicEnded` / `IslandUnloaded`).
 */
public data class SlotAnimationCancelled(
    val playerId: PlayerId,
    val guiId: GuiId,
    val slotIndex: SlotIndex,
    val handle: AnimationHandle,
    val reasonKind: String,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
