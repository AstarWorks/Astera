package com.astarworks.astera.core.application.port.outbound.guidepth

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.guidepth.AnimationHandle
import com.astarworks.astera.core.domain.guidepth.HoverAnimation
import com.astarworks.astera.core.domain.guidepth.SlotAnimation
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: per-slot / per-hover / per-scroll GUI animation
 * (Wave 5gui-depth, ADR-0114).
 *
 * Wave 5gui-depth ships `InMemoryGuiAnimationController` (records the handle,
 * publishes the start event, leaves duration-driven completion to the
 * caller / tests). Wave 6 lands `PaperGuiAnimationController` over Bukkit
 * `EntityMetadata` / armor-stand display entities / scroll-event
 * interception at higher priority.
 *
 * Vendor-neutral by construction: no Bukkit / packet types in the port; the
 * adapter unwraps [SlotAnimation] / [HoverAnimation] variants.
 *
 * # Easing reuse
 *
 * Every method that takes an `easing` arg accepts the Wave 5h [Easing] enum
 * (32 standard curves). This wave does **NOT** introduce a new easing curve.
 *
 * # Concurrency & ownership
 *
 * Each `(player, gui, slot)` triple may carry at most one active animation —
 * starting a second one implicitly cancels the prior (fires
 * `SlotAnimationCancelled(reasonKind = "superseded")`). The adapter / Wave 6
 * Paper impl maintains the same semantic.
 */
public interface IGuiAnimationController {

    /**
     * Begin animating one slot of an open GUI. Returns an opaque handle the
     * caller uses for subsequent cancellation. If the same `(player, gui,
     * slot)` already has an animation, the prior handle is implicitly
     * cancelled with `reasonKind = "superseded"`.
     */
    public fun animateSlot(
        playerId: PlayerId,
        guiId: GuiId,
        slotIndex: SlotIndex,
        animation: SlotAnimation,
    ): Result<AnimationHandle, GuiAnimationError>

    /**
     * Attach a hover-feedback animation to a slot. The animation runs forward
     * when the cursor enters and reverses when it leaves. Returns a handle
     * the caller uses to detach (`cancel(handle)`) when the slot is
     * unbound.
     */
    public fun animateHover(
        playerId: PlayerId,
        guiId: GuiId,
        slotIndex: SlotIndex,
        hoverAnimation: HoverAnimation,
    ): Result<AnimationHandle, GuiAnimationError>

    /**
     * Smoothly scroll a scrollable GUI from [fromScroll] to [toScroll] over
     * [durationTicks] with [easing]. Scroll values are abstract domain
     * units; the adapter maps them to inventory page indices or pixel
     * scroll offsets.
     *
     * Implicitly cancels any prior in-flight scroll for the same `(player,
     * gui)` (the cancel fires `SlotAnimationCancelled` with `slotIndex =
     * SlotIndex(0)` as a placeholder — scroll animations are not tied to a
     * single slot).
     */
    public fun animateScroll(
        playerId: PlayerId,
        guiId: GuiId,
        fromScroll: Int,
        toScroll: Int,
        durationTicks: Long,
        easing: Easing,
    ): Result<AnimationHandle, GuiAnimationError>

    /**
     * Cancel an in-flight animation. Idempotent: cancelling an unknown or
     * already-completed handle returns Success. Fires
     * [com.astarworks.astera.core.domain.event.SlotAnimationCancelled] with
     * `reasonKind = "explicit"` on the first successful cancel.
     */
    public fun cancel(handle: AnimationHandle): Result<Unit, GuiAnimationError>
}

public sealed class GuiAnimationError {
    public data class UnknownPlayer(public val playerId: PlayerId) : GuiAnimationError()
    public data class InvalidDuration(public val detail: String) : GuiAnimationError()
    public data class Platform(public val detail: String) : GuiAnimationError()
}
