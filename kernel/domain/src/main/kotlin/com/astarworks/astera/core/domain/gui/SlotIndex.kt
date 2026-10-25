package com.astarworks.astera.core.domain.gui

/**
 * Zero-based index of a slot inside a [GuiSpec].
 *
 * Bounds depend on the owning [GuiSize] — a `CHEST_27` GUI accepts indices
 * `0..26`. [SlotIndex] itself only enforces non-negative; the upper bound is
 * checked by [GuiSpec] at construction so the error message can include the
 * GUI size in context.
 */
@JvmInline
public value class SlotIndex(public val value: Int) {
    init {
        require(value >= 0) { "SlotIndex must be >= 0: $value" }
    }

    override fun toString(): String = value.toString()
}
