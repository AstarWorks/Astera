package com.astarworks.astera.core.domain.backup

import com.astarworks.astera.core.domain.world.WorldId

/**
 * What [com.astarworks.astera.core.application.port.outbound.backup.IServerBackupGateway]
 * is asked to snapshot or restore (Wave 5w / ADR-0089).
 *
 * Server-scoped (anti-grief restic). Per-player JSON export is a separate
 * concern owned by Wave 5t's `IPlayerDataExportGateway`.
 *
 * `IslandWorld` is **reserved** for Phase 3 (生活の島) — the [IslandId]
 * value class lives next to this file but no production caller uses it
 * yet. Including the variant now keeps the sealed hierarchy stable for
 * Phase 3 (additive vs. break-the-sealed shape).
 */
public sealed interface BackupTarget {
    /** Whole Postgres cluster (per restic policy, pg_dump or filesystem snapshot). */
    public data object Postgres : BackupTarget

    /** Match world archive directory (slime-world / per-match folders). */
    public data object MatchWorldsArchive : BackupTarget

    /** Phase 3+ — a single island world. */
    public data class IslandWorld(val islandId: IslandId) : BackupTarget

    /** A specific custom dimension world by [WorldId]. */
    public data class CustomDimensionWorld(val worldId: WorldId) : BackupTarget

    /** Compose multiple targets into one logical batch. */
    public data class All(val include: Set<BackupTarget>) : BackupTarget {
        init {
            require(include.isNotEmpty()) { "All() must include at least one nested target" }
            require(include.none { it is All }) { "All() must not nest All() recursively (flatten first)" }
        }
    }
}

/**
 * Phase 3+ island identifier (Wave 5w / ADR-0089). Defined alongside
 * [BackupTarget] so the sealed hierarchy is closed in Core today; the
 * Phase 3 island plugin will reference this value class without
 * round-tripping through Phase 1 wiring decisions.
 */
@JvmInline
public value class IslandId(public val value: String) {
    init {
        require(value.isNotBlank()) { "IslandId must not be blank" }
    }
}
