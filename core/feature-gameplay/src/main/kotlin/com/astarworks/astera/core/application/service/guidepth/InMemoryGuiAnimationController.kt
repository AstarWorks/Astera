package com.astarworks.astera.core.application.service.guidepth

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.guidepth.GuiAnimationError
import com.astarworks.astera.core.application.port.outbound.guidepth.IGuiAnimationController
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.event.SlotAnimationCancelled
import com.astarworks.astera.core.domain.event.SlotAnimationCompleted
import com.astarworks.astera.core.domain.event.SlotAnimationStarted
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.guidepth.AnimationHandle
import com.astarworks.astera.core.domain.guidepth.HoverAnimation
import com.astarworks.astera.core.domain.guidepth.SlotAnimation
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [IGuiAnimationController] (Wave 5gui-depth, ADR-0114).
 *
 * Wired by `AsteraCorePlugin` at priority 0 — Wave 6 lands
 * `PaperGuiAnimationController` over Bukkit `EntityMetadata` / armor-stand
 * display entities at higher priority and shadows this impl.
 *
 * **Not thread-safe** beyond what [ConcurrentHashMap] provides. The Paper
 * adapter pipeline runs on the main thread; the concurrent map is
 * precautionary for cross-thread packet handlers in Wave 6.
 *
 * # Scheduler-free by design
 *
 * The in-memory controller does **not** schedule duration-driven completion
 * (no `IScheduler` dependency — mirrors the Wave 5cinematic
 * `InMemoryCameraGateway.freeze` decision to avoid task leaks on gateway
 * replace). Tests that need to assert completion call
 * [simulateComplete] to fire [SlotAnimationCompleted] manually. The Paper
 * adapter (Wave 6) wires `IScheduler.run(durationTicks) {
 * fireCompleted(handle) }`.
 */
public class InMemoryGuiAnimationController(
    private val eventBus: IDomainEventBus,
    private val tickClock: () -> Long = { 0L },
) : IGuiAnimationController {

    private data class ActiveAnimation(
        val playerId: PlayerId,
        val guiId: GuiId,
        val slotIndex: SlotIndex,
    )

    private val active: ConcurrentHashMap<AnimationHandle, ActiveAnimation> = ConcurrentHashMap()
    private val slotIndex: ConcurrentHashMap<ActiveAnimation, AnimationHandle> = ConcurrentHashMap()

    override fun animateSlot(
        playerId: PlayerId,
        guiId: GuiId,
        slotIndex: SlotIndex,
        animation: SlotAnimation,
    ): Result<AnimationHandle, GuiAnimationError> {
        val key = ActiveAnimation(playerId, guiId, slotIndex)
        cancelSlotForKey(key, reasonKind = "superseded")
        val handle = mintHandle()
        active[handle] = key
        this.slotIndex[key] = handle
        eventBus.publish(
            SlotAnimationStarted(
                playerId = playerId,
                guiId = guiId,
                slotIndex = slotIndex,
                handle = handle,
                durationTicks = animation.durationTicks,
                animationKind = kindOf(animation),
                atTick = tickClock(),
            ),
        )
        return Result.success(handle)
    }

    override fun animateHover(
        playerId: PlayerId,
        guiId: GuiId,
        slotIndex: SlotIndex,
        hoverAnimation: HoverAnimation,
    ): Result<AnimationHandle, GuiAnimationError> {
        // Hover animations share the per-(player, gui, slot) ownership slot —
        // installing a hover replaces any prior slot or hover animation on
        // the same triple. This keeps the state model simple and matches
        // the visual reality that you can't have two simultaneous renders
        // on one slot.
        val key = ActiveAnimation(playerId, guiId, slotIndex)
        cancelSlotForKey(key, reasonKind = "superseded")
        val handle = mintHandle()
        active[handle] = key
        this.slotIndex[key] = handle
        eventBus.publish(
            SlotAnimationStarted(
                playerId = playerId,
                guiId = guiId,
                slotIndex = slotIndex,
                handle = handle,
                durationTicks = hoverAnimation.durationTicks,
                animationKind = "hover",
                atTick = tickClock(),
            ),
        )
        return Result.success(handle)
    }

    override fun animateScroll(
        playerId: PlayerId,
        guiId: GuiId,
        fromScroll: Int,
        toScroll: Int,
        durationTicks: Long,
        easing: Easing,
    ): Result<AnimationHandle, GuiAnimationError> {
        if (durationTicks < 0L) {
            return Result.failure(
                GuiAnimationError.InvalidDuration(
                    "animateScroll.durationTicks must be >= 0 (got $durationTicks)",
                ),
            )
        }
        // Scroll animations use a sentinel SlotIndex(0) — they are bound to
        // the GUI as a whole, not to a specific slot. Implicit cancel of any
        // prior scroll on the same (player, gui) uses the same sentinel.
        val key = ActiveAnimation(playerId, guiId, SCROLL_SENTINEL)
        cancelSlotForKey(key, reasonKind = "superseded")
        val handle = mintHandle()
        active[handle] = key
        slotIndex[key] = handle
        eventBus.publish(
            SlotAnimationStarted(
                playerId = playerId,
                guiId = guiId,
                slotIndex = SCROLL_SENTINEL,
                handle = handle,
                durationTicks = durationTicks,
                animationKind = "scroll",
                atTick = tickClock(),
            ),
        )
        return Result.success(handle)
    }

    override fun cancel(handle: AnimationHandle): Result<Unit, GuiAnimationError> {
        val key = active.remove(handle) ?: return Result.success(Unit)
        slotIndex.remove(key, handle)
        eventBus.publish(
            SlotAnimationCancelled(
                playerId = key.playerId,
                guiId = key.guiId,
                slotIndex = key.slotIndex,
                handle = handle,
                reasonKind = "explicit",
                atTick = tickClock(),
            ),
        )
        return Result.success(Unit)
    }

    /**
     * Test helper — simulate the natural completion of [handle] (the in-memory
     * impl does not run a scheduler; the Paper adapter does). Fires
     * [SlotAnimationCompleted] and removes the handle from the active set.
     * Idempotent: completing an unknown / already-completed handle is a no-op.
     */
    public fun simulateComplete(handle: AnimationHandle) {
        val key = active.remove(handle) ?: return
        slotIndex.remove(key, handle)
        eventBus.publish(
            SlotAnimationCompleted(
                playerId = key.playerId,
                guiId = key.guiId,
                slotIndex = key.slotIndex,
                handle = handle,
                atTick = tickClock(),
            ),
        )
    }

    /** Number of active animations (test helper). */
    public fun activeCount(): Int = active.size

    private fun cancelSlotForKey(key: ActiveAnimation, reasonKind: String) {
        val prior = slotIndex.remove(key) ?: return
        active.remove(prior)
        eventBus.publish(
            SlotAnimationCancelled(
                playerId = key.playerId,
                guiId = key.guiId,
                slotIndex = key.slotIndex,
                handle = prior,
                reasonKind = reasonKind,
                atTick = tickClock(),
            ),
        )
    }

    private fun kindOf(animation: SlotAnimation): String = when (animation) {
        is SlotAnimation.Fade -> "fade"
        is SlotAnimation.Slide -> "slide"
        is SlotAnimation.Bounce -> "bounce"
        is SlotAnimation.Pulse -> "pulse"
    }

    private fun mintHandle(): AnimationHandle = AnimationHandle(UUID.randomUUID().toString())

    private companion object {
        /** Sentinel slot index for scroll animations (which are GUI-wide, not slot-bound). */
        val SCROLL_SENTINEL = SlotIndex(0)
    }
}
