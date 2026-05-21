package com.astarworks.astera.core.domain.packet

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Vendor-neutral packet observation envelope (Wave 5y, ADR-0095).
 *
 * Designed for the **type-erasure boundary** between `core.domain` and the
 * Wave 6b PacketEvents adapter. The adapter unpacks a `PacketWrapper<*>` into
 * a `Map<String, Any>` of the fields the listener cares about (position,
 * yaw / pitch, entity id, message component, …) before invoking the listener.
 *
 * **Why a map instead of strong types?** The user-facing API design alternative
 * was a sealed `PacketEnvelope` hierarchy mirroring `PacketKind`. That ties
 * core.domain to PacketEvents-specific shapes (every new MC version reshuffles
 * field names) and would either force domain to expose `Optional<...>` for
 * every PE-version-conditional field, or force an Astera-internal mirror that
 * dedupes nothing. Phase 1 wants the boundary loose; Phase 5+ (after the
 * PacketEvents shape stabilises across two MC bumps) can revisit with strong
 * sub-types per [PacketKind] — that revision lives behind a new ADR.
 *
 * **Conventions for [payloadView].** Wave 6b's adapter populates:
 *  - `MovementClientbound` / `MovementServerbound`: `x`, `y`, `z` (Double),
 *    optional `yaw`, `pitch` (Float), `onGround` (Boolean).
 *  - `DamageAnimation`: `entityId` (Int) for the Bukkit numeric entity id,
 *    `playerUuid` (String) when the source is a player.
 *  - `ChatClientbound`: `message` (String) plain text, `chatType` (String).
 *  - `EntitySpawn` / `EntityDestroy`: `entityId` (Int),
 *    `entityUuid` (String) when known.
 *
 * Tests assert these key names — the adapter is the only legal source of
 * truth, but the conventions live here so application-layer listeners can
 * key off them without a PacketEvents import.
 *
 * @param atTick The server tick at which the packet was observed
 *               (`IScheduler.currentTick()`), so listeners can correlate
 *               with [com.astarworks.astera.core.domain.event.DomainEvent.occurredAt].
 */
public data class PacketEnvelope(
    val kind: PacketKind,
    val playerId: PlayerId,
    val payloadView: Map<String, Any>,
    val atTick: Long,
)
