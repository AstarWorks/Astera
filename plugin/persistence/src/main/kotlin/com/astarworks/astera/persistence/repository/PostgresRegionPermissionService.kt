package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IRegionPermissionService
import com.astarworks.astera.core.application.port.outbound.IStageRegionIndex
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.region.RegionId
import com.astarworks.astera.core.domain.region.RegionPermission
import com.astarworks.astera.core.domain.region.RegionRule
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.persistence.table.RegionPermissionTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * Postgres-backed [IRegionPermissionService] (Wave 5n, V25 migration).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100; overlays the
 * in-memory default registered by Core at priority=0.
 *
 * ## Storage
 *
 * Rules live in [RegionPermissionTable] (`region_permission`). The natural
 * key is `(region_id, permission_key, player_id)`; Postgres treats `NULL`
 * as distinct, so V25 enforces uniqueness via two *partial* unique indices
 * — one for player-specific rules, one for everyone-rules. `upsert`-style
 * semantics are implemented as `DELETE WHERE … + INSERT` inside a single
 * transaction so re-adding the same tuple replaces rather than duplicates.
 *
 * ## Resolution
 *
 * [check] resolves the region(s) at the position via [regionIndex] (the
 * spatial index), then loads every matching rule for those regions in one
 * query. Player-specific rules win over everyone-rules; within the same
 * specificity, any `DENY` blocks the action (defence-in-depth). Default
 * is **allow** when no rule matches.
 *
 * Multi-player rules are stored as multiple rows (one per player) so the
 * table stays first-normal. Phase 1 PoC's per-region rule count is small
 * (< 100 per stage); when this grows we can swap to JSONB array storage.
 */
public class PostgresRegionPermissionService(
    private val db: Database,
    private val regionIndex: IStageRegionIndex,
) : IRegionPermissionService {

    override fun check(
        playerId: PlayerId,
        position: Vec3,
        world: WorldId,
        permission: RegionPermission,
    ): Boolean {
        val regions = regionIndex.regionsAt(world, position)
        if (regions.isEmpty()) return true

        val key = permission.key()
        val regionStrings: List<String> = regions.map { it.value }
        return transaction(db) {
            val rows = RegionPermissionTable
                .selectAll()
                .where {
                    (RegionPermissionTable.regionId inList regionStrings) and
                        (RegionPermissionTable.permissionKey eq key) and
                        (
                            RegionPermissionTable.playerId.isNull() or
                                (RegionPermissionTable.playerId eq playerId.uuid)
                            )
                }
                .toList()

            if (rows.isEmpty()) return@transaction true

            // Group by region; for each region pick the most specific rule.
            val byRegion = rows.groupBy { it[RegionPermissionTable.regionId] }
            var sawDeny = false
            for ((_, regionRows) in byRegion) {
                val playerRow = regionRows.firstOrNull { it[RegionPermissionTable.playerId] != null }
                val everyoneRow = regionRows.firstOrNull { it[RegionPermissionTable.playerId] == null }
                val winning = playerRow ?: everyoneRow ?: continue
                if (winning[RegionPermissionTable.scope] == SCOPE_DENY) {
                    sawDeny = true
                }
            }
            !sawDeny
        }
    }

    override fun allow(
        regionId: RegionId,
        permission: RegionPermission,
        players: Set<PlayerId>?,
    ) {
        upsertRule(regionId, permission, SCOPE_ALLOW, players)
    }

    override fun deny(
        regionId: RegionId,
        permission: RegionPermission,
        players: Set<PlayerId>?,
    ) {
        upsertRule(regionId, permission, SCOPE_DENY, players)
    }

    private fun upsertRule(
        regionId: RegionId,
        permission: RegionPermission,
        scope: String,
        players: Set<PlayerId>?,
    ) {
        val key = permission.key()
        val now = Instant.now()
        transaction(db) {
            if (players == null) {
                // Everyone rule — delete then insert.
                RegionPermissionTable.deleteWhere {
                    (RegionPermissionTable.regionId eq regionId.value) and
                        (RegionPermissionTable.permissionKey eq key) and
                        (RegionPermissionTable.playerId.isNull())
                }
                RegionPermissionTable.insert {
                    it[RegionPermissionTable.regionId] = regionId.value
                    it[RegionPermissionTable.permissionKey] = key
                    it[RegionPermissionTable.scope] = scope
                    it[RegionPermissionTable.playerId] = null
                    it[RegionPermissionTable.updatedAt] = now
                }
            } else {
                // Player-specific rules — delete per-player then bulk insert.
                val uuids: List<UUID> = players.map { it.uuid }
                RegionPermissionTable.deleteWhere {
                    (RegionPermissionTable.regionId eq regionId.value) and
                        (RegionPermissionTable.permissionKey eq key) and
                        (RegionPermissionTable.playerId inList uuids)
                }
                for (uuid in uuids) {
                    RegionPermissionTable.insert {
                        it[RegionPermissionTable.regionId] = regionId.value
                        it[RegionPermissionTable.permissionKey] = key
                        it[RegionPermissionTable.scope] = scope
                        it[RegionPermissionTable.playerId] = uuid
                        it[RegionPermissionTable.updatedAt] = now
                    }
                }
            }
        }
    }

    override fun clear(
        regionId: RegionId,
        permission: RegionPermission,
        players: Set<PlayerId>?,
    ): Boolean {
        val key = permission.key()
        return transaction(db) {
            val deleted = if (players == null) {
                RegionPermissionTable.deleteWhere {
                    (RegionPermissionTable.regionId eq regionId.value) and
                        (RegionPermissionTable.permissionKey eq key) and
                        (RegionPermissionTable.playerId.isNull())
                }
            } else {
                val uuids: List<UUID> = players.map { it.uuid }
                RegionPermissionTable.deleteWhere {
                    (RegionPermissionTable.regionId eq regionId.value) and
                        (RegionPermissionTable.permissionKey eq key) and
                        (RegionPermissionTable.playerId inList uuids)
                }
            }
            deleted > 0
        }
    }

    override fun rules(regionId: RegionId): List<RegionRule> = transaction(db) {
        RegionPermissionTable
            .selectAll()
            .where { RegionPermissionTable.regionId eq regionId.value }
            .mapNotNull { row ->
                val key = row[RegionPermissionTable.permissionKey]
                val permission = decodePermissionKey(key) ?: return@mapNotNull null
                val scope = when (row[RegionPermissionTable.scope]) {
                    SCOPE_ALLOW -> RegionRule.Scope.ALLOW
                    SCOPE_DENY -> RegionRule.Scope.DENY
                    else -> return@mapNotNull null
                }
                val pid = row[RegionPermissionTable.playerId]
                val players: Set<PlayerId>? = pid?.let { setOf(PlayerId(it)) }
                RegionRule(regionId, permission, scope, players)
            }
    }

    private companion object {
        private const val SCOPE_ALLOW = "allow"
        private const val SCOPE_DENY = "deny"

        private fun decodePermissionKey(key: String): RegionPermission? = when {
            key == "build" -> RegionPermission.Build
            key == "break" -> RegionPermission.Break
            key == "interact" -> RegionPermission.Interact
            key == "pvp" -> RegionPermission.PvP
            key == "pickup" -> RegionPermission.Pickup
            key == "drop" -> RegionPermission.Drop
            key.startsWith("use:") -> RegionPermission.Use(ItemId(key.removePrefix("use:")))
            else -> null
        }
    }
}
