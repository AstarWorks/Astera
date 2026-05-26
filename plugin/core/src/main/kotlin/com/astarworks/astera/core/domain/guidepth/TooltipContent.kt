package com.astarworks.astera.core.domain.guidepth

/**
 * Body of a [GuiModalSpec.TooltipOverlay] — an ordered list of [TooltipLine]s
 * (Wave 5gui-depth, ADR-0114).
 *
 * Distinct from the `List<TooltipLine>` produced by `ITooltipRegistry.lookup`
 * (which targets item tooltips) because a tooltip *overlay* is a
 * full-fledged modal payload — wrapping the list in a value type lets the
 * sealed [GuiModalSpec] hierarchy remain a single declarative shape.
 */
public data class TooltipContent(public val lines: List<TooltipLine>) {
    init {
        require(lines.isNotEmpty()) { "TooltipContent.lines must not be empty" }
    }
}
