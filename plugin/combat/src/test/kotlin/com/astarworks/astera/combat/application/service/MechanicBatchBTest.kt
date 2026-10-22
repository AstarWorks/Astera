package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IBlockEditGuard
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.combat.application.port.outbound.ISummonGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.IExplosionGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.ILightningGateway
import com.astarworks.astera.combat.domain.aoe.BlockDamagePolicy
import com.astarworks.astera.combat.domain.aoe.ExplosionError
import com.astarworks.astera.combat.domain.aoe.ExplosionResult
import com.astarworks.astera.combat.domain.aoe.ExplosionSpec
import com.astarworks.astera.combat.domain.aoe.LightningError
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.testfixtures.FakeMatchTeamLookup
import com.astarworks.astera.combat.testfixtures.FakeNearbyTargetQuery
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.BoolValue
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerHealthGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP5: mechanics batch B (explode, lightning, blocks, display, summon, heal, glow, camera shake). */
class MechanicBatchBTest {

    private val caster = PlayerId(UUID.randomUUID())
    private val victim = PlayerId(UUID.randomUUID())
    private val world = WorldId("arena")
    private val origin = Vec3(10.0, 64.0, 10.0)

    private val scheduler = FakeScheduler()
    private val worldGateway = FakeWorldGateway()
    private val teams = FakeMatchTeamLookup()
    private val health = FakePlayerHealthGateway()
    private val explosion = RecordingExplosion()
    private val lightning = RecordingLightning()
    private val displays = RecordingDisplays()
    private val summons = RecordingSummons()
    private val effects = RecordingEffects()
    private val feedback = RecordingFeedback()
    private val registry = SummonRegistry { teams }
    private var arena = true

    private fun dispatcher() = MechanicDispatcher(
        applyDamage = null,
        applyStatus = null,
        worldGateway = worldGateway,
        knockbackGateway = null,
        nearbyQuery = FakeNearbyTargetQuery(listOf(victim)),
        broadcaster = null,
        celEvaluator = StubCelEvaluator(),
        scheduler = scheduler,
        eventBus = FakeDomainEventBus(),
        teamLookup = teams,
        entityEffects = effects,
        feedback = feedback,
        messageRenderer = FakeMessageRenderer(),
        playerHealth = { health },
        entityHealth = { null },
        explosionGateway = { explosion },
        lightningGateway = { lightning },
        blockEditGuard = IBlockEditGuard { _, _, _ -> arena },
        displayGateway = displays,
        summonGateway = summons,
        summonRegistry = registry,
    )

    private val dispatcher = dispatcher()

    private fun ctx() = MechanicContext(actorId = caster, targetId = victim, origin = origin, worldId = world)

    private fun run(vararg mechanics: Mechanic) = dispatcher.dispatch(mechanics.toList(), ctx())

    private fun lit(v: Double) = NumericValue.Literal(v)
    private fun lit(v: Int) = IntValue.Literal(v)
    private val here = Vec3(10.0, 64.0, 10.0)

    // ---- Explode -----------------------------------------------------------

    @Test
    fun `explode goes through the explosion gateway with the skill as source and no terrain damage by default`() {
        run(
            Mechanic.Explode(
                Targeter.Caster,
                power = lit(3.0),
                components = listOf(DamageComponent(NumericValue.Literal(12.0), DamageAttribute.FIRE)),
            ),
        )

        val spec = explosion.specs.single()
        assertThat(spec.center).isEqualTo(origin)
        assertThat(spec.world).isEqualTo(world)
        assertThat(spec.radius).isEqualTo(3.0)
        assertThat(spec.damageProfile.base).isEqualTo(12.0)
        assertThat(spec.blockDamage).isEqualTo(BlockDamagePolicy.None)
        assertThat(spec.source).isInstanceOf(DamageSource.Skill::class.java)
        assertThat((spec.source as DamageSource.Skill).casterId).isEqualTo(caster)
    }

    @Test
    fun `explode uses the explicit radius and caps absurd ones`() {
        run(Mechanic.Explode(Targeter.Caster, power = lit(2.0), radius = lit(6.0)))
        run(Mechanic.Explode(Targeter.Caster, power = lit(2.0), radius = lit(500.0)))

        assertThat(explosion.specs.map { it.radius }).containsExactly(6.0, WorldMechanics.MAX_EXPLOSION_RADIUS)
    }

    @Test
    fun `break_blocks only breaks terrain inside an arena`() {
        run(Mechanic.Explode(Targeter.Caster, power = lit(3.0), breakBlocks = BoolValue.Literal(true)))
        arena = false
        run(Mechanic.Explode(Targeter.Caster, power = lit(3.0), breakBlocks = BoolValue.Literal(true)))

        assertThat(explosion.specs[0].blockDamage).isInstanceOf(BlockDamagePolicy.Astera::class.java)
        assertThat(explosion.specs[1].blockDamage).isEqualTo(BlockDamagePolicy.None)
    }

    @Test
    fun `incendiary explosion sets the hit entities on fire`() {
        explosion.affected = listOf(victim to 6.0)

        run(Mechanic.Explode(Targeter.Caster, power = lit(3.0), incendiary = BoolValue.Literal(true)))

        assertThat(effects.fire[victim]).isGreaterThan(0)
    }

    // ---- SummonLightning ---------------------------------------------------

    @Test
    fun `lightning is visual only unless the skill says otherwise`() {
        run(Mechanic.SummonLightning(Targeter.Caster))
        run(Mechanic.SummonLightning(Targeter.Caster, visualOnly = BoolValue.Literal(false)))

        assertThat(lightning.strikes.map { it.third }).containsExactly(false, true)
        assertThat(lightning.strikes.first().first).isEqualTo(world)
    }

    // ---- SetBlock / RemoveBlock --------------------------------------------

    @Test
    fun `set_block places a temporary block that reverts after the delay`() {
        run(Mechanic.SetBlock(Targeter.Caster, "minecraft:ice", revertAfterTicks = lit(40)))

        assertThat(worldGateway.blockAt(world, here)).isEqualTo(BlockType("minecraft:ice"))
        scheduler.advanceTicks(39)
        assertThat(worldGateway.blockAt(world, here)).isEqualTo(BlockType("minecraft:ice"))
        scheduler.advanceTicks(1)
        assertThat(worldGateway.blockAt(world, here)).isNull()
    }

    @Test
    fun `set_block without a delay still reverts at the default`() {
        run(Mechanic.SetBlock(Targeter.Caster, "ice"))

        assertThat(worldGateway.blockAt(world, here)).isEqualTo(BlockType("minecraft:ice"))
        scheduler.advanceTicks(WorldMechanics.DEFAULT_REVERT_TICKS)
        assertThat(worldGateway.blockAt(world, here)).isNull()
    }

    @Test
    fun `set_block does nothing outside an arena unless outside_arena is set`() {
        arena = false
        run(Mechanic.SetBlock(Targeter.Caster, "minecraft:ice"))
        assertThat(worldGateway.placements).isEmpty()

        run(Mechanic.SetBlock(Targeter.Caster, "minecraft:ice", outsideArena = BoolValue.Literal(true)))
        assertThat(worldGateway.placements).hasSize(1)
    }

    @Test
    fun `set_block does not overwrite solid blocks or place protected ones`() {
        worldGateway.placeBlock(world, here, BlockType("minecraft:stone"))
        worldGateway.placements.clear()

        run(Mechanic.SetBlock(Targeter.Caster, "minecraft:ice"))
        run(Mechanic.SetBlock(Targeter.CasterOffset(com.astarworks.astera.core.domain.skill.value.VectorValue.Literal(Vec3(0.0, 1.0, 0.0))), "minecraft:bedrock"))
        run(Mechanic.SetBlock(Targeter.Caster, "astera:nexus_core"))

        assertThat(worldGateway.placements).isEmpty()
        assertThat(worldGateway.blockAt(world, here)).isEqualTo(BlockType("minecraft:stone"))
    }

    @Test
    fun `remove_block takes the block out and puts it back`() {
        worldGateway.placeBlock(world, here, BlockType("minecraft:stone"))

        run(Mechanic.RemoveBlock(Targeter.Caster, revertAfterTicks = lit(20)))
        assertThat(worldGateway.blockAt(world, here)).isNull()

        scheduler.advanceTicks(20)
        assertThat(worldGateway.blockAt(world, here)).isEqualTo(BlockType("minecraft:stone"))
    }

    @Test
    fun `remove_block never touches indestructible blocks or air`() {
        worldGateway.placeBlock(world, here, BlockType("minecraft:bedrock"))
        run(Mechanic.RemoveBlock(Targeter.Caster))
        assertThat(worldGateway.blockAt(world, here)).isEqualTo(BlockType("minecraft:bedrock"))

        worldGateway.placeBlock(world, here, BlockType("minecraft:chest"))
        run(Mechanic.RemoveBlock(Targeter.Caster))
        assertThat(worldGateway.blockAt(world, here)).isEqualTo(BlockType("minecraft:chest"))

        worldGateway.removals.clear()
        worldGateway.removeBlock(world, here)
        worldGateway.removals.clear()
        run(Mechanic.RemoveBlock(Targeter.Caster))
        assertThat(worldGateway.removals).isEmpty()
    }

    @Test
    fun `overlapping edits restore the very first original`() {
        worldGateway.placeBlock(world, here, BlockType("minecraft:dirt"))

        run(Mechanic.RemoveBlock(Targeter.Caster, revertAfterTicks = lit(20)))
        scheduler.advanceTicks(10)
        run(Mechanic.SetBlock(Targeter.Caster, "minecraft:ice", revertAfterTicks = lit(20)))
        assertThat(worldGateway.blockAt(world, here)).isEqualTo(BlockType("minecraft:ice"))

        // The first revert (tick 20) must not fire for the newer edit.
        scheduler.advanceTicks(10)
        assertThat(worldGateway.blockAt(world, here)).isEqualTo(BlockType("minecraft:ice"))
        scheduler.advanceTicks(10)
        assertThat(worldGateway.blockAt(world, here)).isEqualTo(BlockType("minecraft:dirt"))
    }

    @Test
    fun `shutdown undoes every pending temporary edit`() {
        worldGateway.placeBlock(world, here, BlockType("minecraft:dirt"))
        run(Mechanic.RemoveBlock(Targeter.Caster, revertAfterTicks = lit(1000)))
        run(Mechanic.SetBlock(Targeter.CasterOffset(com.astarworks.astera.core.domain.skill.value.VectorValue.Literal(Vec3(1.0, 0.0, 0.0))), "minecraft:ice", revertAfterTicks = lit(1000)))
        run(
            Mechanic.SpawnDisplay(
                Targeter.Caster,
                DisplaySpec(DisplayKind.Block("minecraft:glass"), lifetimeTicks = 1000L),
            ),
        )
        run(Mechanic.Summon(Targeter.Caster, "ZOMBIE", lifetimeTicks = lit(1000)))

        dispatcher.shutdown()

        assertThat(worldGateway.blockAt(world, here)).isEqualTo(BlockType("minecraft:dirt"))
        assertThat(worldGateway.blockAt(world, Vec3(11.0, 64.0, 10.0))).isNull()
        assertThat(displays.active).isEmpty()
        assertThat(summons.alive).isEmpty()
        assertThat(registry.all()).isEmpty()
    }

    // ---- SpawnDisplay ------------------------------------------------------

    @Test
    fun `display lives for its lifetime then is removed`() {
        run(Mechanic.SpawnDisplay(Targeter.Caster, DisplaySpec(DisplayKind.Block("minecraft:glass"), lifetimeTicks = 30L)))

        assertThat(displays.active).hasSize(1)
        scheduler.advanceTicks(29)
        assertThat(displays.active).hasSize(1)
        scheduler.advanceTicks(1)
        assertThat(displays.active).isEmpty()
    }

    @Test
    fun `text display is rendered for the caster`() {
        val text = DisplayKind.Text(listOf(LocalizedText(mapOf(LocaleCode.EN to "Boom"))))
        run(Mechanic.SpawnDisplay(Targeter.Caster, DisplaySpec(text, lifetimeTicks = 10L)))

        assertThat(displays.spawned.single().lines).containsExactly("Boom")
    }

    // ---- Summon ------------------------------------------------------------

    @Test
    fun `summon spawns owned mobs that expire`() {
        teams.assign(caster, TeamId("red"))

        run(Mechanic.Summon(Targeter.Caster, "ZOMBIE", count = lit(3), lifetimeTicks = lit(100)))

        assertThat(summons.alive).hasSize(3)
        assertThat(summons.requests.map { it.owner }.distinct()).containsExactly(caster)
        assertThat(summons.requests.map { it.team }.distinct()).containsExactly(TeamId("red"))
        assertThat(registry.summonsOf(caster)).hasSize(3)
        // A ring, not one stacked point.
        assertThat(summons.requests.map { it.position }.distinct()).hasSize(3)

        scheduler.advanceTicks(99)
        assertThat(summons.alive).hasSize(3)
        scheduler.advanceTicks(1)
        assertThat(summons.alive).isEmpty()
        assertThat(registry.summonsOf(caster)).isEmpty()
    }

    @Test
    fun `summon with no lifetime still expires at the default`() {
        run(Mechanic.Summon(Targeter.Caster, "ZOMBIE"))

        scheduler.advanceTicks(WorldMechanics.DEFAULT_SUMMON_LIFETIME_TICKS)
        assertThat(summons.alive).isEmpty()
    }

    @Test
    fun `summon is capped per owner and an unknown type is dropped`() {
        repeat(4) { run(Mechanic.Summon(Targeter.Caster, "ZOMBIE", count = lit(8))) }
        assertThat(registry.summonsOf(caster)).hasSize(WorldMechanics.MAX_ACTIVE_SUMMONS_PER_OWNER)

        summons.refuse = true
        val before = registry.all().size
        run(Mechanic.Summon(Targeter.Caster, "mythicmobs:imp"))
        assertThat(registry.all()).hasSize(before)
    }

    @Test
    fun `summons are friendly to their owner team and owner summons but not to enemies`() {
        val mate = PlayerId(UUID.randomUUID())
        val enemy = PlayerId(UUID.randomUUID())
        teams.assign(caster, TeamId("red"))
        teams.assign(mate, TeamId("red"))
        teams.assign(enemy, TeamId("blue"))
        run(Mechanic.Summon(Targeter.Caster, "ZOMBIE", count = lit(2)))
        val (a, b) = registry.summonsOf(caster)

        assertThat(registry.isFriendly(a, caster)).isTrue()
        assertThat(registry.isFriendly(a, mate)).isTrue()
        assertThat(registry.isFriendly(a, b)).isTrue()
        assertThat(registry.isFriendly(a, enemy)).isFalse()
        assertThat(registry.isFriendly(enemy, caster)).isFalse()

        // A summon of the enemy side is an enemy; one of the owner's side is not.
        val enemySummon = PlayerId(UUID.randomUUID())
        registry.register(enemySummon, enemy)
        assertThat(registry.isFriendly(a, enemySummon)).isFalse()
    }

    // ---- Heal --------------------------------------------------------------

    @Test
    fun `heal restores health to the targets through the health gateway`() {
        health.setMaxHealth(victim, 20.0)
        health.setHealth(victim, 5.0, HealthChangeSource.Other("test"))

        run(Mechanic.Heal(Targeter.Target, lit(8.0)))

        assertThat(health.getCurrentHealth(victim)).isEqualTo(13.0)
        val call = health.calls.filterIsInstance<FakePlayerHealthGateway.Call.Heal>().single()
        assertThat(call.source).isInstanceOf(HealthChangeSource.Skill::class.java)
    }

    @Test
    fun `heal is clamped to max and ignores zero or dead targets`() {
        health.setMaxHealth(victim, 20.0)
        health.setHealth(victim, 18.0, HealthChangeSource.Other("test"))
        run(Mechanic.Heal(Targeter.Target, lit(8.0)))
        assertThat(health.getCurrentHealth(victim)).isEqualTo(20.0)

        health.setHealth(victim, 0.0, HealthChangeSource.Other("test"))
        run(Mechanic.Heal(Targeter.Target, lit(8.0)))
        run(Mechanic.Heal(Targeter.Target, lit(0.0)))
        assertThat(health.getCurrentHealth(victim)).isEqualTo(0.0)
    }

    // ---- Glow / CameraShake ------------------------------------------------

    @Test
    fun `glow switches off after the duration and a refresh extends it`() {
        run(Mechanic.Glow(Targeter.Target, lit(40)))
        assertThat(effects.glowing[victim]).isTrue()

        scheduler.advanceTicks(20)
        run(Mechanic.Glow(Targeter.Target, lit(40)))
        scheduler.advanceTicks(20) // the first glow's end: still lit
        assertThat(effects.glowing[victim]).isTrue()
        scheduler.advanceTicks(20)
        assertThat(effects.glowing[victim]).isFalse()
    }

    @Test
    fun `camera shake pulses with decaying intensity for its duration`() {
        run(Mechanic.CameraShake(Targeter.Target, intensity = lit(1.0), ticks = lit(10)))
        scheduler.advanceTicks(10)

        val kicks = feedback.kicks.filter { it.first == victim }.map { it.second }
        assertThat(kicks).hasSize(5)
        assertThat(kicks.first()).isEqualTo(1.0)
        assertThat(kicks).isSortedAccordingTo(Comparator.reverseOrder())
        assertThat(kicks.last()).isLessThan(0.5)
    }

    // ---- support table -----------------------------------------------------

    @Test
    fun `batch B mechanics are no longer reported as unimplemented`() {
        val all = listOf(
            Mechanic.Heal(Targeter.Caster, lit(1.0)),
            Mechanic.Explode(Targeter.Caster, lit(1.0)),
            Mechanic.SummonLightning(Targeter.Caster),
            Mechanic.Summon(Targeter.Caster, "ZOMBIE"),
            Mechanic.SetBlock(Targeter.Caster, "minecraft:ice"),
            Mechanic.RemoveBlock(Targeter.Caster),
            Mechanic.SpawnDisplay(Targeter.Caster, DisplaySpec(DisplayKind.Block("minecraft:glass"), lifetimeTicks = 5L)),
            Mechanic.Glow(Targeter.Caster, lit(5)),
            Mechanic.CameraShake(Targeter.Caster, lit(0.5), lit(5)),
        )
        all.forEach { assertThat(MechanicSupport.problemsOf(it)).describedAs(it::class.simpleName).isEmpty() }
        assertThat(MechanicSupport.problemsOf(Mechanic.Resurrect(Targeter.Caster, lit(0.5)))).isNotEmpty()
    }

    // ---- fakes -------------------------------------------------------------

    private class RecordingExplosion : IExplosionGateway {
        val specs = mutableListOf<ExplosionSpec>()
        var affected: List<Pair<PlayerId, Double>> = emptyList()

        override fun trigger(spec: ExplosionSpec): Result<ExplosionResult, ExplosionError> {
            specs += spec
            return Result.success(ExplosionResult(affected, 0))
        }
    }

    private class RecordingLightning : ILightningGateway {
        val strikes = mutableListOf<Triple<WorldId, Vec3, Boolean>>()

        override fun strike(world: WorldId, position: Vec3, damageEntities: Boolean): Result<Unit, LightningError> {
            strikes += Triple(world, position, damageEntities)
            return Result.success(Unit)
        }
    }

    private class RecordingDisplays : IDisplayEntityGateway {
        data class Spawned(val spec: DisplaySpec, val lines: List<String>)

        val spawned = mutableListOf<Spawned>()
        val active = mutableSetOf<IDisplayEntityGateway.DisplayHandle>()

        override fun spawn(world: WorldId, at: Vec3, spec: DisplaySpec, renderedLines: List<String>): IDisplayEntityGateway.DisplayHandle {
            val handle = IDisplayEntityGateway.DisplayHandle(UUID.randomUUID())
            spawned += Spawned(spec, renderedLines)
            active += handle
            return handle
        }

        override fun remove(handle: IDisplayEntityGateway.DisplayHandle) {
            active -= handle
        }
    }

    private class RecordingSummons : ISummonGateway {
        val requests = mutableListOf<ISummonGateway.SummonRequest>()
        val alive = mutableSetOf<PlayerId>()
        var refuse = false

        override fun spawn(request: ISummonGateway.SummonRequest): PlayerId? {
            if (refuse) return null
            requests += request
            return PlayerId(UUID.randomUUID()).also { alive += it }
        }

        override fun despawn(id: PlayerId) {
            alive -= id
        }
    }

    private class RecordingEffects : IEntityEffectsGateway {
        val fire = mutableMapOf<PlayerId, Int>()
        val glowing = mutableMapOf<PlayerId, Boolean>()

        override fun setVelocity(target: PlayerId, velocity: Vec3) = Unit
        override fun addVelocity(target: PlayerId, delta: Vec3) = Unit
        override fun setFireTicks(target: PlayerId, ticks: Int) {
            fire[target] = ticks
        }

        override fun freeze(target: PlayerId, ticks: Int) = Unit
        override fun thaw(target: PlayerId) = Unit
        override fun setGlowing(target: PlayerId, glowing: Boolean) {
            this.glowing[target] = glowing
        }
    }

    private class RecordingFeedback : IPlayerFeedbackGateway {
        val kicks = mutableListOf<Pair<PlayerId, Double>>()

        override fun sendActionBar(target: PlayerId, rendered: String) = Unit
        override fun sendTitle(
            target: PlayerId,
            title: String,
            subtitle: String?,
            fadeInTicks: Int,
            stayTicks: Int,
            fadeOutTicks: Int,
        ) = Unit

        override fun cameraKick(target: PlayerId, intensity: Double) {
            kicks += target to intensity
        }
    }
}
