package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginStreakRepository
import com.astarworks.astera.core.domain.loginbonus.LoginStreakRecord
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.table.LoginStreaksTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/** Postgres-backed [ILoginStreakRepository] (V61, ADR-0199). */
public class PostgresLoginStreakRepository(private val db: Database) : ILoginStreakRepository {

    override fun findByPlayer(playerId: PlayerId): LoginStreakRecord? = transaction(db) {
        LoginStreaksTable.selectAll()
            .where { LoginStreaksTable.playerId eq playerId.uuid }
            .firstOrNull()
            ?.let {
                LoginStreakRecord(
                    playerId = playerId,
                    streakDays = it[LoginStreaksTable.streakDays],
                    lastClaimDate = it[LoginStreaksTable.lastClaimDate],
                )
            }
    }

    override fun upsert(record: LoginStreakRecord) {
        transaction(db) {
            LoginStreaksTable.upsert(LoginStreaksTable.playerId) {
                it[playerId] = record.playerId.uuid
                it[streakDays] = record.streakDays
                it[lastClaimDate] = record.lastClaimDate
                it[updatedAt] = Instant.now()
            }
        }
    }
}
