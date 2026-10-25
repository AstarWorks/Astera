package com.astarworks.astera.core.domain.dialog

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * SKELETON — Wave 5i Worker α extends.
 *
 * Body element shown above the input form (Paper `DialogBase.body`).
 * Sealed so the Wave 6b adapter's `when` is exhaustive.
 */
public sealed interface DialogBody {
    /** Plain message line (rendered via MiniMessage). */
    public data class PlainText(public val messageKey: MessageKey) : DialogBody

    /** Item preview (icon + tooltip). Wave 6b adapter resolves via IItemRegistry. */
    public data class ItemPreview(public val itemId: ItemId) : DialogBody
}
