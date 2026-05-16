package com.astarworks.astera.core.domain.event

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
 */
public interface DomainEvent
