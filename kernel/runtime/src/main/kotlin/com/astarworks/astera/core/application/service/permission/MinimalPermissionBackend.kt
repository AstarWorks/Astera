package com.astarworks.astera.core.application.service.permission

import com.astarworks.astera.core.application.port.outbound.permission.IPermissionBackend
import com.astarworks.astera.core.application.port.outbound.permission.IRoleRegistry
import com.astarworks.astera.core.application.port.outbound.permission.RoleError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.permission.PermissionRule
import com.astarworks.astera.core.domain.permission.RoleAssignmentRecord
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.permission.RoleRecord
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Wave 5t (ADR-0086) — default permission backend. `backendId = "minimal"`,
 * always [isAvailable].
 *
 * Reads role definitions from [IRoleRegistry] (content-driven). Assignments
 * are kept in an in-memory ConcurrentMap by default; the persistence overlay
 * (`PostgresMinimalPermissionAssignmentRepository` from Wave 5t, V22) plugs
 * in via the [assignments] constructor parameter without changing the
 * resolution logic.
 *
 * Resolution rule (matches Citizens / LuckPerms baseline):
 *
 *   1. Resolve the player's currently-assigned roles (filter out expired).
 *   2. Sort by descending [RoleRecord.priority].
 *   3. Scan each role's rules in order; the first matching rule wins
 *      (Allow → true, Deny → false). A higher-priority role's Deny beats
 *      a lower-priority role's Allow.
 *   4. No matching rule → false (default deny).
 */
public class MinimalPermissionBackend(
    private val roles: IRoleRegistry,
    private val assignments: IRoleAssignmentRepository = InMemoryRoleAssignmentRepository(),
    private val clock: () -> Instant = Instant::now,
) : IPermissionBackend {

    override fun backendId(): String = BACKEND_ID

    override fun isAvailable(): Boolean = true

    override fun check(playerId: PlayerId, node: String): Boolean {
        val now = clock()
        val assigned = assignments.findActive(playerId, now)
        if (assigned.isEmpty()) return false

        val records = assigned
            .mapNotNull { roles.resolve(it.roleId) }
            .sortedByDescending { it.priority }

        for (role in records) {
            for (rule in role.permissions) {
                if (PermissionRule.matches(rule.node, node)) {
                    return when (rule) {
                        is PermissionRule.Allow -> true
                        is PermissionRule.Deny -> false
                    }
                }
            }
        }
        return false
    }

    override fun rolesOf(playerId: PlayerId): Set<RoleId> {
        val now = clock()
        return assignments.findActive(playerId, now).map { it.roleId }.toSet()
    }

    override fun grant(
        playerId: PlayerId,
        roleId: RoleId,
        duration: Duration?,
        grantedBy: PlayerId?,
    ): Result<Unit, RoleError> {
        if (roles.resolve(roleId) == null) {
            return Result.failure(RoleError.RoleNotFound(roleId))
        }
        val now = clock()
        val record = RoleAssignmentRecord(
            playerId = playerId,
            roleId = roleId,
            grantedAt = now,
            expiresAt = duration?.let { now.plus(it) },
            grantedBy = grantedBy,
        )
        return assignments.upsert(record)
    }

    override fun revoke(
        playerId: PlayerId,
        roleId: RoleId,
        revokedBy: PlayerId?,
    ): Result<Unit, RoleError> {
        return assignments.remove(playerId, roleId)
    }

    /**
     * Expiry sweep: return every assignment whose `expiresAt` is in the past
     * (relative to [now]) and remove them. Caller fires `RoleExpired` events
     * for each.
     */
    public fun sweepExpired(now: Instant = clock()): List<RoleAssignmentRecord> {
        return assignments.sweepExpired(now)
    }

    public companion object {
        public const val BACKEND_ID: String = "minimal"
    }
}

/**
 * Storage port for Minimal backend assignments. In-memory default + V22
 * Postgres overlay registered at priority 100 in `AsteraPersistencePlugin`.
 */
public interface IRoleAssignmentRepository {
    public fun findActive(playerId: PlayerId, now: Instant): Collection<RoleAssignmentRecord>
    public fun upsert(record: RoleAssignmentRecord): Result<Unit, RoleError>
    public fun remove(playerId: PlayerId, roleId: RoleId): Result<Unit, RoleError>
    public fun sweepExpired(now: Instant): List<RoleAssignmentRecord>
}

/** Process-local default. */
public class InMemoryRoleAssignmentRepository : IRoleAssignmentRepository {
    private val byPlayer: MutableMap<PlayerId, MutableMap<RoleId, RoleAssignmentRecord>> = ConcurrentHashMap()

    override fun findActive(playerId: PlayerId, now: Instant): Collection<RoleAssignmentRecord> {
        val perPlayer = byPlayer[playerId] ?: return emptyList()
        return perPlayer.values.filter { it.expiresAt == null || it.expiresAt.isAfter(now) }
    }

    override fun upsert(record: RoleAssignmentRecord): Result<Unit, RoleError> {
        val perPlayer = byPlayer.computeIfAbsent(record.playerId) { ConcurrentHashMap() }
        perPlayer[record.roleId] = record
        return Result.success(Unit)
    }

    override fun remove(playerId: PlayerId, roleId: RoleId): Result<Unit, RoleError> {
        val perPlayer = byPlayer[playerId]
            ?: return Result.failure(RoleError.NotAssigned(playerId, roleId))
        val removed = perPlayer.remove(roleId)
            ?: return Result.failure(RoleError.NotAssigned(playerId, roleId))
        if (perPlayer.isEmpty()) {
            byPlayer.remove(playerId)
        }
        @Suppress("UNUSED_VARIABLE")
        val removedRef = removed
        return Result.success(Unit)
    }

    override fun sweepExpired(now: Instant): List<RoleAssignmentRecord> {
        val expired = mutableListOf<RoleAssignmentRecord>()
        for ((_, perPlayer) in byPlayer) {
            val toRemove = perPlayer.values.filter {
                val ttl = it.expiresAt ?: return@filter false
                !ttl.isAfter(now)
            }
            for (rec in toRemove) {
                perPlayer.remove(rec.roleId)
                expired += rec
            }
        }
        return expired
    }
}
