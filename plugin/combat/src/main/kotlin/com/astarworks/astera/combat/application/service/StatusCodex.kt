package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.status.StatusEffectId

/**
 * UI-12: "what does this status do?" lookups. The one source of effect text is `astera.statusdesc.<id>`
 * (also used by the item tooltips); names come from the status spec's `display_name_key`. Builds the chat
 * lines for the `/astera status` list and the single-status detail.
 */
public class StatusCodex(
    private val statuses: IStatusEffectRegistry,
    private val messages: IMessageRenderer,
) {

    /** Every status a player can meet: the registered ones (sorted) plus the skill-only immunity / silence. */
    public fun ids(): List<StatusEffectId> =
        (statuses.all().map { it.id } + EXTRA.map(::StatusEffectId)).distinctBy { it.value }.sortedBy { it.value }

    /** Resolves what the player typed (id or localized name, case-insensitive). */
    public fun resolve(token: String, viewer: PlayerId?): StatusEffectId? {
        val t = token.trim().lowercase()
        if (t.isEmpty()) return null
        return ids().firstOrNull { it.value == t } ?: ids().firstOrNull { name(it, viewer).lowercase() == t }
    }

    public fun name(id: StatusEffectId, viewer: PlayerId?): String {
        val key = statuses.find(id)?.displayNameKey ?: MessageKey("astera.status.${id.value}.name")
        return messages.render(viewer, key)
    }

    public fun description(id: StatusEffectId, viewer: PlayerId?): String =
        messages.render(viewer, MessageKey("$STATUS_DESC.${id.value}"))

    /** `/astera status`: every status with its one-line effect, each name clickable for the detail. */
    public fun listLines(viewer: PlayerId?): List<String> =
        listOf(messages.render(viewer, K_LIST_HEADER)) +
            ids().map { messages.render(viewer, K_LIST_LINE, entryPlaceholders(it, viewer)) } +
            messages.render(viewer, K_LIST_FOOTER)

    /** `/astera status <id>`: the full description. */
    public fun detailLines(id: StatusEffectId, viewer: PlayerId?): List<String> =
        listOf(messages.render(viewer, K_DETAIL, entryPlaceholders(id, viewer)))

    private fun entryPlaceholders(id: StatusEffectId, viewer: PlayerId?): Map<String, String> = mapOf(
        "id" to id.value,
        "name" to name(id, viewer),
        "desc" to description(id, viewer),
        // Inside a quoted MiniMessage argument (hover text) a literal apostrophe must be escaped.
        "desc_q" to description(id, viewer).replace("'", "\\'"),
    )

    public companion object {
        private const val STATUS_DESC = "astera.statusdesc"
        private val EXTRA = listOf("immunity", "silence")
        public val K_UNKNOWN: MessageKey = MessageKey("astera.statuscodex.unknown")
        private val K_LIST_HEADER = MessageKey("astera.statuscodex.list.header")
        private val K_LIST_LINE = MessageKey("astera.statuscodex.list.line")
        private val K_LIST_FOOTER = MessageKey("astera.statuscodex.list.footer")
        private val K_DETAIL = MessageKey("astera.statuscodex.detail")
    }
}
