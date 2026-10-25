package com.astarworks.astera.core.application.port.outbound.admin

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Wave 5t (ADR-0086) — per-player data export / import gateway.
 *
 * Scope split versus Wave 5w (restic):
 *
 *   - **Wave 5t (this port)** — *player-scoped* backup. Single player's
 *     Wallet / Stats / Inventory snapshot / Loadout / Settings / Vault
 *     serialized to a self-contained JSON envelope. Used by admin tooling
 *     for GDPR export ("give me my data"), per-player rollback ("undo the
 *     last 10 minutes for this one griefer's victim"), and cross-server
 *     migration ("ship me to the EU server").
 *   - **Wave 5w (deferred, restic)** — *server-scoped* backup. Whole
 *     Postgres + content snapshot via restic on a 24h cadence. Separate
 *     concern, separate port (`IServerBackupOrchestrator` in Wave 5w plan).
 *
 * The Wave 5t port works at the **application layer** — it composes existing
 * repositories (`IPlayerStatsRepository`, `IPlayerAttributeContainerStore`, …)
 * and returns the envelope as a structured value, not a file. The caller
 * (admin command, web admin endpoint) is responsible for choosing how to
 * persist the envelope (download, sidecar JSON in `private/backups/`, …).
 */
public interface IServerBackupGateway {

    /**
     * Export every persisted shard of [playerId]'s state into a single
     * envelope. Read-only: does not mutate any repository.
     */
    public fun export(playerId: PlayerId): Result<PlayerBackupEnvelope, ServerBackupError>

    /**
     * Import an envelope back into the state shards. Idempotent on the
     * envelope's [PlayerBackupEnvelope.playerId]. The Phase 1 implementation
     * is a **replace** (last-write wins) — Phase 4 may layer a merge policy
     * (LWW per field, prefer-incoming, …) on top.
     */
    public fun import(envelope: PlayerBackupEnvelope): Result<Unit, ServerBackupError>
}

/**
 * Self-contained export envelope. JSON-serializable in the adapter layer
 * (not in domain — kotlinx.serialization annotations stay outside `domain.*`
 * per Konsist rule 6 / coding-posture).
 *
 * Each [shards] map entry is `(repositoryName → opaque JSON blob)`. The
 * repository name MUST match the Astera service registry's port simple name
 * (e.g. `"IPlayerStatsRepository"`, `"IPlayerSettingsRepository"`) so the
 * import side can dispatch by name without a separate registry.
 *
 * `schemaVersion = 1` reserved for the Wave 5t format. Bump when a shard's
 * blob shape changes incompatibly; the import side MUST refuse a version it
 * does not know.
 */
public data class PlayerBackupEnvelope(
    val playerId: PlayerId,
    val exportedAt: Instant,
    val schemaVersion: Int,
    val shards: Map<String, String>,
)

/** Sealed errors for [IServerBackupGateway]. */
public sealed class ServerBackupError {
    public abstract val messageKey: MessageKey

    /** The envelope's schema version is not understood by this build. */
    public data class UnsupportedSchema(val version: Int) : ServerBackupError() {
        override val messageKey: MessageKey = MessageKey("astera.admin.backup.unsupported_schema")
    }

    /** Underlying I/O or serialization failure. */
    public data class IoFailed(val reason: String) : ServerBackupError() {
        override val messageKey: MessageKey = MessageKey("astera.admin.backup.io_failed")
    }

    /** A shard listed in the envelope did not match a registered importer. */
    public data class UnknownShard(val name: String) : ServerBackupError() {
        override val messageKey: MessageKey = MessageKey("astera.admin.backup.unknown_shard")
    }
}
