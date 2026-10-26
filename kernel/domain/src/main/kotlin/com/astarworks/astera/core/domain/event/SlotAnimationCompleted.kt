package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.guidepth.AnimationHandle
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Cross-plugin event (Wave 5gui-depth, ADR-0114): a slot animation finished
 * its natural duration.
 *
 * Paired with [SlotAnimationStarted]. For early cancellation, the bus emits
 * [SlotAnimationCancelled] instead — `Completed` and `Cancelled` are mutually
 * exclusive for a given handle.
 *
 * In-memory note: the default `InMemoryGuiAnimationController` does not run
 * a scheduler — the Paper adapter (Wave 6) fires this event from
 * `IScheduler.run(durationTicks)`. Tests that need to assert completion
 * call `simulateComplete(handle)` on the in-memory impl.
 */
public data class SlotAnimationCompleted(
    val playerId: PlayerId,
    val guiId: GuiId,
    val slotIndex: SlotIndex,
    val handle: AnimationHandle,
    val atTick: Long = 0L,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
