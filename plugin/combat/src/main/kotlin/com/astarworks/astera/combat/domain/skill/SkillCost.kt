package com.astarworks.astera.combat.domain.skill

import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.effect.vfx.VfxId

/** Saturating structural lifetime operation estimate. Population uses an explicit cap or the configured crowd assumption. */
public object SkillCost {
    public fun add(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    public fun multiply(a: Long, b: Long): Long = when {
        a == 0L || b == 0L -> 0
        a > Long.MAX_VALUE / b -> Long.MAX_VALUE
        else -> a * b
    }

    public fun estimate(
        phases: PhaseMechanics,
        params: SkillParams,
        maxTargets: Int? = null,
        assumedCrowdSize: Int = 16,
        skill: (SkillId) -> Long = { Long.MAX_VALUE },
        vfx: (VfxId) -> Long = { Long.MAX_VALUE },
        particle: (com.astarworks.astera.core.domain.effect.ParticleId, Long) -> Long = { _, _ -> Long.MAX_VALUE },
    ): Long {
        require(assumedCrowdSize > 0)
        val population = (maxTargets ?: assumedCrowdSize).toLong()
        fun count(value: IntValue): Long = (value as? IntValue.Literal)?.value?.coerceAtLeast(0)?.toLong() ?: Long.MAX_VALUE
        fun targets(t: Targeter): Long = when (t) {
            is Targeter.ForwardRay, is Targeter.NearbyEntities, is Targeter.Cone, is Targeter.Enemies, is Targeter.Allies,
            is Targeter.Filter, is Targeter.Random -> population
            else -> 1
        }
        fun condition(c: com.astarworks.astera.core.domain.skill.condition.Condition?): Long = when (c) {
            null -> 0
            is com.astarworks.astera.core.domain.skill.condition.Condition.Cel -> Long.MAX_VALUE
            is com.astarworks.astera.core.domain.skill.condition.Condition.And -> add(condition(c.left), condition(c.right))
            is com.astarworks.astera.core.domain.skill.condition.Condition.Or -> add(condition(c.left), condition(c.right))
            is com.astarworks.astera.core.domain.skill.condition.Condition.Not -> condition(c.inner)
            else -> multiply(32, multiply(population, population))
        }
        fun list(mechanics: List<Mechanic>): Long {
            var total = 0L
            for (m in mechanics) {
                val period = count(m.periodTicks)
                val duration = count(m.durationTicks)
                val firings = when {
                    period == 0L -> 1L
                    period == Long.MAX_VALUE || duration == 0L || duration == Long.MAX_VALUE -> Long.MAX_VALUE
                    else -> add(1, duration / period)
                }
                val work = when (m) {
                    is Mechanic.Loop -> multiply(count(m.count), list(m.mechanics))
                    is Mechanic.IfThen -> maxOf(list(m.then_), list(m.else_))
                    is Mechanic.Choose -> m.weighted.maxOf { list(it.mechanics) }
                    is Mechanic.ForEachTarget -> list(m.mechanics)
                    is Mechanic.Counter -> list(m.onHit)
                    is Mechanic.DashStrike -> multiply(count(m.ticks), multiply(population, add(16, list(m.onHit))))
                    is Mechanic.CastSkill -> skill(m.skillId)
                    is Mechanic.PlayVfx -> vfx(m.vfxId)
                    is Mechanic.SpawnParticle -> particle(m.particleId, count(m.count).coerceAtLeast(1))
                    is Mechanic.ScheduleParticleAnimation -> m.animation.shapes.fold(0L) { sum, shape ->
                        val points = when (shape) {
                            is com.astarworks.astera.core.domain.effect.animation.AnimatedShape.Point -> 1L
                            is com.astarworks.astera.core.domain.effect.animation.AnimatedShape.Circle -> multiply(shape.periodTicks.toLong(), shape.acceleration.toLong())
                            is com.astarworks.astera.core.domain.effect.animation.AnimatedShape.Ring -> multiply(shape.periodTicks.toLong(), shape.points.toLong())
                            is com.astarworks.astera.core.domain.effect.animation.AnimatedShape.Line -> add(1, multiply(shape.segments.toLong(), shape.pointsPerSegment.toLong()))
                        }
                        val emissions = shape.particles.fold(0L) { n, p -> add(n, p.count.coerceAtLeast(1).toLong()) }
                        add(sum, multiply(32, multiply(shape.amount.toLong(), multiply(points, emissions))))
                    }
                    is Mechanic.Explode, is Mechanic.Summon, is Mechanic.SummonLightning -> Long.MAX_VALUE // dependent fan-out
                    else -> 1L
                }
                total = add(total, multiply(firings, add(condition(m.condition), multiply(targets(m.targets), add(1, work)))))
            }
            return total
        }
        val groups = when (phases) {
            is PhaseMechanics.Projectile -> listOf(phases.cast, phases.hitEntity, phases.hitBlock, phases.miss, phases.expire)
            is PhaseMechanics.Melee -> listOf(phases.cast, phases.hitEntity, phases.miss)
            is PhaseMechanics.Aoe -> listOf(phases.cast, phases.hitEntity, phases.expire)
            is PhaseMechanics.Buff -> listOf(phases.cast, phases.expire, phases.removed)
            is PhaseMechanics.Teleport -> listOf(phases.cast, phases.preTeleport, phases.postTeleport)
            is PhaseMechanics.Summon -> listOf(phases.cast, phases.spawn, phases.summonDeath)
            is PhaseMechanics.Sequence -> listOf(phases.cast)
        }
        val mechanics = when (phases) {
            is PhaseMechanics.Melee -> add(list(phases.cast), add(multiply(population, list(phases.hitEntity)), list(phases.miss)))
            is PhaseMechanics.Aoe -> add(list(phases.cast), add(multiply(population, list(phases.hitEntity)), list(phases.expire)))
            else -> groups.fold(0L) { sum, group -> add(sum, list(group)) }
        }
        val baseline = when (params) {
            is SkillParams.MeleeParams, is SkillParams.AoeParams -> multiply(16, population)
            is SkillParams.ProjectileParams -> multiply(params.maxLifetimeTicks.toLong(), 16)
            else -> 1L
        }
        return add(baseline, mechanics)
    }
}
