package com.astarworks.astera.core.application.port.outbound.visibility

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.visibility.VisibilityError

/**
 * Outbound port — per-viewer entity and player visibility (Wave 5y, ADR-0095).
 *
 * Where [com.astarworks.astera.core.application.port.outbound.IPlayerGateway]
 * answers "is this player online?" globally, this gateway answers "is this
 * entity visible *to that specific viewer*?". The asymmetry matters in PvP
 * (vanish admin), spectator (one-way friend tracking), match (hide enemy
 * scout from one team only) and stage-geometry teasers.
 *
 * The implementation strategy is **packet-level**, not the scoreboard-team
 * trick: `Player.hidePlayer` works for players but cannot drop arbitrary
 * entity-spawn packets per-viewer. PacketEvents wraps both spawn / destroy
 * + the 1.20+ `PlayerInfoUpdate` / `PlayerInfoRemove` tablet split, so the
 * Wave 6b Paper adapter drops the spawn packet on egress and the equivalent
 * destroy packet on hide-toggle.
 *
 * **Vanilla fallback.** When PacketEvents is not installed (ADR-0009 soft-dep
 * guarantee), implementations may degrade to `Player.hidePlayer` /
 * `Player.hideEntity` for players + spawn-suppression no-op for non-player
 * entities, surfacing the limitation through [VisibilityError.BackendError]
 * with [com.astarworks.astera.core.domain.visibility.PacketBackendError.PacketEventsUnavailable].
 * Domain code must accept that non-player entities may be unhideable in that
 * mode; tests assert the failure is *reported*, not silently swallowed.
 *
 * Implementations are NOT thread-safe (called from Paper's main thread or the
 * server-tick scheduler only). All read methods return defensive copies — the
 * caller is free to mutate the returned collection.
 */
public interface IPlayerVisibilityGateway {

    public fun hideEntity(viewer: PlayerId, target: EntityHandle): Result<Unit, VisibilityError>

    public fun showEntity(viewer: PlayerId, target: EntityHandle): Result<Unit, VisibilityError>

    public fun hidePlayer(viewer: PlayerId, target: PlayerId): Result<Unit, VisibilityError>

    public fun showPlayer(viewer: PlayerId, target: PlayerId): Result<Unit, VisibilityError>

    /**
     * Entities currently hidden from [viewer] (defensive snapshot).
     *
     * Implementations may use a synthetic [EntityHandle] with backendId
     * `"player"` to surface players that have been hidden via [hidePlayer]
     * — callers should not rely on the backendId value but should treat the
     * UUID as identity.
     */
    public fun hiddenFromViewer(viewer: PlayerId): Collection<EntityHandle>

    /**
     * Viewers that currently see [target]. Inverse index of [hiddenFromViewer]
     * for the symmetric query.
     */
    public fun visibleToViewers(target: EntityHandle): Collection<PlayerId>
}
