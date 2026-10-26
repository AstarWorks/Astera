package com.astarworks.astera.core.domain.world

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Sealed record of a single block-level mutation in a tracked region (Wave 5d).
 *
 * Used by `BlockChangeJournal` (Match plugin) to keep a per-match log that
 * `RestoreBlocksOnMatchEndedUseCase` replays in reverse on `MatchEnded`:
 * - [Broken] entries get [restore] (block put back to `prevBlockType`)
 * - [Placed] entries get removed (no-op block where one was placed)
 *
 * `worldId` is intentionally absent: the journal is keyed by match, and per
 * Wave 5d's `MatchWorld` design every match owns at most one world. Cross-
 * world block tracking is a Phase 5+ Metaverse concern (ADR-0045 / Wave 9a).
 */
public sealed class BlockChange {
    public abstract val position: Vec3
    public abstract val byPlayer: PlayerId?
    public abstract val atTick: Long

    public data class Placed(
        override val position: Vec3,
        val blockType: BlockType,
        override val byPlayer: PlayerId?,
        override val atTick: Long,
    ) : BlockChange()

    public data class Broken(
        override val position: Vec3,
        val prevBlockType: BlockType,
        override val byPlayer: PlayerId?,
        override val atTick: Long,
    ) : BlockChange()
}
