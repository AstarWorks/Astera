package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.match.domain.generator.GeneratorId
import java.time.Instant

/**
 * Stars entered the map from a generator or a Mid wave (after the supply formula, `StarSupply`). [team] is set when
 * only that team can pick them up. Kill bursts are not supply. The measurable source of the star pace.
 */
public data class StarSupplied(
    val matchId: MatchId,
    val generatorId: GeneratorId,
    val amount: Int,
    val team: TeamId?,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
