package com.astarworks.astera.core.domain.replay

import java.util.UUID

/**
 * Unique identifier for a [RecordedReplay] (Wave 5v, ADR-0094).
 *
 * One-to-one with `MatchId` for "current recording in progress" but the
 * persisted row has its own UUID so a single match can — in principle —
 * be re-recorded (admin tooling, replay restitch) without overwriting
 * earlier captures. Phase 1 PoC stays at one row per match (latest-write-
 * wins via upsert on `match_id`), the explicit ReplayId leaves the door
 * open for an append-only history later.
 */
@JvmInline
public value class ReplayId(public val uuid: UUID) {
    override fun toString(): String = uuid.toString()

    public companion object {
        public fun random(): ReplayId = ReplayId(UUID.randomUUID())
        public fun parse(s: String): ReplayId = ReplayId(UUID.fromString(s))
    }
}
