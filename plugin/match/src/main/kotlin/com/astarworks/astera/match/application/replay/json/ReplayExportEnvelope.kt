package com.astarworks.astera.match.application.replay.json

import kotlinx.serialization.Serializable

/**
 * Top-level wire envelope written by `/astera-match replay export <matchId>`
 * (Wave 5v, ADR-0088).
 *
 * The Postgres `match_replays` row decomposes a [com.astarworks.astera.core
 * .domain.replay.RecordedReplay] into 3 JSONB columns + 4 columnar fields;
 * the export combines them back into a single document with the same field
 * names so Phase 4 playback consumers (web UI / standalone clip viewer)
 * can decode `.replay.json` files identically to a DB row.
 *
 * `id` / `matchId` are flat strings rather than wrapping `ReplayId` /
 * `MatchId` value classes because kotlinx-serialization's default value-
 * class handling adds a redundant level of nesting in the output — flat
 * fields read naturally from JavaScript consumers in Phase 4.
 */
@Serializable
public data class ReplayExportEnvelope(
    val id: String,
    val matchId: String,
    val startTick: Long,
    val endTick: Long,
    val sizeBytes: Int,
    val format: ReplayFormatJson,
    val keyFrames: KeyFramesJson,
    val events: ReplayEventsJson,
)
