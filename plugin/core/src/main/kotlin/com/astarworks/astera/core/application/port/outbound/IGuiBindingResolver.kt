package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: resolve a [BindingKey] in a `GuiSlot.Reactive` to a live
 * [ItemId] for a particular viewer.
 *
 * Wave 4.5 (R3.5, ADR-0027) scope: **ItemId-valued bindings only**.
 * Supported binding keys (resolved by `DefaultGuiBindingResolver`):
 *
 * - `player.loadout.main` / `.sub` / `.move` / `.special` / `.armor` /
 *   `.ultimate` → ItemId in that loadout slot (or null if empty)
 *
 * Number-valued bindings (`player.coins.star`, `player.kills`, etc.) are
 * intentionally out of Wave 4.5 scope — they need a richer return type
 * (`ResolvedReactiveSlot` with displayOverride + loreOverride) which Wave 5+
 * will introduce. Unknown bindings return null and the renderer falls back to
 * a BARRIER placeholder so a typo doesn't kill the whole GUI render.
 *
 * Lives in `plugin/core` (cross-plugin classloader rule, ADR-0020).
 */
public interface IGuiBindingResolver {
    /**
     * Resolve [binding] for [viewer]. Returns the live `ItemId` to render,
     * or `null` if the binding is unknown or unavailable for that viewer.
     */
    public fun resolve(binding: BindingKey, viewer: PlayerId?): ItemId?
}
