package com.astarworks.astera.core.adapter.paper.cosmetic

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Equipped title and name colour per online player, filled by [PaperCosmeticDisplay] on its entity-scoped
 * refreshes. Chat formatting runs synchronously on an async chat thread and must not suspend, so it reads this.
 */
public class ChatCosmeticCache {
    public data class Entry(val title: Component, val nameColor: TextColor?)

    private val entries = ConcurrentHashMap<UUID, Entry>()

    public fun get(id: UUID): Entry? = entries[id]
    public fun put(id: UUID, entry: Entry) { entries[id] = entry }
    public fun remove(id: UUID) { entries.remove(id) }
}
