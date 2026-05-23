package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IProfileCardService
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.profile.CosmeticSummary
import com.astarworks.astera.core.domain.profile.EquipmentSummary
import com.astarworks.astera.core.domain.profile.ProfileVisibility
import com.astarworks.astera.core.domain.profile.PublicProfileCard
import com.astarworks.astera.core.domain.profile.TitleSummary
import com.astarworks.astera.persistence.table.ProfileCardSnapshotsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [IProfileCardService] (Wave 5flex / ADR-0102 surface #3).
 *
 * Registered at priority 100 by `AsteraPersistencePlugin`; overlays the
 * in-memory default (`InMemoryProfileCardService`) registered by
 * `AsteraCorePlugin` at priority 0.
 *
 * Visibility values whose textual code is neither `PUBLIC` nor
 * `PRIVATE` cause the row to be skipped on read — defense against a
 * manual UPDATE that introduces an unknown variant. The CHECK
 * constraint in V35 ensures this never happens through the application
 * layer.
 */
public class PostgresProfileCardService(private val db: Database) : IProfileCardService {

    override fun find(playerId: PlayerId): PublicProfileCard? = transaction(db) {
        ProfileCardSnapshotsTable
            .selectAll()
            .where { ProfileCardSnapshotsTable.playerId eq playerId.uuid }
            .firstOrNull()
            ?.toDomainOrNull()
    }

    override fun save(card: PublicProfileCard): Unit = transaction(db) {
        ProfileCardSnapshotsTable.upsert(ProfileCardSnapshotsTable.playerId) {
            it[playerId] = card.playerId.uuid
            it[displayName] = card.displayName
            it[visibility] = card.visibility.name
            it[equipmentItemCount] = card.equipment.itemCount
            it[equipmentFeaturedId] = card.equipment.featuredItemId
            it[titlesUnlockedCount] = card.titles.unlockedCount
            it[titlesEquippedId] = card.titles.equippedTitleId
            it[cosmeticsUnlockedCount] = card.cosmetics.unlockedCount
            it[cosmeticsFeaturedId] = card.cosmetics.featuredCosmeticId
            it[powerIndexTotal] = card.powerIndexTotal
            it[computedAt] = card.computedAt
        }
        Unit
    }

    override fun delete(playerId: PlayerId): Unit = transaction(db) {
        ProfileCardSnapshotsTable.deleteWhere { ProfileCardSnapshotsTable.playerId eq playerId.uuid }
        Unit
    }

    override fun all(): List<PublicProfileCard> = transaction(db) {
        ProfileCardSnapshotsTable
            .selectAll()
            .mapNotNull { it.toDomainOrNull() }
    }

    private fun ResultRow.toDomainOrNull(): PublicProfileCard? {
        val visibility = runCatching {
            ProfileVisibility.valueOf(this[ProfileCardSnapshotsTable.visibility])
        }.getOrNull() ?: return null
        return PublicProfileCard(
            playerId = PlayerId(this[ProfileCardSnapshotsTable.playerId]),
            displayName = this[ProfileCardSnapshotsTable.displayName],
            visibility = visibility,
            equipment = EquipmentSummary(
                itemCount = this[ProfileCardSnapshotsTable.equipmentItemCount],
                featuredItemId = this[ProfileCardSnapshotsTable.equipmentFeaturedId],
            ),
            titles = TitleSummary(
                unlockedCount = this[ProfileCardSnapshotsTable.titlesUnlockedCount],
                equippedTitleId = this[ProfileCardSnapshotsTable.titlesEquippedId],
            ),
            cosmetics = CosmeticSummary(
                unlockedCount = this[ProfileCardSnapshotsTable.cosmeticsUnlockedCount],
                featuredCosmeticId = this[ProfileCardSnapshotsTable.cosmeticsFeaturedId],
            ),
            powerIndexTotal = this[ProfileCardSnapshotsTable.powerIndexTotal],
            computedAt = this[ProfileCardSnapshotsTable.computedAt],
        )
    }
}
