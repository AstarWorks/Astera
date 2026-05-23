package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillProgression

/**
 * Outbound port: per-player, per-axis progression storage
 * (Wave 5multi-skill, ADR-0101).
 *
 * Lives in `:plugin:core` so every plugin can publish XP-source events that
 * flow into the [com.astarworks.astera.stats.application.usecase.progression.AwardSkillXpUseCase]
 * use case. The use case is the single writer; this port is the boundary.
 *
 * Default in-memory impl in `:plugin:stats` at priority 0; Postgres overlay
 * in `:plugin:persistence` at priority 100 (ADR-0020 pattern, mirrors
 * [INormaProgressRepository] / [IPlayerStatsRepository]).
 *
 * The port intentionally keeps writes coarse-grained:
 *  - [find] / [allFor] for reads
 *  - [upsert] for single-axis writes (the common case)
 *  - [upsertAll] for bulk hydration (player join / batch backfill)
 *
 * Composite key is `(playerId, kind)` — no separate "row id" surface, so the
 * Postgres impl doesn't need a synthetic primary key.
 */
public interface ISkillProgressionRepository {

    /** Find the persisted progression for ([playerId], [kind]), or `null` if untouched. */
    public fun find(playerId: PlayerId, kind: SkillKind): SkillProgression?

    /** Upsert a single axis. `progression.playerId` / `.kind` must match arguments. */
    public fun upsert(progression: SkillProgression)

    /** Every persisted axis for [playerId] (may include `< AXIS_COUNT` entries on a new player). */
    public fun allFor(playerId: PlayerId): Collection<SkillProgression>

    /** Bulk upsert (used by tests + admin import). */
    public fun upsertAll(progressions: Collection<SkillProgression>) {
        progressions.forEach { upsert(it) }
    }
}
