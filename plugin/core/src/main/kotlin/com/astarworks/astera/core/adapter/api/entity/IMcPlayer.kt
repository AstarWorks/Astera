package com.astarworks.astera.core.adapter.api.entity

import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Vendor-neutral Minecraft player handle.
 *
 * Implementations live in `adapter-minecraft-impl-*` (Paper, Folia, Spigot,
 * Velocity, ...). The application layer never sees these implementations
 * directly — only the outbound ports defined in `application/port/outbound`.
 *
 * Strings passed to [sendMessage] are already MiniMessage-formatted; the impl
 * is responsible for deserializing to its native Component / chat type.
 *
 * [giveItem] takes an [IMcItemTemplate] resolved from `IItemRegistry`. Display
 * name and lore are pre-rendered per the viewer's locale by the caller, and
 * passed in as overrides so weapon-level i18n keys can take precedence over
 * the item template's defaults (typical: weapon name shows on a sword, not
 * the underlying item name).
 */
interface IMcPlayer {
    val id: PlayerId
    val name: String
    val location: Vec3
    val locale: String?

    fun sendMessage(rendered: String)

    /**
     * Materialize [template] in this player's inventory.
     *
     * @param displayNameOverride MiniMessage-formatted display name. When
     *   non-null, overrides whatever the template would render. Pass `null`
     *   to use the template's own `displayName` MessageKey.
     * @param loreOverride MiniMessage-formatted lore lines. Empty means
     *   "use the template's own lore".
     */
    fun giveItem(template: IMcItemTemplate, displayNameOverride: String?, loreOverride: List<String>)

    /**
     * Material key of the item currently held in the main hand, or null if the
     * hand is empty / holding air. Stage 2 H will replace this with a richer
     * "what weapon is in hand?" lookup that consults a PersistentDataContainer
     * tag written at give-time.
     */
    fun heldItemMaterialKey(): String?
}
