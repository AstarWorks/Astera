package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.generator.GeneratorId
import java.time.Instant

/**
 * A [com.astarworks.astera.match.domain.generator.StarGenerator] just produced
 * a resource (Wave 5c).
 *
 * Published by `SpawnResourceUseCase` once a generator's
 * [com.astarworks.astera.match.domain.generator.GeneratorAttributes.ENERGY]
 * crosses its effective period. The amount carried here is the *post-multiplier*
 * value (base `amount` already scaled by
 * [com.astarworks.astera.match.domain.generator.GeneratorAttributes.AMOUNT_MULTIPLIER]).
 *
 * Downstream: Stats / Economy / HUD subscribers materialize the spawn —
 * spawning an item entity, crediting a wallet, animating an icon — through
 * their own listeners. The match domain itself stays free of MC bindings.
 */
public data class ResourceSpawned(
    val generatorId: GeneratorId,
    val matchId: MatchId,
    val position: Vec3,
    val currency: Currency,
    val amount: Int,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
