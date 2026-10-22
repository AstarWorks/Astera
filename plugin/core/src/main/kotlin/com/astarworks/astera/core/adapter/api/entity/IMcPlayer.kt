package com.astarworks.astera.core.adapter.api.entity

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
 */
interface IMcPlayer {
    val id: PlayerId
    val name: String
    val location: Vec3
    val locale: String?

    fun sendMessage(rendered: String)

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
