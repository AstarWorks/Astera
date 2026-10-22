package com.astarworks.astera.core.domain.gui

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * A localised text line with placeholder arguments, used by [GuiSlot.Static]
 * to override an icon's display name / append lore lines for **dynamic**
 * content (a match row, an online player, a queue lane) where a bare
 * [MessageKey] cannot carry the numbers.
 *
 * Pure value: rendering happens in the adapter through `IMessageRenderer`
 * (ADR-0021 — the GUI domain holds no behaviour). Placeholders use the
 * renderer's `{name}` syntax.
 */
public data class GuiText(
    public val key: MessageKey,
    public val args: Map<String, String> = emptyMap(),
)
