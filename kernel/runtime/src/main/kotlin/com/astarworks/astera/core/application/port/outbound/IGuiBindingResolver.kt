package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.gui.ResolvedReactiveSlot
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: resolve a [BindingKey] in a `GuiSlot.Reactive` into a rich
 * [ResolvedReactiveSlot] for a particular viewer.
 *
 * # Wave 5f (RTM #21) scope
 *
 * The return type widens from Wave 4.5's `ItemId?` to [ResolvedReactiveSlot]?
 * so resolvers can express numeric bindings:
 *
 * - `player.loadout.main` / `.sub` / `.move` / `.special` / `.armor` /
 *   `.ultimate` → `ResolvedReactiveSlot(itemId = <loadout item>)` — every
 *   override stays null, so the renderer falls back to the GUI YAML defaults.
 * - `player.coins.<currency>` → `ResolvedReactiveSlot(itemId = "indicator-coin-<currency>",
 *   countOverride = balance, displayOverride = MessageKey("astera.gui.binding.coins.value"))`.
 * - `player.kills` → `ResolvedReactiveSlot(itemId = "indicator-kills", countOverride = kills)`.
 *
 * **All overrides are nullable**: a non-null override replaces the GUI YAML
 * default for that field; a null override means "use whatever the GUI YAML /
 * item template would have rendered statically".
 *
 * Unknown / unavailable bindings return `null` and the renderer falls back to a
 * BARRIER placeholder so a typo in `content/gui/` YAML doesn't kill the whole
 * render. The same null is returned for viewer-relative bindings when no
 * viewer is supplied.
 *
 * Lives in `plugin/core` (cross-plugin classloader rule, ADR-0020).
 */
public interface IGuiBindingResolver {
    /**
     * Resolve [binding] for [viewer]. Returns the [ResolvedReactiveSlot] to
     * render, or `null` if the binding is unknown / unavailable for that
     * viewer. Returning non-null with every override `null` is valid and
     * means "render the item id with GUI-YAML defaults".
     */
    public suspend fun resolve(binding: BindingKey, viewer: PlayerId?): ResolvedReactiveSlot?
}
