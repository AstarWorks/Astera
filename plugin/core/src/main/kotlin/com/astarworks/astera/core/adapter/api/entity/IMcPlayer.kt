package com.astarworks.astera.core.adapter.api.entity

import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
 * [giveItem] takes an [IMcItemTemplate] produced by
 * `ItemSpec.toAdapterTemplate(messages, viewer)` (see
 * [com.astarworks.astera.core.application.service.toAdapterTemplate]). The
 * template already carries pre-rendered MiniMessage display name + lore per
 * the viewer's locale (Wave _items-1 / ADR-0073); the caller may still pass
 * non-null `displayNameOverride` / non-empty `loreOverride` to take
 * precedence (typical: a per-binding weapon-level label overrides the
 * underlying item's default name).
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
     *   non-null, overrides whatever the template carries. Pass `null` to
     *   use the template's own pre-rendered `displayName` (resolved at
     *   conversion time by `ItemSpec.toAdapterTemplate`).
     * @param loreOverride MiniMessage-formatted lore lines. Empty means
     *   "use the template's own pre-rendered lore".
     */
    fun giveItem(template: IMcItemTemplate, displayNameOverride: String?, loreOverride: List<String>)

    /**
     * Material key of the item currently held in the main hand, or null if the
     * hand is empty / holding air. Stage 2 H will replace this with a richer
     * "what weapon is in hand?" lookup that consults a PersistentDataContainer
     * tag written at give-time.
     */
    fun heldItemMaterialKey(): String?

    /**
     * Send a transient action-bar message (the bar just above the hotbar).
     * The text is resolved via [com.astarworks.astera.core.application.port.outbound.IMessageRenderer]
     * at the Paper-impl layer — callers pass the i18n [messageKey] and any
     * placeholders, not pre-rendered text. Idempotent: a new send replaces
     * the previous action-bar text (vanilla Minecraft fades it out after
     * ~2 seconds).
     *
     * Paper impl: Adventure via PaperPlayer (renderer-resolved keys).
     */
    fun sendActionBar(messageKey: MessageKey, placeholders: Map<String, String> = emptyMap())

    /**
     * Show a title (large center text) with optional subtitle and
     * fade-in / stay / fade-out durations.
     *
     * Both [titleKey] and [subtitleKey] (if non-null) are resolved against
     * the i18n catalog at the Paper-impl layer. Vanilla defaults are
     * fadeIn=500ms / stay=3500ms / fadeOut=1000ms.
     *
     * Paper impl: Adventure via PaperPlayer.
     */
    fun sendTitle(
        titleKey: MessageKey,
        subtitleKey: MessageKey? = null,
        fadeIn: Duration = 500.milliseconds,
        stay: Duration = 3500.milliseconds,
        fadeOut: Duration = 1000.milliseconds,
    )
}
