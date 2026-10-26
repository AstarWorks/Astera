package com.astarworks.astera.persistence.permission

import com.astarworks.astera.persistence.adapter.io.blockingTransaction
import com.astarworks.astera.core.application.port.outbound.permission.RoleError
import com.astarworks.astera.core.application.service.permission.IRoleAssignmentRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.permission.RoleAssignmentRecord
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.table.PermissionRoleAssignmentTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Instant

/**
 * Wave 5t (ADR-0086) — Postgres-backed [IRoleAssignmentRepository] for the
 * Minimal permission backend. V22 migration table.
 *
 * Registered at priority 100 by `AsteraPersistencePlugin`; overlays the
 * in-memory default.
 *
 * Storage shape:
 *   - `expires_at IS NULL`         → permanent grant
 *   - `expires_at <= now()`        → expired (filtered out of [findActive])
 *   - `expires_at > now()`         → time-bounded, active
 *
 * Expiry sweep ([sweepExpired]) reads + deletes in one transaction so the
 * deleted rows can be returned to the caller for `RoleExpired` event emission.
 */
public class PostgresRoleAssignmentRepository(private val db: Database) : IRoleAssignmentRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun findActive(playerId: PlayerId, now: Instant): Collection<RoleAssignmentRecord> =
        blockingTransaction(db) {
            PermissionRoleAssignmentTable
                .selectAll()
                .where { PermissionRoleAssignmentTable.playerId eq playerId.uuid }
                .map { row ->
                    RoleAssignmentRecord(
                        playerId = PlayerId(row[PermissionRoleAssignmentTable.playerId]),
                        roleId = RoleId(row[PermissionRoleAssignmentTable.roleId]),
                        grantedAt = row[PermissionRoleAssignmentTable.grantedAt],
                        expiresAt = row[PermissionRoleAssignmentTable.expiresAt],
                        grantedBy = row[PermissionRoleAssignmentTable.grantedBy]?.let { PlayerId(it) },
                    )
                }
                .filter {
                    val ttl = it.expiresAt
                    ttl == null || ttl.isAfter(now)
                }
        }

    override fun upsert(record: RoleAssignmentRecord): Result<Unit, RoleError> {
        return try {
            blockingTransaction(db) {
                PermissionRoleAssignmentTable.upsert(
                    PermissionRoleAssignmentTable.playerId,
                    PermissionRoleAssignmentTable.roleId,
                ) {
                    it[playerId] = record.playerId.uuid
                    it[roleId] = record.roleId.value
                    it[grantedAt] = record.grantedAt
                    it[expiresAt] = record.expiresAt
                    it[grantedBy] = record.grantedBy?.uuid
                }
            }
            Result.success(Unit)
        } catch (ex: SQLException) {
            log.warn("upsert role-assignment SQL failure", ex)
            Result.failure(RoleError.BackendFailure(ex.message ?: "SQL failure"))
        }
    }

    override fun remove(playerId: PlayerId, roleId: RoleId): Result<Unit, RoleError> {
        return try {
            val deleted = blockingTransaction(db) {
                PermissionRoleAssignmentTable.deleteWhere {
                    (PermissionRoleAssignmentTable.playerId eq playerId.uuid) and
                        (PermissionRoleAssignmentTable.roleId eq roleId.value)
                }
            }
            if (deleted > 0) Result.success(Unit)
            else Result.failure(RoleError.NotAssigned(playerId, roleId))
        } catch (ex: SQLException) {
            log.warn("remove role-assignment SQL failure", ex)
            Result.failure(RoleError.BackendFailure(ex.message ?: "SQL failure"))
        }
    }

    override fun sweepExpired(now: Instant): List<RoleAssignmentRecord> = blockingTransaction(db) {
        val expired = PermissionRoleAssignmentTable
            .select(
                PermissionRoleAssignmentTable.playerId,
                PermissionRoleAssignmentTable.roleId,
                PermissionRoleAssignmentTable.grantedAt,
                PermissionRoleAssignmentTable.expiresAt,
                PermissionRoleAssignmentTable.grantedBy,
            )
            .where { PermissionRoleAssignmentTable.expiresAt lessEq now }
            .map { row ->
                RoleAssignmentRecord(
                    playerId = PlayerId(row[PermissionRoleAssignmentTable.playerId]),
                    roleId = RoleId(row[PermissionRoleAssignmentTable.roleId]),
                    grantedAt = row[PermissionRoleAssignmentTable.grantedAt],
                    expiresAt = row[PermissionRoleAssignmentTable.expiresAt],
                    grantedBy = row[PermissionRoleAssignmentTable.grantedBy]?.let { PlayerId(it) },
                )
            }
        if (expired.isNotEmpty()) {
            PermissionRoleAssignmentTable.deleteWhere {
                PermissionRoleAssignmentTable.expiresAt lessEq now
            }
        }
        expired
    }
}
