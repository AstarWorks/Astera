package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.stage.StageId
import java.time.Instant

/**
 * Match transitioned from WAITING → COUNTDOWN and is now live.
 *
 * # `modeKey`
 *
 * Wave 5wireup (ADR-0116 §G16) added the optional [modeKey] field so the
 * Annihilation dispatch wiring in `AsteraMatchPlugin` can pick the right
 * `IGameMode` instead of hard-coding Norma. Existing callers that don't
 * supply a key keep working — the field defaults to `null` which the
 * wiring layer treats as "fall back to the registered default mode (Norma
 * in Phase 1 PoC)".
 *
 * String-typed rather than `GameModeId` because `GameModeId` lives in
 * `:plugin:match` and core events must stay vendor-neutral across plugin
 * boundaries (same rationale as [MatchFormed.modeKey]).
 */
public data class MatchStarted(
    val matchId: MatchId,
    val stageId: StageId,
    val atTick: Long,
    val modeKey: String? = null,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
