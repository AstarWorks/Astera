package com.astarworks.astera.core.domain.perviewer

/**
 * Why a per-viewer override sweep was triggered (Wave 5y-ext, ADR-0112).
 *
 * Sealed (not enum) so future causes can carry payloads — e.g. a future
 * `MatchEnded(matchId)` clearer could attach the match id for audit. Each
 * variant feeds [com.astarworks.astera.core.domain.event.PerViewerOverridesCleared.reason].
 *
 * # Current variants
 *
 *  - [PlayerQuit] — viewer left the server; subscriber-of-`PlayerQuitServer`
 *    flow inside each in-memory gateway clears every override for the
 *    leaving player to avoid stale state on rejoin / handle leak.
 *  - [Explicit] — application use case called `clearAllOverridesForViewer`
 *    directly (e.g. tutorial end / ability cancel).
 *  - [TargetDespawned] — NPC despawn / block break / GUI close swept every
 *    viewer's override of that target (block-level only — NPC despawn is
 *    handled by [com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner.despawn]
 *    cascade; GUI close is per-viewer not per-target).
 */
public sealed interface ClearReason {

    public data object PlayerQuit : ClearReason

    public data object Explicit : ClearReason

    public data object TargetDespawned : ClearReason
}
