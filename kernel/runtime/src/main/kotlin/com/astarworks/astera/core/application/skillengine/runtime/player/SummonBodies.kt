package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.table.SummonBody
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID

/**
 * The platform's half of a summon body (ADR-0313 rev): a physical hitbox the world can aim at (Paper: a non-living interaction entity).
 * The kernel owns the body's hp and team rules; the gateway only gives it a presence and removes it again. [remove] is called exactly
 * once per spawned body (run end, break, reload, match end), so a gateway can keep a plain map of what it spawned.
 */
public interface ISkillBodyGateway {
    public fun spawn(body: UUID, world: WorldId, feet: Vec3, width: Double, height: Double)

    public fun remove(body: UUID)
}

/** No world presence: the body exists for the kernel's queries only (a platform without hitbox entities). */
public object NoBodyGateway : ISkillBodyGateway {
    override fun spawn(body: UUID, world: WorldId, feet: Vec3, width: Double, height: Double) {}

    override fun remove(body: UUID) {}
}

/** One live body: owned by [run] (its cast handle value), credited to [owner]. */
internal class SummonBodyRec(
    val id: UUID, val run: Long, val owner: UUID, val world: WorldId, val feet: Vec3, val spec: SummonBody, var hp: Double,
) {
    val box: HitBox get() = HitBox(Vec3(feet.x - spec.width / 2, feet.y, feet.z - spec.width / 2), Vec3(feet.x + spec.width / 2, feet.y + spec.height, feet.z + spec.width / 2))
    fun candidate(): HitCandidate = HitCandidate(id, feet, 0.0, box, spec.height * 0.9)
}

/** The registry of live summon bodies plus the decorators that make them visible to the entity query and the damage port. */
internal class SummonBodies(private val onBroken: (SummonBodyRec) -> Unit) {
    val live = LinkedHashMap<UUID, SummonBodyRec>()

    fun ownerOfBody(id: UUID): UUID? = live[id]?.owner

    private fun near(b: SummonBodyRec, point: Vec3, radius: Double): Boolean {
        val box = b.box
        val dx = maxOf(box.min.x - point.x, 0.0, point.x - box.max.x)
        val dy = maxOf(box.min.y - point.y, 0.0, point.y - box.max.y)
        val dz = maxOf(box.min.z - point.z, 0.0, point.z - box.max.z)
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }

    inner class Query(private val inner: ISkillEntityQuery) : ISkillEntityQuery by inner {
        private fun mine(cast: CastRef, point: Vec3, radius: Double, excluding: UUID?) =
            live.values.filter { it.world == cast.world && it.id != excluding && near(it, point, radius) }

        override fun nearby(cast: CastRef, point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate> =
            inner.nearby(cast, point, radius, excluding) + mine(cast, point, radius, excluding).map { it.candidate() }

        override fun bodiesNear(cast: CastRef, point: Vec3, radius: Double, excluding: UUID?): List<HitBox> =
            inner.bodiesNear(cast, point, radius, excluding) + mine(cast, point, radius, excluding).map { it.box }

        override fun enemyBodiesNear(cast: CastRef, point: Vec3, radius: Double): List<HitBox> =
            inner.enemyBodiesNear(cast, point, radius) + mine(cast, point, radius, null).filter { !isAlly(cast, cast.caster, it.id) }.map { it.box }

        override fun rootOf(cast: CastRef, entity: UUID): EntityRoot? {
            val b = live[entity] ?: return inner.rootOf(cast, entity)
            return EntityRoot(b.feet, 0.0, 0.0, b.spec.height * 0.9, b.spec.height)
        }

        override fun isAlly(cast: CastRef, a: UUID, b: UUID): Boolean {
            val ra = ownerOfBody(a) ?: a
            val rb = ownerOfBody(b) ?: b
            return ra == rb || inner.isAlly(cast, ra, rb)
        }
    }

    inner class Gameplay(private val inner: ISkillGameplayGateway, private val query: ISkillEntityQuery) : ISkillGameplayGateway by inner {
        /** Damage to a body: an ally of the owner changes nothing; an enemy lowers hp, and at 0 the body breaks (returns true = killed). */
        fun hit(attacker: UUID, body: SummonBodyRec, amount: Double): Boolean {
            if (body.id !in live) return false
            val cast = CastRef(-1L, body.owner, body.world, null)
            if (attacker == body.owner || query.isAlly(cast, attacker, body.owner)) return false
            body.hp -= amount
            if (body.hp > 0.0) return false
            live.remove(body.id)
            onBroken(body)
            return true
        }

        override fun damage(
            skillId: String, caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3,
            ignoreInvulnerability: Boolean, ignoreArmor: Boolean,
        ): Boolean {
            val body = live[victim] ?: return inner.damage(skillId, caster, victim, components, hitPoint, ignoreInvulnerability, ignoreArmor)
            return hit(caster, body, components.sumOf { it.amount })
        }

        override fun statusStacks(entity: UUID, statusId: String): Int = if (entity in live) 0 else inner.statusStacks(entity, statusId)

        override fun applyStatus(caster: UUID, victim: UUID, statusId: String, stacks: Int, durationTicks: Int) {
            if (victim !in live) inner.applyStatus(caster, victim, statusId, stacks, durationTicks)
        }

        override fun removeStatus(victim: UUID, statusId: String) {
            if (victim !in live) inner.removeStatus(victim, statusId)
        }

        override fun cleanse(victim: UUID, negativeOnly: Boolean) {
            if (victim !in live) inner.cleanse(victim, negativeOnly)
        }

        override fun ignite(caster: UUID, victim: UUID, durationTicks: Int) {
            if (victim !in live) inner.ignite(caster, victim, durationTicks)
        }

        override fun silence(caster: UUID, victim: UUID, durationTicks: Int) {
            if (victim !in live) inner.silence(caster, victim, durationTicks)
        }

        override fun modifyAttribute(caster: UUID, victim: UUID, attribute: String, operation: String, amount: Double, durationTicks: Int?) {
            if (victim !in live) inner.modifyAttribute(caster, victim, attribute, operation, amount, durationTicks)
        }

        override fun restoreResource(victim: UUID, resource: String, amount: Double) {
            if (victim !in live) inner.restoreResource(victim, resource, amount)
        }

        override fun impulse(victim: UUID, vector: Vec3) {
            if (victim !in live) inner.impulse(victim, vector)
        }

        override fun freezeEntities(entities: List<UUID>, ticks: Int) {
            inner.freezeEntities(entities.filter { it !in live }, ticks)
        }
    }
}
