package com.astarworks.astera.core.application.gui.hud

import com.astarworks.astera.core.domain.hud.BossBarColor
import com.astarworks.astera.core.domain.hud.BossBarStyle
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Kotlin DSL for the [HudLayout] sealed hierarchy (Wave 5j Worker δ, ADR-0097).
 *
 * The domain [HudLayout] / [HudComponent] types are pure data classes with
 * verbose constructors — fine for adapters, painful for hand-written
 * call-sites such as `ManaBarHud` or `StarCounterHud` that compose 5+
 * primitives. This DSL mirrors the `GuiSpecBuilder.kt` (Wave 5i Worker β)
 * and `DialogSpecBuilder.kt` (Wave 5i Worker γ) shape so the whole UI
 * surface — chest GUIs, dialogs, HUDs — reads the same way:
 *
 * ```
 * hudLayout {
 *     bossBar(slot = 1, color = BossBarColor.BLUE, style = BossBarStyle.NOTCHED_10, progress = 0.5f) {
 *         composite(spacing = 2) {
 *             glyph("hud/mana/icon", widthPx = 10)
 *             spacer(2)
 *             text("astera.hud.mana.value")
 *         }
 *     }
 * }
 * ```
 *
 * The DSL is intentionally **layout-singular** — every top-level
 * `hudLayout { … }` block emits exactly one [HudLayout] variant. Mana,
 * stamina, sidebar etc. are separate layouts (separate `HudHandle`s in the
 * gateway), so co-locating them in one DSL block would be a category error.
 * A call that doesn't choose a layout, or chooses two, fails at `build()`.
 *
 * Lives in `application/` rather than `domain/` because the receiver-block
 * pattern compiles to `Function*` types which the domain layer doesn't
 * allow (mirrors `GuiSpecBuilder` for the same reason).
 *
 * **Vendor-neutral by construction**: this file imports zero Adventure /
 * Bukkit / Paper types. The renderer (Worker γ `HudRenderer`) converts the
 * resulting [HudLayout] to Adventure `Component` at the edge.
 */

/**
 * DSL receiver marker. Scopes nested receivers so an inner `composite { … }`
 * block cannot accidentally call the outer `bossBar(…)` / `sidebar(…)`
 * methods. Mirrors `@GuiSlotDsl` / `@DialogDsl` in the sibling builders.
 */
@DslMarker
public annotation class HudDsl

/**
 * Entry point of the [HudLayout] DSL. Construct exactly one layout via the
 * [block] receiver, picking one of [HudLayoutBuilder.actionBar] /
 * [HudLayoutBuilder.bossBar] / [HudLayoutBuilder.sidebar] /
 * [HudLayoutBuilder.tabList] / [HudLayoutBuilder.subtitle].
 *
 * @throws IllegalStateException if the block sets zero layouts or more than one.
 */
public fun hudLayout(block: HudLayoutBuilder.() -> Unit): HudLayout =
    HudLayoutBuilder().apply(block).build()

/**
 * Top-level builder. Each `bossBar(…)`, `actionBar(…)` etc. call sets the
 * single layout slot; a second call within the same block fails at
 * `build()` to surface the mistake at the call site rather than silently
 * discarding the earlier configuration.
 */
@HudDsl
public class HudLayoutBuilder {
    private var layout: HudLayout? = null

    /**
     * Action Bar layout. The single-line MC overlay between hotbar and
     * world; auto-fades on the client side after [ttlTicks] ticks
     * (Adventure default = 40 = 2 s). The renderer re-sends the layout each
     * tick window to keep it visible if the use case wants persistence.
     *
     * @param ttlTicks client-side TTL in ticks (1..400 is a sensible range;
     *   the domain allows wider, this DSL doesn't second-guess it).
     * @param block exactly one component (use `composite { … }` for groups).
     */
    public fun actionBar(ttlTicks: Int = 40, block: HudComponentBuilder.() -> Unit) {
        assignLayout("actionBar") {
            HudLayout.ActionBar(
                component = HudComponentBuilder().apply(block).build(),
                ttlTicks = ttlTicks,
            )
        }
    }

    /**
     * BossBar slot layout. MC stacks up to 4 visible BossBars per player;
     * [slot] picks the vertical position (1 = top, 4 = bottom-most).
     *
     * @param slot 1..4. Out-of-range fails in [HudLayout.BossBarSlot.init].
     * @param color Adventure-mirrored bar color (no Adventure import).
     * @param style Adventure-mirrored notching style.
     * @param progress 0f..1f fill ratio. Out-of-range fails in
     *   [HudLayout.BossBarSlot.init].
     */
    public fun bossBar(
        slot: Int,
        color: BossBarColor,
        style: BossBarStyle,
        progress: Float = 1f,
        block: HudComponentBuilder.() -> Unit,
    ) {
        assignLayout("bossBar") {
            HudLayout.BossBarSlot(
                slot = slot,
                component = HudComponentBuilder().apply(block).build(),
                color = color,
                style = style,
                progress = progress,
            )
        }
    }

    /**
     * Sidebar layout (the right-edge scoreboard column). The [titleKey] is
     * the i18n key for the bolded header row; each `line { … }` inside
     * [block] adds one body row in declaration order (top-to-bottom).
     *
     * Domain requires 1..15 lines — [HudLayout.Sidebar.init] enforces it.
     */
    public fun sidebar(titleKey: String, block: HudSidebarLinesBuilder.() -> Unit) {
        assignLayout("sidebar") {
            HudLayout.Sidebar(
                titleKey = MessageKey(titleKey),
                lines = HudSidebarLinesBuilder().apply(block).build(),
            )
        }
    }

    /**
     * Sidebar layout from a pre-built [MessageKey]. Use this when the title
     * key is composed via [MessageKey.div] elsewhere and the caller doesn't
     * want to drop back to a raw string.
     */
    public fun sidebar(titleKey: MessageKey, block: HudSidebarLinesBuilder.() -> Unit) {
        assignLayout("sidebar") {
            HudLayout.Sidebar(
                titleKey = titleKey,
                lines = HudSidebarLinesBuilder().apply(block).build(),
            )
        }
    }

    /**
     * Tab list header/footer layout. Either or both may be set; both
     * `null` is permitted by the domain but is a no-op in practice (the
     * adapter clears any existing header/footer).
     */
    public fun tabList(block: HudTabListBuilder.() -> Unit) {
        assignLayout("tabList") {
            val (header, footer) = HudTabListBuilder().apply(block).build()
            HudLayout.TabList(header = header, footer = footer)
        }
    }

    /**
     * Subtitle (title + subtitle screen overlay). Default timings match
     * MC's vanilla title default: 10-tick fade in, 60-tick stay, 10-tick
     * fade out (≈ 4 seconds total).
     */
    public fun subtitle(
        fadeIn: Int = 10,
        stay: Int = 60,
        fadeOut: Int = 10,
        block: HudComponentBuilder.() -> Unit,
    ) {
        assignLayout("subtitle") {
            HudLayout.Subtitle(
                component = HudComponentBuilder().apply(block).build(),
                fadeInTicks = fadeIn,
                stayTicks = stay,
                fadeOutTicks = fadeOut,
            )
        }
    }

    internal fun build(): HudLayout = checkNotNull(layout) {
        "hudLayout { ... } requires exactly one of actionBar { } / bossBar(...) { } / " +
            "sidebar(...) { } / tabList { } / subtitle(...) { } to be called."
    }

    /**
     * Assigns the single layout slot, failing fast if the block already
     * declared one. Surfacing the conflict at the call site (rather than
     * silently overwriting) matches the GUI/Dialog DSL behaviour and makes
     * "I forgot I already set X" bugs visible at first run.
     */
    private inline fun assignLayout(callName: String, build: () -> HudLayout) {
        check(layout == null) {
            "hudLayout { ... } already has a layout assigned; `$callName(...)` would replace it. " +
                "Each hudLayout block emits exactly one HudLayout — split into separate blocks for " +
                "multi-region HUDs (each gets its own HudHandle via IHudGateway.showFor)."
        }
        layout = build()
    }
}

/**
 * Component-tree sub-builder, shared by every layout that takes a single
 * component (`actionBar`, `bossBar`, `subtitle`) as well as each line of a
 * `sidebar` / `tabList`.
 *
 * The builder collects primitives in declaration order; `build()` returns:
 *  - the single primitive if exactly one was added,
 *  - a [HudComponent.Composite] wrapping all of them otherwise,
 *  - an error if zero primitives were added.
 *
 * This lets callers write `text("foo")` and get a `Text` directly,
 * vs. wrapping a longer sequence in a `Composite` only when needed. The
 * [HudComponent.Composite] always carries the accumulated [spacing] (which
 * defaults to 0 unless [spacing] is called).
 */
@HudDsl
public class HudComponentBuilder internal constructor() {
    private val parts: MutableList<HudComponent> = mutableListOf()
    private var spacing: Int = 0

    /**
     * Append a plain i18n text primitive. The renderer resolves
     * [messageKey] through `IMessageRenderer` at render time using the
     * player's locale.
     *
     * @param color optional tint override. `null` (default) inherits the
     *   surrounding layout's color (e.g. BossBar's color).
     */
    public fun text(messageKey: String, color: HudColor? = null) {
        parts += HudComponent.Text(MessageKey(messageKey), color)
    }

    /**
     * Append a text primitive from a pre-built [MessageKey]. Same color
     * semantics as the [String] overload.
     */
    public fun text(messageKey: MessageKey, color: HudColor? = null) {
        parts += HudComponent.Text(messageKey, color)
    }

    /**
     * Append a custom-font glyph.
     *
     * @param ref glyph ref (`HudGlyphRef` naming rules — lower-kebab-case
     *   path; e.g. `"hud/mana/icon"`).
     * @param widthPx logical advance width for layout math. The renderer
     *   uses the registered [com.astarworks.astera.core.domain.hud.HudGlyph]'s
     *   width when emitting; this value is for the DSL's reasoning about
     *   spacing.
     * @param ascent vertical offset, mirrors font.json `ascent`.
     */
    public fun glyph(ref: String, widthPx: Int = 8, ascent: Int = 0) {
        parts += HudComponent.Glyph(HudGlyphRef(ref), widthPx, ascent)
    }

    /**
     * Append a horizontal progress bar of [segments] characters filled by
     * [percent] (0..1).
     */
    public fun progressBar(
        percent: Float,
        fillRef: String,
        emptyRef: String,
        segments: Int = 20,
    ) {
        parts += HudComponent.ProgressBar(
            percent = percent,
            fillGlyph = HudGlyphRef(fillRef),
            emptyGlyph = HudGlyphRef(emptyRef),
            widthSegments = segments,
        )
    }

    /**
     * Append a nested composite (rare — usually only needed for two-level
     * grouping like "icon + spacer + (value + suffix)"). Children declared
     * inside the [block] are wrapped in their own [HudComponent.Composite]
     * with [spacing] between them.
     */
    public fun composite(spacing: Int = 0, block: HudComponentBuilder.() -> Unit) {
        val nested = HudComponentBuilder().apply(block).buildList()
        parts += HudComponent.Composite(nested, spacing)
    }

    /** Append a pure-spacing primitive (advance width in MC pixels). */
    public fun spacer(widthPx: Int) {
        parts += HudComponent.Spacer(widthPx)
    }

    /**
     * Override the spacing applied between top-level parts when [build]
     * wraps them in a [HudComponent.Composite]. Has no effect if only one
     * primitive was added (the single primitive is returned as-is).
     */
    public fun spacing(value: Int) {
        spacing = value
    }

    /**
     * Returns the assembled component:
     *  - single primitive if only one was declared,
     *  - [HudComponent.Composite] wrapping the list otherwise.
     *
     * @throws IllegalStateException if no primitive was declared.
     */
    internal fun build(): HudComponent = when (parts.size) {
        0 -> error(
            "HudComponentBuilder: at least one of text(...) / glyph(...) / " +
                "progressBar(...) / composite { ... } / spacer(...) is required.",
        )
        1 -> parts.first()
        else -> HudComponent.Composite(parts.toList(), spacing)
    }

    /**
     * Returns the raw children list — used by [composite] when nesting so
     * the outer block doesn't auto-wrap an already-wrapped list.
     */
    internal fun buildList(): List<HudComponent> {
        check(parts.isNotEmpty()) {
            "HudComponentBuilder.composite { ... } requires at least one child primitive."
        }
        return parts.toList()
    }
}

/**
 * Sidebar lines sub-builder. Each `line { … }` call adds one [HudComponent]
 * line in declaration order (top-to-bottom). The wrapping [HudLayout.Sidebar]
 * enforces 1..15 lines in its `init`.
 */
@HudDsl
public class HudSidebarLinesBuilder internal constructor() {
    private val lines: MutableList<HudComponent> = mutableListOf()

    /** Add one sidebar line built via the standard [HudComponentBuilder]. */
    public fun line(block: HudComponentBuilder.() -> Unit) {
        lines += HudComponentBuilder().apply(block).build()
    }

    internal fun build(): List<HudComponent> = lines.toList()
}

/**
 * Tab-list header/footer sub-builder. Either or both may be set; calling
 * neither produces a `TabList(null, null)` — legal but a no-op render.
 */
@HudDsl
public class HudTabListBuilder internal constructor() {
    private var header: HudComponent? = null
    private var footer: HudComponent? = null

    /** Set the tab-list header. */
    public fun header(block: HudComponentBuilder.() -> Unit) {
        header = HudComponentBuilder().apply(block).build()
    }

    /** Set the tab-list footer. */
    public fun footer(block: HudComponentBuilder.() -> Unit) {
        footer = HudComponentBuilder().apply(block).build()
    }

    internal fun build(): Pair<HudComponent?, HudComponent?> = header to footer
}
