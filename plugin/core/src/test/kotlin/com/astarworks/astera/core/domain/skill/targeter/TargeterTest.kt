package com.astarworks.astera.core.domain.skill.targeter

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.skill.value.VectorValue
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TargeterTest {

    @Test
    fun `singleton targeters are distinct objects and stable`() {
        // Five object variants
        val all: List<Targeter> = listOf(
            Targeter.Caster,
            Targeter.Target,
            Targeter.Origin,
            Targeter.HitLocation,
            Targeter.ProjectilePosition,
        )
        // Each is referentially stable
        assertThat(Targeter.Caster).isSameAs(Targeter.Caster)
        assertThat(Targeter.Target).isSameAs(Targeter.Target)
        assertThat(Targeter.Origin).isSameAs(Targeter.Origin)
        assertThat(Targeter.HitLocation).isSameAs(Targeter.HitLocation)
        assertThat(Targeter.ProjectilePosition).isSameAs(Targeter.ProjectilePosition)
        // And distinct from each other
        assertThat(all.toSet()).hasSize(5)
    }

    @Test
    fun `offset variants carry their VectorValue`() {
        val v = VectorValue.Literal(Vec3(1.0, 0.0, 0.0))
        assertThat(Targeter.CasterOffset(v).offset).isEqualTo(v)
        assertThat(Targeter.TargetOffset(v).offset).isEqualTo(v)
        assertThat(Targeter.ProjectileOffset(v).offset).isEqualTo(v)
        assertThat(Targeter.HitOffset(v).offset).isEqualTo(v)
    }

    @Test
    fun `NearbyEntities applies defaults`() {
        val t = Targeter.NearbyEntities(
            center = Targeter.Caster,
            radius = NumericValue.Literal(5.0),
        )
        assertThat(t.center).isEqualTo(Targeter.Caster)
        assertThat(t.radius).isEqualTo(NumericValue.Literal(5.0))
        assertThat(t.maxCount).isEqualTo(IntValue.Literal(20))
        assertThat(t.excludeCaster).isTrue()
    }

    @Test
    fun `NearbyEntities accepts custom maxCount and excludeCaster=false`() {
        val t = Targeter.NearbyEntities(
            center = Targeter.Target,
            radius = NumericValue.Literal(10.0),
            maxCount = IntValue.Literal(5),
            excludeCaster = false,
        )
        assertThat(t.maxCount).isEqualTo(IntValue.Literal(5))
        assertThat(t.excludeCaster).isFalse()
    }

    @Test
    fun `ForwardRay carries from and distance`() {
        val t = Targeter.ForwardRay(Targeter.Caster, NumericValue.Literal(8.0))
        assertThat(t.from).isEqualTo(Targeter.Caster)
        assertThat(t.distance).isEqualTo(NumericValue.Literal(8.0))
    }

    @Test
    fun `Cone carries from direction angle and distance`() {
        val t = Targeter.Cone(
            from = Targeter.Caster,
            direction = DirectionRef.FromCaster,
            angleDegrees = NumericValue.Literal(60.0),
            distance = NumericValue.Literal(4.0),
        )
        assertThat(t.from).isEqualTo(Targeter.Caster)
        assertThat(t.direction).isSameAs(DirectionRef.FromCaster)
        assertThat(t.angleDegrees).isEqualTo(NumericValue.Literal(60.0))
        assertThat(t.distance).isEqualTo(NumericValue.Literal(4.0))
    }

    @Test
    fun `Enemies and Allies carry center and includeSelf default`() {
        val enemies = Targeter.Enemies(Targeter.Caster)
        assertThat(enemies.center).isEqualTo(Targeter.Caster)

        val allies = Targeter.Allies(Targeter.Caster)
        assertThat(allies.center).isEqualTo(Targeter.Caster)
        assertThat(allies.includeSelf).isFalse()

        val alliesWithSelf = Targeter.Allies(Targeter.Caster, includeSelf = true)
        assertThat(alliesWithSelf.includeSelf).isTrue()
    }

    @Test
    fun `Filter wraps a base targeter with a condition`() {
        val cond = Condition.HealthBelow(Targeter.Target, NumericValue.Literal(0.5))
        val base = Targeter.NearbyEntities(Targeter.Caster, NumericValue.Literal(5.0))
        val filtered = Targeter.Filter(base, cond)
        assertThat(filtered.base).isEqualTo(base)
        assertThat(filtered.condition).isEqualTo(cond)
    }

    @Test
    fun `Random carries from and count`() {
        val from = Targeter.NearbyEntities(Targeter.Caster, NumericValue.Literal(10.0))
        val r = Targeter.Random(from, IntValue.Literal(3))
        assertThat(r.from).isEqualTo(from)
        assertThat(r.count).isEqualTo(IntValue.Literal(3))
    }

    @Test
    fun `DirectionRef variants are distinct singletons and Custom wraps VectorValue`() {
        assertThat(DirectionRef.FromCaster).isSameAs(DirectionRef.FromCaster)
        assertThat(DirectionRef.FromHitNormal).isSameAs(DirectionRef.FromHitNormal)
        assertThat(DirectionRef.Up).isSameAs(DirectionRef.Up)

        val singletons: Set<DirectionRef> = setOf(
            DirectionRef.FromCaster, DirectionRef.FromHitNormal, DirectionRef.Up,
        )
        assertThat(singletons).hasSize(3)

        val v = VectorValue.Literal(Vec3(0.0, 1.0, 0.0))
        assertThat(DirectionRef.Custom(v).vector).isEqualTo(v)
    }

    @Test
    fun `LocationRef variants compose Offset`() {
        assertThat(LocationRef.Caster).isSameAs(LocationRef.Caster)
        assertThat(LocationRef.Target).isSameAs(LocationRef.Target)
        assertThat(LocationRef.Origin).isSameAs(LocationRef.Origin)

        val world = WorldId("overworld")
        val abs = LocationRef.Absolute(world, Vec3(10.0, 64.0, -5.0))
        assertThat(abs.world).isEqualTo(world)
        assertThat(abs.position).isEqualTo(Vec3(10.0, 64.0, -5.0))

        val offset = LocationRef.Offset(LocationRef.Caster, VectorValue.Literal(Vec3(0.0, 2.0, 0.0)))
        assertThat(offset.from).isSameAs(LocationRef.Caster)
        assertThat(offset.delta).isEqualTo(VectorValue.Literal(Vec3(0.0, 2.0, 0.0)))
    }
}
