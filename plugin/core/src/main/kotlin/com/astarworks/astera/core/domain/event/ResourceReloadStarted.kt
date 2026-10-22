package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published when a reload skill starts its timer; [ResourceReloaded] follows
 * [durationTicks] later (WP14, ADR-0177). Lets the weapon-resource HUD show a
 * reload progress bar instead of a bar that silently jumps to full.
 */
public data class ResourceReloadStarted(
    val playerId: PlayerId,
    val weapon: ItemId,
    val durationTicks: Long,
    val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
