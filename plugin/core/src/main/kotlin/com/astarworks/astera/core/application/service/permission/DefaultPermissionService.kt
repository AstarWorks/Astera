package com.astarworks.astera.core.application.service.permission

import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.permission.IPermissionBackend
import com.astarworks.astera.core.application.port.outbound.permission.RoleError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PermissionBackendSwitched
import com.astarworks.astera.core.domain.event.PermissionDenied
import com.astarworks.astera.core.domain.event.RoleGranted
import com.astarworks.astera.core.domain.event.RoleRevoked
import com.astarworks.astera.core.domain.permission.PermissionDenyReason
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Wave 5t (ADR-0086) — backend selector. Composes a *priority-ordered* list of
 * [IPermissionBackend]s, dispatches every op to the first available one, and
 * emits a [PermissionBackendSwitched] event whenever the active backend
 * changes.
 *
 * Phase 1 wiring: `[LuckPermsPermissionBackend, MinimalPermissionBackend]` —
 * LuckPerms wins when installed; Minimal is the always-available fallback.
 * The selector re-checks availability on every call (cheap — just a flag /
 * `Bukkit.getPluginManager().getPlugin("LuckPerms")` lookup in the Paper
 * adapter); no scheduled poller required.
 *
 * The selector deliberately does **not** implement [com.astarworks.astera.core.application.port.outbound.IPermissionService]
 * — that pre-existing port has Wave-3 semantics (RoleDefinition-shaped, no
 * priority, no expiry). Wave 5t introduces this richer surface alongside the
 * legacy one; the wiring layer (`AsteraCorePlugin`) can route the
 * `PermissionGating` decorator and admin commands through whichever surface
 * the call site needs.
 */
public class DefaultPermissionService(
    backends: List<IPermissionBackend>,
    private val broadcaster: IBroadcaster,
    private val tickProvider: () -> Long = { 0L },
) {
    init {
        require(backends.isNotEmpty()) { "DefaultPermissionService requires at least one backend" }
        val ids = backends.map { it.backendId() }
        require(ids.distinct().size == ids.size) {
            "Backend ids must be unique; got $ids"
        }
    }

    private val backends: List<IPermissionBackend> = backends.toList()
    private val activeRef: AtomicReference<String> = AtomicReference(
        this.backends.firstOrNull { it.isAvailable() }?.backendId()
            ?: this.backends.last().backendId(),
    )

    /** Currently-selected backend id ("luckperms" / "minimal" / …). */
    public fun activeBackendId(): String {
        val backend = pick()
        val previous = activeRef.getAndSet(backend.backendId())
        if (previous != backend.backendId()) {
            broadcaster.publish(
                PermissionBackendSwitched(
                    oldBackend = previous,
                    newBackend = backend.backendId(),
                    atTick = tickProvider(),
                ),
            )
        }
        return backend.backendId()
    }

    public fun check(playerId: PlayerId, node: String): Boolean {
        val backend = pick()
        recordActive(backend.backendId())
        val granted = backend.check(playerId, node)
        if (!granted) {
            broadcaster.publish(
                PermissionDenied(
                    playerId = playerId,
                    node = node,
                    source = PermissionDenyReason.NoMatchingRule,
                    atTick = tickProvider(),
                ),
            )
        }
        return granted
    }

    public fun rolesOf(playerId: PlayerId): Set<RoleId> {
        val backend = pick()
        recordActive(backend.backendId())
        return backend.rolesOf(playerId)
    }

    public fun grant(
        playerId: PlayerId,
        roleId: RoleId,
        duration: Duration? = null,
        grantedBy: PlayerId? = null,
    ): Result<Unit, RoleError> {
        val backend = pick()
        recordActive(backend.backendId())
        return when (val outcome = backend.grant(playerId, roleId, duration, grantedBy)) {
            is Result.Success -> {
                val now = Instant.now()
                broadcaster.publish(
                    RoleGranted(
                        playerId = playerId,
                        roleId = roleId,
                        grantedBy = grantedBy,
                        expiresAt = duration?.let { now.plus(it) },
                        atTick = tickProvider(),
                    ),
                )
                outcome
            }
            is Result.Failure -> outcome
        }
    }

    public fun revoke(
        playerId: PlayerId,
        roleId: RoleId,
        revokedBy: PlayerId? = null,
    ): Result<Unit, RoleError> {
        val backend = pick()
        recordActive(backend.backendId())
        return when (val outcome = backend.revoke(playerId, roleId, revokedBy)) {
            is Result.Success -> {
                broadcaster.publish(
                    RoleRevoked(
                        playerId = playerId,
                        roleId = roleId,
                        revokedBy = revokedBy,
                        atTick = tickProvider(),
                    ),
                )
                outcome
            }
            is Result.Failure -> outcome
        }
    }

    private fun pick(): IPermissionBackend {
        for (backend in backends) {
            if (backend.isAvailable()) return backend
        }
        // Defensive: backends list is non-empty per init check; the last
        // entry is the always-available fallback. If somehow none is
        // available, return the lowest-priority one so callers still get a
        // structured (failing) response rather than a NPE.
        return backends.last()
    }

    private fun recordActive(backendId: String) {
        val previous = activeRef.getAndSet(backendId)
        if (previous != backendId) {
            broadcaster.publish(
                PermissionBackendSwitched(
                    oldBackend = previous,
                    newBackend = backendId,
                    atTick = tickProvider(),
                ),
            )
        }
    }
}
