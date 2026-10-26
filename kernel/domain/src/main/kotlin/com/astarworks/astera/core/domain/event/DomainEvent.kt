package com.astarworks.astera.core.domain.event

import java.time.Instant

/**
 * Marker for events produced by domain rules across every Astera plugin.
 *
 * Implementations are immutable value records. Application services publish
 * events through the [com.astarworks.astera.core.application.port.outbound.IBroadcaster]
 * outbound port; in-process subscribers use the
 * [com.astarworks.astera.core.application.port.outbound.IDomainEventBus] port
 * (default impl: `InMemoryDomainEventBus`).
 *
 * ADR-0022: the marker lives in Core so every plugin can publish / subscribe
 * with a shared type. Feature plugins (Weapons / Combat / Match / Stats /
 * Economy) supply their own concrete variants (`WeaponFired`, `DamageDealt`,
 * `MatchStarted`, …). This interface is intentionally **not sealed** —
 * Kotlin's sealed hierarchies are confined to a single module, which would
 * defeat the multi-plugin layout. Cross-plugin exhaustiveness lives in
 * documentation and Konsist tests, not the compiler.
 *
 * Every concrete event MUST declare [occurredAt] and [version] as constructor
 * parameters (with defaults), so the value is fixed at construction time rather
 * than re-evaluated on each read.
 *
 * @property version Wire-shape version. Bump when the event's data class changes
 *   incompatibly (field removed / type changed). Additive fields with defaults
 *   are backward-compatible and do NOT require a bump.
 * @property occurredAt Wall-clock instant at which the event physically occurred.
 *   Distinct from server-tick fields (`atTick`) which measure in-game time.
 */
public interface DomainEvent {
    public val route: EventRoute
    public val version: Int
    public val occurredAt: Instant
}
