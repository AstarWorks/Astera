package com.astarworks.astera.core.application.gui

import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Rich Kotlin DSL extensions for [GuiSpec.Builder] (Wave 5i).
 *
 * The domain-side [GuiSpec.build] companion accepts a `Builder` block but its
 * only primitive is `slot(SlotIndex, GuiSlot)` — every call site has to
 * construct a fully-formed [GuiSlot] in place, which gets noisy as soon as
 * borders, grids, multi-click handlers and tooltips enter the picture.
 *
 * This file lives in `application/` rather than `domain/` because the lambda
 * receiver pattern relies on `Function*` types which are forbidden inside the
 * domain GUI package (Konsist `gui slot variants must not contain lambda
 * fields`). Builders compile to `Function0<Unit>` etc. — perfectly fine in
 * application code, not fine inside domain values.
 *
 * ### Primitives
 *
 * - **Border helpers** ([border] / [borderColumn] / [borderFrame]) — fill
 *   rows, columns, or the inventory perimeter with a [GuiSlot.Border].
 * - **Grid layout** ([repeatGrid]) — drop a list of items into a 9-wide chest
 *   row block starting at any slot index; the body block constructs each
 *   slot via a [GuiStaticSlotBuilder].
 * - **Block-style slot builders** ([static] / [reactive] / [confirmation] /
 *   [textInput] / [empty]) — readable alternative to constructing the full
 *   [GuiSlot] variant inline.
 *
 * ### Why the indirection
 *
 * Slot construction is a 5+ argument call (item id, four click handlers,
 * tooltip lines). Inline construction makes the layout's *shape* invisible —
 * you can't see "row 1 col 4" without parsing a builder argument list. With
 * the DSL the structure reads top-to-bottom:
 *
 * ```
 * GuiSpec.build(GuiId("shop-main"), MessageKey("astera.gui.shop"), GuiSize.CHEST_27) {
 *     borderFrame(itemId = "gui-pane-dark")
 *     repeatGrid(items = offers, startIndex = 10, columns = 7) { offer, _ ->
 *         item(offer.itemId)
 *         onLeftClick("shop.purchase") { "offer-id"(offer.id) }
 *         onRightClick("shop.preview")  { "offer-id"(offer.id) }
 *         tooltip("astera.shop.tooltip.left-buy", "astera.shop.tooltip.right-preview")
 *     }
 * }
 * ```
 *
 * The DSL is **additive** — `Builder.slot(SlotIndex, GuiSlot)` still works
 * and is preferred for one-off literal slots inside tests.
 */

/**
 * DSL receiver marker. Prevents accidental nested scope confusion (e.g. an
 * outer `Builder.tooltip(...)` being called from within a `static { … }`
 * block).
 */
@DslMarker
public annotation class GuiSlotDsl

// ---------------------------------------------------------------------------
// Layout helpers — border / borderColumn / borderFrame
// ---------------------------------------------------------------------------

/**
 * Inventory width in slots. Every supported [GuiSize] is either a multiple of
 * 9 (chest rows) or 5 (hopper). Chest layouts dominate the GUI catalogue, so
 * we expose the helpers as 9-wide and special-case hoppers separately.
 */
private const val CHEST_WIDTH: Int = 9

/**
 * Fill every slot of every row in [rows] with a [GuiSlot.Border] of [itemId].
 *
 * Row indices are zero-based — `rows = 0..0` is the top row, `rows = 2..2` is
 * the third row, `rows = 0..2` covers the first three rows. Out-of-bounds
 * rows raise [IllegalArgumentException] via the underlying
 * [GuiSpec.Builder.slot] bounds check.
 *
 * Hopper layouts only have one 5-wide row; `rows = 0..0` writes 5 borders.
 */
public fun GuiSpec.Builder.border(rows: IntRange, itemId: String) {
    val borderItem = ItemId(itemId)
    val width = rowWidth()
    for (row in rows) {
        val start = row * width
        for (col in 0 until width) {
            slot(SlotIndex(start + col), GuiSlot.Border(borderItem))
        }
    }
}

/**
 * Fill every row's [columns] cells with a [GuiSlot.Border] of [itemId].
 * Useful for vertical separators (`columns = 4..4` for a middle divider) or
 * for the left/right edges of a chest.
 */
public fun GuiSpec.Builder.borderColumn(columns: IntRange, itemId: String) {
    val borderItem = ItemId(itemId)
    val width = rowWidth()
    val rowCount = size.slotCount / width
    for (col in columns) {
        require(col in 0 until width) {
            "borderColumn: column $col out of bounds for $size (width=$width)"
        }
        for (row in 0 until rowCount) {
            slot(SlotIndex(row * width + col), GuiSlot.Border(borderItem))
        }
    }
}

/**
 * Fill the perimeter (top + bottom rows, left + right columns) of the
 * inventory with a [GuiSlot.Border] of [itemId]. The interior is left empty.
 *
 * For single-row layouts (HOPPER_5, CHEST_9) the entire row is the perimeter
 * and the call degenerates to filling every slot.
 */
public fun GuiSpec.Builder.borderFrame(itemId: String) {
    val width = rowWidth()
    val rowCount = size.slotCount / width
    val lastRow = rowCount - 1
    val lastCol = width - 1
    val borderItem = ItemId(itemId)
    for (row in 0..lastRow) {
        for (col in 0..lastCol) {
            val onEdge = row == 0 || row == lastRow || col == 0 || col == lastCol
            if (onEdge) {
                slot(SlotIndex(row * width + col), GuiSlot.Border(borderItem))
            }
        }
    }
}

/**
 * Selects 9 (chest) or 5 (hopper) for layout math. The domain only exposes
 * these two shapes today; adding a new vanilla container would require an
 * extra entry here and in [GuiSize].
 */
private fun GuiSpec.Builder.rowWidth(): Int = when (size) {
    GuiSize.HOPPER_5 -> 5
    GuiSize.CHEST_9,
    GuiSize.CHEST_18,
    GuiSize.CHEST_27,
    GuiSize.CHEST_36,
    GuiSize.CHEST_45,
    GuiSize.CHEST_54,
    -> CHEST_WIDTH
}

// ---------------------------------------------------------------------------
// Grid layout — repeatGrid
// ---------------------------------------------------------------------------

/**
 * Lay out [items] into a 9-wide chest grid starting at [startIndex] and
 * wrapping every [columns] entries to the next row.
 *
 * Mapping (assumes chest layout — hopper layouts are not supported because a
 * "grid" of 5 slots is just a single row, use [border] + slot calls):
 *
 * ```
 * currentIndex = startIndex + (loopIndex / columns) * 9 + (loopIndex % columns)
 * ```
 *
 * Example — 14 offers in a 7-wide grid starting at slot 10:
 *
 * ```
 *   row 1 (slots 10..16): offers 0..6
 *   row 2 (slots 19..25): offers 7..13
 * ```
 *
 * (Slot 17, 18 are the right edge / next-row left edge skipped by the
 * `columns=7` width.)
 *
 * Each iteration receives a fresh [GuiStaticSlotBuilder]; the [block] sets
 * the item id and any click handlers / tooltip. `item(…)` MUST be called or
 * the build raises [IllegalArgumentException].
 *
 * @param items rows to render — empty list is a no-op.
 * @param startIndex slot at which to place the first item. Must be a valid
 *   [SlotIndex] for [size].
 * @param columns how wide each grid row is. Required `>= 1`. Values larger
 *   than [CHEST_WIDTH] are clamped at construction (a column count > 9 would
 *   wrap into the next row anyway, but we surface the bug early).
 */
public fun <T> GuiSpec.Builder.repeatGrid(
    items: List<T>,
    startIndex: Int = 0,
    columns: Int,
    block: GuiStaticSlotBuilder.(item: T, index: Int) -> Unit,
) {
    require(columns >= 1) { "repeatGrid: columns must be >= 1, was $columns" }
    require(columns <= CHEST_WIDTH) {
        "repeatGrid: columns must be <= $CHEST_WIDTH (a chest row), was $columns"
    }
    require(startIndex >= 0) { "repeatGrid: startIndex must be >= 0, was $startIndex" }
    items.forEachIndexed { loopIndex, item ->
        val currentIndex =
            startIndex + (loopIndex / columns) * CHEST_WIDTH + (loopIndex % columns)
        val built = GuiStaticSlotBuilder().apply { block(item, loopIndex) }.build()
        slot(SlotIndex(currentIndex), built)
    }
}

// ---------------------------------------------------------------------------
// Block-style slot constructors — static / reactive / confirmation / textInput / empty
// ---------------------------------------------------------------------------

/** Block-style constructor for a [GuiSlot.Static] at [index]. */
public fun GuiSpec.Builder.static(index: Int, block: GuiStaticSlotBuilder.() -> Unit) {
    val built = GuiStaticSlotBuilder().apply(block).build()
    slot(SlotIndex(index), built)
}

/**
 * Block-style constructor for a [GuiSlot.Reactive] at [index].
 *
 * [binding] is the data source key — typed as `String` for ergonomics; the
 * [BindingKey] validator surfaces malformed keys at the call site.
 */
public fun GuiSpec.Builder.reactive(
    index: Int,
    binding: String,
    block: GuiReactiveSlotBuilder.() -> Unit = {},
) {
    val built = GuiReactiveSlotBuilder(BindingKey(binding)).apply(block).build()
    slot(SlotIndex(index), built)
}

/** Block-style constructor for a [GuiSlot.Confirmation] at [index]. */
public fun GuiSpec.Builder.confirmation(
    index: Int,
    block: GuiConfirmationSlotBuilder.() -> Unit,
) {
    val built = GuiConfirmationSlotBuilder().apply(block).build()
    slot(SlotIndex(index), built)
}

/** Block-style constructor for a [GuiSlot.TextInput] at [index]. */
public fun GuiSpec.Builder.textInput(
    index: Int,
    block: GuiTextInputSlotBuilder.() -> Unit,
) {
    val built = GuiTextInputSlotBuilder().apply(block).build()
    slot(SlotIndex(index), built)
}

/** Place an explicit [GuiSlot.Empty] at [index]. */
public fun GuiSpec.Builder.empty(index: Int) {
    slot(SlotIndex(index), GuiSlot.Empty)
}

// ---------------------------------------------------------------------------
// Per-slot block builders
// ---------------------------------------------------------------------------

/**
 * Builder for [GuiSlot.Static] — collects an item id, optional click handlers
 * for each of the four supported click types, a tooltip line list, and a
 * stack count. `item(…)` is required; everything else is optional.
 */
@GuiSlotDsl
public class GuiStaticSlotBuilder internal constructor() {
    private var _itemId: ItemId? = null
    private var _onLeftClick: GuiIntent? = null
    private var _onRightClick: GuiIntent? = null
    private var _onShiftClick: GuiIntent? = null
    private var _onMiddleClick: GuiIntent? = null
    private val _tooltip: MutableList<MessageKey> = mutableListOf()
    private var _count: Int = 1

    /** Sets the item by string id (validated via [ItemId]). */
    public fun item(id: String) {
        _itemId = ItemId(id)
    }

    /** Sets the item by typed [ItemId] — useful when the caller already has one. */
    public fun item(id: ItemId) {
        _itemId = id
    }

    /** Stack size in `1..64`. */
    public fun count(value: Int) {
        _count = value
    }

    /** Attach a left-click intent with an optional payload block. */
    public fun onLeftClick(intentKey: String, payloadBlock: PayloadBuilder.() -> Unit = {}) {
        _onLeftClick = buildIntent(intentKey, payloadBlock)
    }

    /** Attach a right-click intent (typical use: preview / info). */
    public fun onRightClick(intentKey: String, payloadBlock: PayloadBuilder.() -> Unit = {}) {
        _onRightClick = buildIntent(intentKey, payloadBlock)
    }

    /** Attach a shift+click intent (typical use: bulk action / quick-buy). */
    public fun onShiftClick(intentKey: String, payloadBlock: PayloadBuilder.() -> Unit = {}) {
        _onShiftClick = buildIntent(intentKey, payloadBlock)
    }

    /** Attach a middle-click intent (rarely used; reserve for power users). */
    public fun onMiddleClick(intentKey: String, payloadBlock: PayloadBuilder.() -> Unit = {}) {
        _onMiddleClick = buildIntent(intentKey, payloadBlock)
    }

    /** Append a single tooltip line. */
    public fun tooltip(key: String) {
        _tooltip += MessageKey(key)
    }

    /** Append one or more tooltip lines in order. */
    public fun tooltip(vararg keys: String) {
        _tooltip += keys.map { MessageKey(it) }
    }

    internal fun build(): GuiSlot.Static {
        val id = requireNotNull(_itemId) {
            "GuiStaticSlotBuilder: static slot requires item(...) call"
        }
        return GuiSlot.Static(
            itemId = id,
            onLeftClick = _onLeftClick,
            onRightClick = _onRightClick,
            onShiftClick = _onShiftClick,
            onMiddleClick = _onMiddleClick,
            tooltip = _tooltip.toList(),
            count = _count,
        )
    }
}

/**
 * Builder for [GuiSlot.Reactive]. The [binding] is required at construction
 * (so the receiver always has one); the block only adds optional tooltip
 * lines.
 */
@GuiSlotDsl
public class GuiReactiveSlotBuilder internal constructor(
    private val binding: BindingKey,
) {
    private val _tooltip: MutableList<MessageKey> = mutableListOf()

    /** Append a single tooltip line. */
    public fun tooltip(key: String) {
        _tooltip += MessageKey(key)
    }

    /** Append one or more tooltip lines in order. */
    public fun tooltip(vararg keys: String) {
        _tooltip += keys.map { MessageKey(it) }
    }

    internal fun build(): GuiSlot.Reactive =
        GuiSlot.Reactive(binding = binding, tooltip = _tooltip.toList())
}

/**
 * Builder for [GuiSlot.Confirmation]. Requires every field — confirmation
 * prompts are explicit by design, so the build raises
 * [IllegalArgumentException] if any field is missing.
 */
@GuiSlotDsl
public class GuiConfirmationSlotBuilder internal constructor() {
    private var _promptKey: MessageKey? = null
    private var _confirmItemId: ItemId? = null
    private var _cancelItemId: ItemId? = null
    private var _confirmIntent: GuiIntent? = null
    private var _cancelIntent: GuiIntent? = null
    private val _tooltip: MutableList<MessageKey> = mutableListOf()

    /** i18n key for the prompt text rendered above the confirm/cancel pair. */
    public fun promptKey(key: String) {
        _promptKey = MessageKey(key)
    }

    /** Item shown for the "yes" / confirm side (e.g. green wool). */
    public fun confirmItem(id: String) {
        _confirmItemId = ItemId(id)
    }

    /** Item shown for the "no" / cancel side (e.g. red wool). */
    public fun cancelItem(id: String) {
        _cancelItemId = ItemId(id)
    }

    /** Intent dispatched when the player clicks the confirm item. */
    public fun confirmIntent(intentKey: String, payloadBlock: PayloadBuilder.() -> Unit = {}) {
        _confirmIntent = buildIntent(intentKey, payloadBlock)
    }

    /** Intent dispatched when the player clicks the cancel item. */
    public fun cancelIntent(intentKey: String, payloadBlock: PayloadBuilder.() -> Unit = {}) {
        _cancelIntent = buildIntent(intentKey, payloadBlock)
    }

    /** Append a single tooltip line. */
    public fun tooltip(key: String) {
        _tooltip += MessageKey(key)
    }

    /** Append one or more tooltip lines in order. */
    public fun tooltip(vararg keys: String) {
        _tooltip += keys.map { MessageKey(it) }
    }

    internal fun build(): GuiSlot.Confirmation {
        return GuiSlot.Confirmation(
            promptKey = requireNotNull(_promptKey) {
                "GuiConfirmationSlotBuilder: promptKey(...) is required"
            },
            confirmItemId = requireNotNull(_confirmItemId) {
                "GuiConfirmationSlotBuilder: confirmItem(...) is required"
            },
            cancelItemId = requireNotNull(_cancelItemId) {
                "GuiConfirmationSlotBuilder: cancelItem(...) is required"
            },
            confirmIntent = requireNotNull(_confirmIntent) {
                "GuiConfirmationSlotBuilder: confirmIntent(...) is required"
            },
            cancelIntent = requireNotNull(_cancelIntent) {
                "GuiConfirmationSlotBuilder: cancelIntent(...) is required"
            },
            tooltip = _tooltip.toList(),
        )
    }
}

/**
 * Builder for [GuiSlot.TextInput]. Requires [placeholderKey] and
 * [onSubmitIntent]; [maxLength] defaults to 32 (matches the underlying domain
 * default — change only when a wider field is needed).
 */
@GuiSlotDsl
public class GuiTextInputSlotBuilder internal constructor() {
    private var _placeholderKey: MessageKey? = null
    private var _maxLength: Int = 32
    private var _onSubmitIntent: GuiIntent? = null

    /** i18n key for the placeholder text shown when the field is empty. */
    public fun placeholderKey(key: String) {
        _placeholderKey = MessageKey(key)
    }

    /** Maximum allowed input length in `1..128`. */
    public fun maxLength(value: Int) {
        _maxLength = value
    }

    /**
     * Intent dispatched on submit. The submitted text arrives under payload
     * key `"text"` per [GuiSlot.TextInput].
     */
    public fun onSubmit(intentKey: String, payloadBlock: PayloadBuilder.() -> Unit = {}) {
        _onSubmitIntent = buildIntent(intentKey, payloadBlock)
    }

    internal fun build(): GuiSlot.TextInput {
        return GuiSlot.TextInput(
            placeholderKey = requireNotNull(_placeholderKey) {
                "GuiTextInputSlotBuilder: placeholderKey(...) is required"
            },
            maxLength = _maxLength,
            onSubmitIntent = requireNotNull(_onSubmitIntent) {
                "GuiTextInputSlotBuilder: onSubmit(...) is required"
            },
        )
    }
}

// ---------------------------------------------------------------------------
// PayloadBuilder — shared payload accumulation for every click handler
// ---------------------------------------------------------------------------

/**
 * Block-style payload accumulator for [GuiIntent]. Both call styles are
 * supported:
 *
 * ```
 * onLeftClick("shop.purchase") {
 *     put("offer-id", "iron-sword")     // explicit
 *     "qty"("1")                        // operator shorthand — reads like a literal
 * }
 * ```
 *
 * The operator-shorthand `String.invoke(String)` mirrors Gradle-style DSLs
 * and keeps the payload terse for the common case of small static maps. It's
 * a writer convenience only — the resulting payload is the same flat
 * `Map<String, String>` shape that `GuiIntent` consumes.
 */
@GuiSlotDsl
public class PayloadBuilder internal constructor() {
    private val entries: MutableMap<String, String> = mutableMapOf()

    /** Explicit put for cases where the operator form is awkward. */
    public fun put(key: String, value: String) {
        entries[key] = value
    }

    /** Operator shorthand: `"offer-id"("iron-sword")` is the same as `put("offer-id", "iron-sword")`. */
    public operator fun String.invoke(value: String) {
        entries[this] = value
    }

    internal fun build(): Map<String, String> = entries.toMap()
}

/**
 * Shared helper — every `onXxxClick` family method funnels through here so
 * the [GuiIntent] construction (and its validation regex) lives in one
 * place.
 */
private fun buildIntent(key: String, payloadBlock: PayloadBuilder.() -> Unit): GuiIntent {
    val payload = PayloadBuilder().apply(payloadBlock).build()
    return GuiIntent(key, payload)
}
