package com.astarworks.astera.weapons.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.LoadoutSlot
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Emitted by `LoadoutSaveUseCase` when a player's [LoadoutSlot] is assigned a
 * new [ItemId] (or cleared via `itemId = null`).
 *
 * Wave 4.5 (R3.5) / ADR-0027: event lives in the Weapons plugin's
 * `domain/event/` package per the ADR-0022 Phase 3 convention — feature
 * plugins own the events they publish. The payload types ([PlayerId],
 * [LoadoutSlot], [ItemId]) live in `plugin/core` so cross-plugin subscribers
 * (e.g. a future `:plugin:stats` listener that records "outfit changed" or a
 * `:plugin:economy` shop-purchase reconciler) get the same `KClass` identity
 * Astera-Core publishes — silly-knuth §5.
 *
 * Wave 5+ scope: skill plugins may subscribe to recompute passive effects
 * when MOVE / SPECIAL / ULTIMATE slots change. The match-apply listener uses
 * the [LoadoutSaveUseCase] write side only — it does not subscribe to this
 * event itself.
 */
public data class LoadoutChanged(
    val playerId: PlayerId,
    val slot: LoadoutSlot,
    /** New occupant of [slot]; `null` means the slot was cleared. */
    val itemId: ItemId?,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
