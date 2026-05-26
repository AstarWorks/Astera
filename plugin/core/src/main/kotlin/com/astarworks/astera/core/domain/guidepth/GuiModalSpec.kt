package com.astarworks.astera.core.domain.guidepth

import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Declarative description of a popup / modal / overlay shown over an existing
 * GUI (Wave 5gui-depth, ADR-0114).
 *
 * Unlike [GuiSpec] (which describes a full screen), `GuiModalSpec` describes
 * a transient overlay layered above whatever the player is currently looking
 * at. Five canonical kinds cover the Wave 5flex / Wave 5refine / Wave 5e /
 * Wave 5bet patterns plus a generic escape hatch.
 *
 * # Why callbacks are [GuiIntent] values
 *
 * Same rationale as [GuiSlot] / [GuiIntent]: the modal spec must stay purely
 * declarative so it can be serialised, replayed, and rendered by a non-Paper
 * adapter (Phase 4 web admin). The application layer's `GuiIntentDispatcher`
 * routes the emitted intent to the right use case.
 *
 * Sealed so the adapter's render `when` is exhaustive.
 */
public sealed interface GuiModalSpec {

    /**
     * Two-button confirmation prompt (e.g., "Are you sure you want to spend
     * 1000 stars on this refinement?"). The adapter emits [onConfirm] when
     * the player clicks confirm, [onCancel] when they click cancel or close
     * the overlay.
     */
    public data class Confirmation(
        public val titleKey: MessageKey,
        public val messageKey: MessageKey,
        public val onConfirm: GuiIntent,
        public val onCancel: GuiIntent,
    ) : GuiModalSpec

    /**
     * Auto-dismissing toast notification (e.g., "+1 win!", "Skill unlocked!").
     *
     * @property dismissAfterTicks number of ticks to display before
     *   auto-dismissing. `null` = no auto-dismiss (player must close manually).
     *   `0` = render exactly one tick (degenerate but valid for batched
     *   pre-render scenarios).
     */
    public data class Notification(
        public val titleKey: MessageKey,
        public val messageKey: MessageKey,
        public val dismissAfterTicks: Long? = null,
    ) : GuiModalSpec {
        init {
            dismissAfterTicks?.let {
                require(it >= 0L) {
                    "Notification.dismissAfterTicks must be >= 0 (got $it)"
                }
            }
        }
    }

    /**
     * Wave 5e quickbar popup: a horizontal row of slots the player picks
     * from. The adapter emits [onSelect] with the chosen slot's `slotIndex`
     * under payload key `"slotIndex"`.
     */
    public data class QuickbarSelector(
        public val slots: List<GuiSlot>,
        public val onSelect: GuiIntent,
    ) : GuiModalSpec {
        init {
            require(slots.isNotEmpty()) {
                "QuickbarSelector.slots must not be empty"
            }
            require(slots.size <= MAX_QUICKBAR_SLOTS) {
                "QuickbarSelector.slots size must be <= $MAX_QUICKBAR_SLOTS (got ${slots.size})"
            }
        }

        public companion object {
            /**
             * Upper bound on quickbar slot count. The vanilla hotbar fits 9
             * slots; we cap at twice that to give the adapter room for a
             * two-row popup variant without inviting unbounded growth.
             */
            public const val MAX_QUICKBAR_SLOTS: Int = 18
        }
    }

    /**
     * Hover-anchored tooltip overlay (e.g., refinement tier breakdown shown
     * when the cursor sits over a refined sword).
     */
    public data class TooltipOverlay(
        public val content: TooltipContent,
        public val anchorPos: AnchorPosition,
    ) : GuiModalSpec

    /**
     * Generic overlay carrying a full [GuiSpec]. Escape hatch for cases where
     * the four specialised variants don't fit (e.g., minigame within a
     * modal). The adapter renders [content] using the same engine as the
     * main GUI.
     */
    public data class Custom(public val content: GuiSpec) : GuiModalSpec
}
