package com.astarworks.astera.core.domain.dialog

/**
 * Why an [NpcDialogTree] walk terminated (Wave 5p, ADR-0093).
 *
 * Reported via [com.astarworks.astera.core.domain.event.NpcDialogEnded] so
 * subscribers (analytics, tutorial sequencer, quest gating) can distinguish
 * a player who happily walked through the tree from one who cancelled or
 * was forcibly dropped.
 */
public enum class DialogEndReason {
    /** Reached a terminal [DialogNode.End] naturally. */
    COMPLETED,

    /** Player closed the dialog (escape / right-click out). */
    CANCELLED,

    /** Player disconnected mid-dialog. */
    DISCONNECT,

    /** A condition node's predicate resolved to false in a branch that ended the tree. */
    FALSE_BRANCH,

    /** An admin reload swapped the catalog and the in-flight node disappeared. */
    CATALOG_SWAPPED,
}
