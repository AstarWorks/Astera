package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.TransferError
import com.astarworks.astera.core.domain.proxy.TransferReason
import com.astarworks.astera.core.domain.proxy.TransferWhen

/**
 * Outbound port: high-level cross-server transfer flow (ADR-0084, Wave 5r).
 *
 * Sits **above** [IProxyMessenger] and adds the things a raw plugin-message
 * port does not own:
 *  1. **Reasoned moves.** Every transfer carries a [TransferReason] so the
 *     other end can show the right HUD copy and operators can audit moves.
 *  2. **Permission gate.** The default impl checks
 *     `astera.transfer.<targetServer>` via [IPermissionService] (Wave 5t)
 *     before invoking the messenger, so a player without the role gets
 *     [TransferError.PermissionDenied] instead of having the proxy silently
 *     drop the request.
 *  3. **Deferred queue.** [queueTransfer] handles "do this later" scheduling
 *     (`OnNextLogin`, `AfterCurrentMatch`) without forcing every caller to
 *     re-implement a queue.
 *
 * The port returns [Result] with a sealed [TransferError]; no raw
 * `Throwable` leaks (Konsist rule).
 *
 * Phase 1 default impl: `InMemoryServerTransferGateway` (priority 0). Wave 6b
 * lands `VelocityServerTransferGateway` (priority 100) on the Velocity side
 * + `BungeeServerTransferGateway` (priority 100) on the Paper side fallback.
 */
public interface IServerTransferGateway {

    /**
     * Move [playerId] to [targetServer] immediately.
     *
     * Steps:
     *  1. Check `astera.transfer.<targetServer.value>` permission (if a
     *     permission service is wired). Denial → [TransferError.PermissionDenied].
     *  2. Publish a `PlayerTransferRequested` domain event so listeners
     *     (HUD, audit, inventory snapshot) can react before the move.
     *  3. Delegate to [IProxyMessenger.sendToServer]. Any [com.astarworks.astera.core.domain.proxy.ProxyError]
     *     is wrapped in [TransferError.ProxyUnavailable].
     *
     * Success here means "the proxy accepted the request" — the player has
     * not arrived yet. Subscribe to `PlayerArrivedOnServer` for arrival.
     */
    public fun transfer(
        playerId: PlayerId,
        targetServer: ServerName,
        transferReason: TransferReason,
    ): Result<Unit, TransferError>

    /**
     * Queue a transfer to fire later, per [when_].
     *
     * Parameter is named `when_` because `when` is a hard Kotlin keyword.
     * The queue is persisted in-memory by the default impl; Wave 6b's
     * Velocity adapter persists across the proxy's restart.
     *
     * Returns [TransferError.QueueFull] if the in-memory queue is at
     * capacity. Permission is **not** checked at queue time — it is checked
     * at fire time, since the player's roles may change between now and then.
     */
    public fun queueTransfer(
        playerId: PlayerId,
        targetServer: ServerName,
        when_: TransferWhen,
        transferReason: TransferReason,
    ): Result<Unit, TransferError>
}
