package com.astarworks.astera.core.application.port.outbound.mob

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.mob.boss.BossError
import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.boss.BossSpec
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId
import com.astarworks.astera.core.domain.mob.raid.RaidClaim
import com.astarworks.astera.core.domain.mob.runtime.MobId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: orchestrates the boss instance lifecycle (Wave 5pve-boss,
 * ADR-0098).
 *
 * Responsibilities:
 *  - Spawn a boss instance (mints [MobId], delegates entity spawn to
 *    `IMobSpawner` via the [BossSpec.baseMobType], publishes [com.astarworks.astera.core.domain.event.BossSpawned]).
 *  - Track phase state per live instance + evaluate [com.astarworks.astera.core.domain.mob.boss.BossPhaseTransition]s
 *    when HP fraction changes (publishes [com.astarworks.astera.core.domain.event.BossPhaseChanged]).
 *  - Enforce [com.astarworks.astera.core.domain.mob.raid.RaidLockPolicy]
 *    before allowing player damage.
 *  - Resolve death — publishes [com.astarworks.astera.core.domain.event.BossDefeated]
 *    + writes the V30 `boss_kills` audit row (if [BossSpec.recordKill]) +
 *    triggers loot via `ILootRollService` + `IPveLootDistributor`.
 *
 * Phase 1 PoC: in-memory state only. Wave 6b Paper subclass overlays the
 * actual entity-side HP tracking (the Phase 1 InMemory impl exposes
 * `onDamage` for test-driven phase transitions).
 *
 * # Why a service-shaped port, not a use case
 *
 * Boss lifecycle is a long-lived multi-step orchestration (spawn → claim →
 * phase × N → death → loot → audit). Modelling each step as a separate
 * use case would scatter the state across half a dozen classes. The
 * service surface keeps the orchestration as one cohesive responsibility
 * while the underlying use cases (`SpawnMob`, `RollLoot`, `WriteAudit`)
 * remain composable.
 */
public interface IBossLifecycleService {

    /**
     * Register a boss spec so it can be looked up by id. The Phase 1 in-memory
     * impl keeps a `Map<BossId, BossSpec>`; Phase 4 will likely fold this
     * into a YAML registry like [IMobRegistry].
     */
    public fun registerSpec(spec: BossSpec)

    /** Look up a previously registered spec, or null. */
    public fun specOf(bossId: BossId): BossSpec?

    /**
     * Spawn a boss at [position] in [world]. Resolves [bossId] via the
     * registered specs + delegates underlying entity spawn to `IMobSpawner`
     * (using [BossSpec.baseMobType]). Publishes [com.astarworks.astera.core.domain.event.BossSpawned].
     *
     * @param zoneId Optional encounter zone the boss is anchored to.
     *   Carried into [com.astarworks.astera.core.domain.event.BossSpawned]
     *   for downstream subscribers (zone-state tracking, "boss is up"
     *   announcer).
     */
    public fun spawn(
        bossId: BossId,
        world: WorldId,
        position: Vec3,
        zoneId: EncounterZoneId? = null,
    ): Result<BossInstanceHandle, BossError>

    /**
     * Look up the live instance state, or null if no boss with [mobId] is
     * active. Read-only inspection — mutation goes through [onDamage] /
     * [onDeath].
     */
    public fun instanceOf(mobId: MobId): BossInstance?

    /**
     * Apply a damage hit from [attacker] to the boss instance. The service:
     *  1. checks [com.astarworks.astera.core.domain.mob.raid.RaidLockPolicy]
     *     (rejects with [BossError.RaidLocked] / [BossError.PartyRequired]
     *     if blocked)
     *  2. records the [RaidClaim] if this is the first hit
     *  3. re-evaluates [com.astarworks.astera.core.domain.mob.boss.BossPhaseTransition]s
     *     against the new HP fraction (publishes [com.astarworks.astera.core.domain.event.BossPhaseChanged]
     *     if a phase change triggered)
     *
     * The damage **amount** is opaque to the service — caller computed it
     * via the regular combat pipeline. Service only needs the new
     * `hpFraction` (currentHp / maxHp) to re-evaluate transitions.
     *
     * @return [Result.Success] with the updated [BossInstance] on success;
     *   [BossError] on raid-lock rejection.
     */
    public fun onDamage(
        mobId: MobId,
        attacker: PlayerId,
        partyId: PartyId?,
        newHpFraction: Double,
        atTick: Long,
    ): Result<BossInstance, BossError>

    /**
     * Resolve a boss death. Publishes [com.astarworks.astera.core.domain.event.BossDefeated]
     * + writes V30 audit row (via `IBossKillAuditWriter`) + triggers loot
     * roll. Returns the final [BossInstance] snapshot.
     */
    public fun onDeath(
        mobId: MobId,
        killerId: PlayerId?,
        atTick: Long,
    ): Result<BossInstance, BossError>
}

/**
 * Read-only snapshot of a live boss instance returned by [IBossLifecycleService].
 *
 * Tracks phase + raid claim + tick bookkeeping. Mutation happens via the
 * service's [IBossLifecycleService.onDamage] / [IBossLifecycleService.onDeath]
 * — direct mutation of this record is not supported (the value class is a
 * snapshot).
 */
public data class BossInstance(
    public val mobId: MobId,
    public val handle: EntityHandle,
    public val bossId: BossId,
    public val zoneId: EncounterZoneId?,
    public val world: WorldId,
    public val spawnedAtTick: Long,
    public val currentPhase: Int,
    public val raidClaim: RaidClaim?,
    public val hpFraction: Double,
) {
    init {
        require(currentPhase >= 0) {
            "BossInstance.currentPhase must be >= 0: $currentPhase"
        }
        require(hpFraction.isFinite() && hpFraction in 0.0..1.0) {
            "BossInstance.hpFraction must be finite and in [0.0, 1.0]: $hpFraction"
        }
        require(spawnedAtTick >= 0L) {
            "BossInstance.spawnedAtTick must be >= 0: $spawnedAtTick"
        }
    }

    /** True iff this boss has been claimed by a party (raid-locked). */
    public val isClaimed: Boolean get() = raidClaim != null

    /** Convenience: party currently owning the boss (null = unclaimed / open). */
    public val owningParty: PartyId? get() = raidClaim?.partyId
}

/**
 * Returned by [IBossLifecycleService.spawn] on success. Carries everything a
 * caller needs to interact with the instance afterward (mob id for service
 * lookups, entity handle for adapter-level operations).
 */
public data class BossInstanceHandle(
    public val mobId: MobId,
    public val entityHandle: EntityHandle,
    public val bossId: BossId,
)
