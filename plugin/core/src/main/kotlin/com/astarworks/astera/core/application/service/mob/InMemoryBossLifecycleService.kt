package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.mob.BossInstance
import com.astarworks.astera.core.application.port.outbound.mob.BossInstanceHandle
import com.astarworks.astera.core.application.port.outbound.mob.IBossKillAuditWriter
import com.astarworks.astera.core.application.port.outbound.mob.IBossLifecycleService
import com.astarworks.astera.core.application.port.outbound.mob.IMobSpawner
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.BossDefeated
import com.astarworks.astera.core.domain.event.BossPhaseChanged
import com.astarworks.astera.core.domain.event.BossSpawned
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.mob.boss.BossError
import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.boss.BossPhaseTransition
import com.astarworks.astera.core.domain.mob.boss.BossSpec
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId
import com.astarworks.astera.core.domain.mob.raid.RaidClaim
import com.astarworks.astera.core.domain.mob.raid.RaidLockPolicy
import com.astarworks.astera.core.domain.mob.runtime.MobId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Phase 1 default impl of [IBossLifecycleService] (Wave 5pve-boss, ADR-0098).
 *
 * Single-threaded in-memory orchestration:
 *  - Spawns delegate to [IMobSpawner] using [BossSpec.baseMobType].
 *  - Phase state is tracked in [instances] keyed by [MobId].
 *  - [onDamage] re-evaluates [BossPhaseTransition.HpThreshold] in declaration
 *    order; first match fires.
 *  - [onDeath] publishes [BossDefeated], writes audit row (if
 *    [BossSpec.recordKill]), and clears the instance.
 *
 * The InMemory impl does **not** call the loot roll service itself — that's
 * the responsibility of a subscriber to [BossDefeated] (the lifecycle
 * service stays focused on phase + claim + audit; loot is composed in the
 * plugin wiring layer). Tests can subscribe directly.
 *
 * # MinHp clamping
 *
 * `newHpFraction` is clamped to `[0.0, 1.0]` defensively even though the
 * domain record validates it — the lifecycle service is the boundary
 * between adapter and domain, and tolerating slight overshoot (e.g. 1.0001
 * from a damage formula rounding error) prevents spurious rejections.
 */
public class InMemoryBossLifecycleService(
    private val spawner: IMobSpawner,
    private val eventBus: IDomainEventBus,
    private val auditWriter: IBossKillAuditWriter,
) : IBossLifecycleService {

    private val specs: MutableMap<BossId, BossSpec> = mutableMapOf()
    private val instances: MutableMap<MobId, BossInstance> = mutableMapOf()

    override fun registerSpec(spec: BossSpec) {
        specs[spec.id] = spec
    }

    override fun specOf(bossId: BossId): BossSpec? = specs[bossId]

    override fun spawn(
        bossId: BossId,
        world: WorldId,
        position: Vec3,
        zoneId: EncounterZoneId?,
    ): Result<BossInstanceHandle, BossError> {
        val spec = specs[bossId] ?: return Result.failure(BossError.UnknownBoss(bossId))
        val entityResult = spawner.spawn(world, position, spec.baseMobType)
        val entityHandle: EntityHandle = when (entityResult) {
            is Result.Success -> entityResult.value
            is Result.Failure -> return Result.failure(
                BossError.BackendError("underlying mob spawn failed: ${entityResult.error}"),
            )
        }
        val mobId = MobId.of(entityHandle.id)
        val instance = BossInstance(
            mobId = mobId,
            handle = entityHandle,
            bossId = bossId,
            zoneId = zoneId,
            world = world,
            spawnedAtTick = 0L,
            currentPhase = spec.initialPhase.index,
            raidClaim = null,
            hpFraction = 1.0,
        )
        instances[mobId] = instance
        eventBus.publish(
            BossSpawned(
                mobId = mobId,
                handle = entityHandle,
                bossId = bossId,
                zoneId = zoneId,
                world = world,
                position = position,
                atTick = 0L,
            ),
        )
        return Result.success(
            BossInstanceHandle(
                mobId = mobId,
                entityHandle = entityHandle,
                bossId = bossId,
            ),
        )
    }

    override fun instanceOf(mobId: MobId): BossInstance? = instances[mobId]

    override fun onDamage(
        mobId: MobId,
        attacker: PlayerId,
        partyId: PartyId?,
        newHpFraction: Double,
        atTick: Long,
    ): Result<BossInstance, BossError> {
        val instance = instances[mobId] ?: return Result.failure(BossError.UnknownMob(mobId))
        val spec = specs[instance.bossId]
            ?: return Result.failure(BossError.UnknownBoss(instance.bossId))

        // 1. Raid-lock enforcement
        val raidCheck = checkRaidLock(spec, instance, attacker, partyId)
        if (raidCheck is Result.Failure) return raidCheck

        // 2. Claim if first hit + party-locked
        val claimed = applyClaim(instance, spec, attacker, partyId, atTick)

        // 3. Update HP fraction
        val clampedHp = newHpFraction.coerceIn(0.0, 1.0)

        // 4. Re-evaluate transitions.
        //
        // Phase 1 lifecycle evaluates only HpThreshold inline. Wave 5combat-
        // depth (ADR-0105) widens BossPhaseTransition with TimerExpired /
        // MultiPlayerThreshold / MechanicCompleted / AllOf; the lifecycle
        // service silently skips those until Wave 6b switches this loop to
        // BossPhaseTransitionEvaluator.fires(...). The non-HP cases are
        // intentionally a no-op here so existing HP-only specs keep firing
        // unchanged.
        var phase = claimed.currentPhase
        var firedTransition: BossPhaseTransition? = null
        for (t in spec.transitions) {
            if (t.fromPhase != phase) continue
            val fires = when (t) {
                is BossPhaseTransition.HpThreshold -> clampedHp <= t.hpFraction
                is BossPhaseTransition.TimerExpired -> false
                is BossPhaseTransition.MultiPlayerThreshold -> false
                is BossPhaseTransition.MechanicCompleted -> false
                is BossPhaseTransition.AllOf -> false
            }
            if (fires) {
                firedTransition = t
                phase = t.toPhase
                break
            }
        }

        val updated = claimed.copy(currentPhase = phase, hpFraction = clampedHp)
        instances[mobId] = updated

        if (firedTransition != null) {
            eventBus.publish(
                BossPhaseChanged(
                    mobId = mobId,
                    bossId = instance.bossId,
                    fromPhase = firedTransition.fromPhase,
                    toPhase = firedTransition.toPhase,
                    trigger = firedTransition,
                    atTick = atTick,
                ),
            )
        }
        return Result.success(updated)
    }

    override fun onDeath(
        mobId: MobId,
        killerId: PlayerId?,
        atTick: Long,
    ): Result<BossInstance, BossError> {
        val instance = instances[mobId] ?: return Result.failure(BossError.UnknownMob(mobId))
        val spec = specs[instance.bossId]
            ?: return Result.failure(BossError.UnknownBoss(instance.bossId))

        val fightDuration = (atTick - instance.spawnedAtTick).coerceAtLeast(0L)
        val event = BossDefeated(
            mobId = mobId,
            bossId = instance.bossId,
            killerId = killerId,
            partyId = instance.owningParty,
            fightDurationTicks = fightDuration,
            finalPhase = instance.currentPhase,
            atTick = atTick,
        )
        if (spec.recordKill) {
            auditWriter.record(event)
        }
        eventBus.publish(event)
        val finalInstance = instance.copy(hpFraction = 0.0)
        instances.remove(mobId)
        return Result.success(finalInstance)
    }

    /**
     * Test accessor — number of live boss instances. Production callers should
     * use [instanceOf] for individual lookup.
     */
    public fun activeInstanceCount(): Int = instances.size

    private fun checkRaidLock(
        spec: BossSpec,
        instance: BossInstance,
        attacker: PlayerId,
        partyId: PartyId?,
    ): Result<Unit, BossError> {
        return when (val policy = spec.raidLock) {
            is RaidLockPolicy.Open -> Result.success(Unit)
            is RaidLockPolicy.PartyLock -> {
                if (partyId == null) {
                    return Result.failure(BossError.PartyRequired(attacker))
                }
                val claim = instance.raidClaim
                if (claim != null && claim.partyId != partyId) {
                    return Result.failure(
                        BossError.RaidLocked(
                            playerId = attacker,
                            mobId = instance.mobId,
                            partyId = claim.partyId,
                        ),
                    )
                }
                // PartyLock.maxPartySize is a spec-level invariant; the
                // enforcement happens when the party itself is formed
                // (via `IPartyRepository` / `JoinPartyUseCase`), so we don't
                // re-check size here. `policy.maxPartySize` is captured by
                // the constructed PartyLock and is left as a domain-side
                // assertion (BossSpec construction validates the cap).
                @Suppress("UnusedVariable")
                val cap = policy.maxPartySize
                Result.success(Unit)
            }
        }
    }

    @Suppress("UNUSED_VARIABLE")
    private fun applyClaim(
        instance: BossInstance,
        spec: BossSpec,
        attacker: PlayerId,
        partyId: PartyId?,
        atTick: Long,
    ): BossInstance {
        if (spec.raidLock is RaidLockPolicy.Open) return instance
        if (instance.raidClaim != null) return instance
        if (partyId == null) return instance
        val claim = RaidClaim(
            mobId = instance.mobId,
            partyId = partyId,
            firstHitter = attacker,
            claimedAt = Instant.now(),
        )
        val claimed = instance.copy(raidClaim = claim)
        instances[instance.mobId] = claimed
        return claimed
    }
}
