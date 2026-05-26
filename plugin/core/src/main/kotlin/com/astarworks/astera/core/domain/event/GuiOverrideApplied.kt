package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.perviewer.GuiHandle
import java.time.Instant

/**
 * Published when a per-viewer GUI override is staged on
 * [com.astarworks.astera.core.application.port.outbound.perviewer.gui.IPerViewerGuiStateGateway]
 * (Wave 5y-ext, ADR-0112).
 *
 * Two override flavours collapse into one event with mutually-exclusive
 * payload fields (mirroring [BlockOverrideApplied] discipline):
 *
 *  - [slotOverride] non-null — a single slot was overridden for the viewer
 *  - [titleOverride] non-null — the GUI title was overridden
 *
 * Exactly one of the two must be present per emission. The init block
 * enforces this so subscribers can `when` over a single dimension.
 *
 * `viewer` is encoded inside [handle] (see [GuiHandle.viewer]) — keeping it
 * out of the top-level payload prevents subscribers from drifting to "what
 * if the viewer differs from the handle?", which the domain forbids.
 */
public data class GuiOverrideApplied(
    val handle: GuiHandle,
    val slotIndex: SlotIndex? = null,
    val slotOverride: GuiSlot? = null,
    val titleOverride: MessageKey? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    init {
        val isSlotOverride = slotOverride != null
        val isTitleOverride = titleOverride != null
        require(isSlotOverride.xor(isTitleOverride)) {
            "GuiOverrideApplied requires exactly one of slotOverride / titleOverride per emission."
        }
        if (isSlotOverride) {
            require(slotIndex != null) {
                "GuiOverrideApplied.slotOverride requires a non-null slotIndex."
            }
        } else {
            require(slotIndex == null) {
                "GuiOverrideApplied.titleOverride must not carry a slotIndex."
            }
        }
    }
}
