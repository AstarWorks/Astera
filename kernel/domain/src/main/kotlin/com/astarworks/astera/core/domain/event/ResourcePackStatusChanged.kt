package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.hud.ResourcePackStatus
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * The client reported what became of the resource pack Astera pushed
 * (ADR-0182): translated from Bukkit's `PlayerResourcePackStatusEvent` by
 * `BukkitEventAdapter`. The status HUD switches between the glyph bar and
 * the plain-text fallback on it.
 */
public data class ResourcePackStatusChanged(
    val playerId: PlayerId,
    val status: ResourcePackStatus,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
