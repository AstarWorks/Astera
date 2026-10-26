package com.astarworks.astera.core.application.gui.hud

import com.astarworks.astera.core.application.port.outbound.ICustomFontRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudGlyph
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor

/**
 * Converts a [HudComponent] / [HudLayout] tree to an Adventure
 * [Component] for a specific viewer (Wave 5j Worker γ, ADR-0097).
 *
 * Lives in the **application** layer — Adventure is a Paper-supplied
 * library but is *not* Bukkit. Konsist's `core application must not import
 * Bukkit family or persistence drivers` rule explicitly allows imports
 * from `net.kyori.adventure.*`; what it forbids is `org.bukkit.*`,
 * `io.papermc.*`, `org.spigotmc.*`, `com.destroystokyo.paper.*`, and
 * `com.mojang.*`.
 *
 * Wave 6b `PaperHudGateway` calls into this renderer per layout:
 *  - [HudLayout.ActionBar]     → `audience.sendActionBar(render(layout.component))`
 *  - [HudLayout.Sidebar]       → Scoreboard objective with one rendered line per [HudComponent]
 *  - [HudLayout.TabList]       → `audience.sendPlayerListHeaderAndFooter(header, footer)`
 *  - [HudLayout.Subtitle]      → `audience.showTitle(Title.title(empty, render(component), times))`
 *
 * # Glyph resolution
 *
 * Glyph references that miss the [ICustomFontRegistry] render as
 * [BARRIER_PLACEHOLDER_COMPONENT] (red `?`) instead of throwing — a missing
 * glyph is operationally a YAML/resource-pack drift that we want surfaced
 * to the player as a visible defect, not as a 500-style crash that loses
 * the entire HUD frame.
 *
 * # Threading
 *
 * Pure function over the inputs; safe to call from any thread.
 */
public class HudRenderer(
    private val messageRenderer: IMessageRenderer,
    private val fontRegistry: ICustomFontRegistry,
) {

    /**
     * Convert [component] to an Adventure [Component] resolved for
     * [viewer]. Pass `null` for [viewer] to resolve with the server's
     * default locale (used for broadcasts that pre-render once for every
     * player).
     */
    public fun render(component: HudComponent, viewer: PlayerId?): Component =
        when (component) {
            is HudComponent.Text -> renderText(component, viewer)
            is HudComponent.Glyph -> renderGlyph(component)
            is HudComponent.ProgressBar -> renderProgressBar(component)
            is HudComponent.Composite -> renderComposite(component, viewer)
            is HudComponent.Spacer -> renderSpacer(component)
            is HudComponent.FontText -> Component.text(component.text).font(ASTERA_FONT_KEY)
            is HudComponent.Rendered -> net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(component.miniMessage)
        }

    /**
     * Convenience: extract the primary [HudComponent] from [layout] and
     * render it. [HudLayout.Sidebar] (multi-line) and [HudLayout.TabList]
     * (header + footer) don't reduce to one component — for those the
     * Wave 6b adapter calls [render] per line / per header-footer slot
     * directly. This helper returns [Component.empty] for those layouts
     * so the call site can keep a uniform code path.
     */
    public fun renderLayout(layout: HudLayout, viewer: PlayerId?): Component =
        when (layout) {
            is HudLayout.ActionBar -> render(layout.component, viewer)
            is HudLayout.Subtitle -> render(layout.component, viewer)
            is HudLayout.Sidebar,
            is HudLayout.TabList,
            is HudLayout.StatusLine, // composed into the action bar by the gateway, not rendered alone
            -> Component.empty()
        }

    // ─── Per-variant renderers ─────────────────────────────────────────

    private fun renderText(text: HudComponent.Text, viewer: PlayerId?): Component {
        val raw = messageRenderer.render(viewer, text.messageKey, text.placeholders)
        val base = Component.text(raw)
        return text.color?.let { base.color(it.toAdventure()) } ?: base
    }

    private fun renderGlyph(glyph: HudComponent.Glyph): Component {
        val resolved: HudGlyph = fontRegistry.lookup(glyph.textureRef)
            ?: return BARRIER_PLACEHOLDER_COMPONENT
        return Component.text(resolved.unicodeChar.toString())
            .font(ASTERA_FONT_KEY)
    }

    private fun renderProgressBar(bar: HudComponent.ProgressBar): Component {
        val fillCount = (bar.percent * bar.widthSegments).toInt().coerceIn(0, bar.widthSegments)
        val emptyCount = bar.widthSegments - fillCount
        val fillGlyph = fontRegistry.lookup(bar.fillGlyph)
        val emptyGlyph = fontRegistry.lookup(bar.emptyGlyph)
        if (fillGlyph == null || emptyGlyph == null) {
            // Either side missing → degrade to a single placeholder so the
            // bar doesn't half-render with garbled glyphs.
            return BARRIER_PLACEHOLDER_COMPONENT
        }
        val fillPart = Component.text(fillGlyph.unicodeChar.toString().repeat(fillCount))
            .font(ASTERA_FONT_KEY)
        val emptyPart = Component.text(emptyGlyph.unicodeChar.toString().repeat(emptyCount))
            .font(ASTERA_FONT_KEY)
        return Component.empty().append(fillPart).append(emptyPart)
    }

    private fun renderComposite(composite: HudComponent.Composite, viewer: PlayerId?): Component {
        if (composite.parts.isEmpty()) return Component.empty()
        val spacer = if (composite.spacing > 0) {
            renderSpacer(HudComponent.Spacer(composite.spacing))
        } else {
            null
        }
        var acc: Component = Component.empty()
        composite.parts.forEachIndexed { index, part ->
            if (index > 0 && spacer != null) acc = acc.append(spacer)
            acc = acc.append(render(part, viewer))
        }
        return acc
    }

    /**
     * Spacer rendering strategy:
     *
     * 1. If a glyph named `space/<widthPx>` is registered, use it
     *    (designer can opt into a precise negative-space font glyph).
     * 2. Else fall back to plain ASCII spaces, one per ~`SPACE_GLYPH_PX`
     *    pixels of requested width.
     *
     * Negative-space font glyphs (the canonical Minecraft technique) are
     * a Wave 6b texture-gen sub-pipeline; this fallback keeps the
     * renderer functional in tests + Phase 1 PoC without one.
     */
    private fun renderSpacer(spacer: HudComponent.Spacer): Component {
        if (spacer.widthPx <= 0) return Component.empty()
        val ref = spacerRefFor(spacer.widthPx)
        val glyph = fontRegistry.lookup(ref)
        if (glyph != null) {
            return Component.text(glyph.unicodeChar.toString()).font(ASTERA_FONT_KEY)
        }
        val spaces = ((spacer.widthPx + SPACE_GLYPH_PX - 1) / SPACE_GLYPH_PX)
            .coerceAtLeast(1)
        return Component.text(" ".repeat(spaces))
    }

    public companion object {
        /**
         * Adventure font key for Astera's custom glyph font. Wave 6b
         * `PaperHudGateway` ships a resource pack with
         * `assets/astera/font/default.json` keyed by this namespace+path.
         */
        public val ASTERA_FONT_KEY: Key = Key.key("astera", "default")

        /**
         * Visible "missing glyph" component. Red `?` in vanilla font so
         * it doesn't depend on the custom font being applied (the player
         * may have declined the resource pack).
         */
        public val BARRIER_PLACEHOLDER_COMPONENT: Component =
            Component.text("?").color(NamedTextColor.RED)

        /**
         * Approximate pixel width of one ASCII space in the vanilla
         * Minecraft font (4px glyph + 1px advance). Used by the spacer
         * fallback when no negative-space glyph is registered.
         */
        private const val SPACE_GLYPH_PX: Int = 4

        /**
         * Convention for spacer glyph refs: a designer registering a
         * negative-space glyph for a precise pixel offset names it
         * `space/<widthPx>` (e.g. `space/2`, `space/8`). The renderer
         * looks this up before falling back to ASCII spaces.
         */
        internal fun spacerRefFor(widthPx: Int): HudGlyphRef =
            HudGlyphRef("space/$widthPx")
    }
}

/**
 * Map [HudColor] (vendor-neutral RGBA) onto Adventure's [TextColor]. Alpha
 * is dropped — Adventure's text color model is RGB only; HUDs that need
 * translucency must use a glyph with baked transparency.
 */
internal fun HudColor.toAdventure(): TextColor = TextColor.color(redInt, greenInt, blueInt)
