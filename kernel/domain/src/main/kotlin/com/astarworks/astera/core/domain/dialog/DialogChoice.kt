package com.astarworks.astera.core.domain.dialog

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * A single selectable option under a [DialogNode.Choice] (Wave 5p, ADR-0093).
 *
 * The [labelKey] renders through `IMessageRenderer` in the player's locale.
 * Selecting this choice causes the dialog runner to transition to [nextNodeId].
 */
public data class DialogChoice(
    public val labelKey: MessageKey,
    public val nextNodeId: DialogNodeId,
)
