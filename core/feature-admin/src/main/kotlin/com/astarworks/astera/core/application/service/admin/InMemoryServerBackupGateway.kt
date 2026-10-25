package com.astarworks.astera.core.application.service.admin

import com.astarworks.astera.core.application.port.outbound.admin.IServerBackupGateway
import com.astarworks.astera.core.application.port.outbound.admin.PlayerBackupEnvelope
import com.astarworks.astera.core.application.port.outbound.admin.ServerBackupError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 1 PoC [IServerBackupGateway] — keeps the latest exported envelope per
 * player in memory. Production deployments overlay a Postgres-backed export
 * pipeline (Phase 4 / Wave 8a) that joins the same per-shard repositories;
 * the in-memory impl is sufficient for AsteraTestHarness round-trip tests
 * and `/astera backup export <player>` development.
 *
 * Shard composition is the caller's responsibility: pass a map of
 * `(shardName → JSON blob)` to [registerExport] (typically wired in
 * `AsteraCorePlugin` from the registered repositories). The Wave 5t port
 * deliberately does not depend on every repository symbol to keep core
 * coupling minimal — coupling happens at the wiring layer.
 *
 * Schema version: 1. Bump on incompatible shard-shape changes.
 */
public class InMemoryServerBackupGateway(
    private val schemaVersion: Int = 1,
    private val shardComposer: (PlayerId) -> Map<String, String> = { emptyMap() },
    private val shardImporter: (PlayerId, Map<String, String>) -> Result<Unit, ServerBackupError> = { _, _ ->
        Result.success(Unit)
    },
) : IServerBackupGateway {

    private val cachedEnvelopes: MutableMap<PlayerId, PlayerBackupEnvelope> = ConcurrentHashMap()

    override fun export(playerId: PlayerId): Result<PlayerBackupEnvelope, ServerBackupError> {
        return try {
            val shards = shardComposer(playerId)
            val envelope = PlayerBackupEnvelope(
                playerId = playerId,
                exportedAt = Instant.now(),
                schemaVersion = schemaVersion,
                shards = shards,
            )
            cachedEnvelopes[playerId] = envelope
            Result.success(envelope)
        } catch (t: Throwable) {
            Result.failure(ServerBackupError.IoFailed(t.message ?: t.javaClass.simpleName))
        }
    }

    override fun import(envelope: PlayerBackupEnvelope): Result<Unit, ServerBackupError> {
        if (envelope.schemaVersion != schemaVersion) {
            return Result.failure(ServerBackupError.UnsupportedSchema(envelope.schemaVersion))
        }
        return shardImporter(envelope.playerId, envelope.shards)
            .also {
                if (it is Result.Success) cachedEnvelopes[envelope.playerId] = envelope
            }
    }

    /** Test-only: read back the cached envelope from a prior export. */
    public fun cachedFor(playerId: PlayerId): PlayerBackupEnvelope? = cachedEnvelopes[playerId]
}
