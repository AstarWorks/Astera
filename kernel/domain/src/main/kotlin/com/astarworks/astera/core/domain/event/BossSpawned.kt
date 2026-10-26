package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId
import com.astarworks.astera.core.domain.mob.runtime.MobId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Published by `IBossLifecycleService` after a boss instance enters the
 * world (Wave 5pve-boss, ADR-0098).
 *
 * Distinct from [MobSpawned] — bosses are encounter-level entities with
 * raid-lock semantics + phase tracking, and subscribers (HUD raid bar,
 * announcer, audit) need the boss-specific [bossId] + [mobId] handles to
 * subscribe to subsequent [BossPhaseChanged] / [BossDefeated] events for
 * that instance.
 *
 * Subscribers:
 *  - HUD (Wave 5b / Wave 6b): show raid bossbar to nearby players.
 *  - Announcer: broadcast "<Boss> awakens in <Zone>".
 *  - Audit (V30 `boss_kills`): pre-write a "spawn" row to track time-to-kill.
 *  - Combat: register the boss handle as a damage target.
 *
 * @property mobId Runtime id for this specific boss instance (used as the
 *   audit join key).
 * @property handle Vendor-neutral entity handle minted by the underlying
 *   mob adapter (`MobBackendId` resolves to "minimal" in Phase 1).
 * @property bossId Registry id of the [com.astarworks.astera.core.domain.mob.boss.BossSpec].
 * @property zoneId Encounter zone this boss is anchored to (null if spawned
 *   ad-hoc via admin command).
 * @property world Target world id.
 * @property position Spawn position.
 * @property atTick Server tick at spawn time.
 */
public data class BossSpawned(
    public val mobId: MobId,
    public val handle: EntityHandle,
    public val bossId: BossId,
    public val zoneId: EncounterZoneId?,
    public val world: WorldId,
    public val position: Vec3,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
