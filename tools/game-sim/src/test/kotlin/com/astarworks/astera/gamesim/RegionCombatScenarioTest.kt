package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.contents.SkillContents
import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.SkillId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** Real content + production combat wiring; no clock advance is allowed between input and assertions. */
class RegionCombatScenarioTest {
    @Test
    fun `uncapped shipped melee damages the whole crowd in the input tick`() {
        scenario("region-combat-unlimited-inline") {
            val caster = player("caster", team = "red")
            val crowd = (1..3).map { n -> player("victim$n", Vec3(0.0, groundY, 1.0 + n * 0.5), team = "blue") }
            val before = crowd.associate { it.id to hp(it) }
            val tick = sim.tick
            val hits = mutableListOf<DamageDealt>()
            val subscription = sim.eventBus.subscribe(DamageDealt::class) { hits += it }
            try {
                sim.cast(caster, "sword-slash")
                assertThat(sim.tick).isEqualTo(tick)
                assertThat(hits.map { it.target }).containsExactlyElementsOf(crowd.map { it.id })
                assertThat(hits.map { it.atTick }).containsOnly(tick)
                for (victim in crowd) {
                    val hit = hits.single { it.target == victim.id }
                    assertThat(hp(victim)).isEqualTo(before.getValue(victim.id) - hit.resolved.finalAmount)
                    assertThat(hit.resolved.finalAmount).isIn(8.0, 16.0)
                }
            } finally { subscription.cancel() }
        }
    }

    @Test
    fun `real projectile motion contacts and damage match with inline one and four workers`() {
        data class Frame(val hp: Double?, val positions: List<Vec3>)
        data class Run(val frames: List<Frame>, val hits: List<Pair<Long, Double>>)
        fun run(parallelism: Int): Run {
            val sim = GameSim.boot("region-projectiles-pool-$parallelism", projectileCompute =
                com.astarworks.astera.combat.application.service.CombatComputePool(threshold = 4, chunkSize = 2, parallelism = parallelism))
            val frames = mutableListOf<Frame>()
            val hits = mutableListOf<Pair<Long, Double>>()
            try {
                val caster = sim.spawnPlayer("caster", team = com.astarworks.astera.core.domain.match.TeamId("red"))
                val victim = sim.spawnPlayer("victim", Vec3(0.0, sim.world.groundY, 12.0), com.astarworks.astera.core.domain.match.TeamId("blue"))
                sim.setMaxHealth(victim, 1000.0)
                val registry = sim.services.getOrThrow<ISkillRegistry>() as SkillContents
                val original = checkNotNull(registry.find(SkillId("pistol-shot")))
                registry.register(original.copy(cooldownTicks = 0, resourceCost = 0))
                val subscription = sim.eventBus.subscribe(DamageDealt::class) { hits += it.atTick to it.resolved.finalAmount }
                repeat(9) { sim.cast(caster, "pistol-shot") }
                assertThat(sim.projectiles.inFlight).isEqualTo(9)
                repeat(40) {
                    sim.step()
                    frames += Frame(sim.hp(victim), sim.projectiles.snapshots().map { it.position })
                }
                subscription.cancel()
                assertThat(hits).isNotEmpty()
                assertThat(sim.projectiles.inFlight).isZero()
                return Run(frames, hits)
            } finally { sim.writeTrace(); sim.shutdown() }
        }
        val inline = run(0)
        assertThat(run(1)).isEqualTo(inline)
        assertThat(run(4)).isEqualTo(inline)
    }

    @Test
    fun `max_targets decoded by the real loader limits melee and leaves other targets untouched`() {
        val directory = Files.createTempDirectory("astera-capped-melee")
        try {
            val source = Files.readString(GameSim.defaultContentDir().resolve("skills/sword-slash.yaml"))
            Files.writeString(directory.resolve("sword-slash.yaml"), source + "\nmax_targets: 2\n")
            val loaded = SkillContents()
            val report = loaded.bootstrap(directory)
            assertThat(report.failed).isZero()
            val capped = checkNotNull(loaded.find(SkillId("sword-slash")))
            assertThat(capped.maxTargets).isEqualTo(2)
            scenario("region-combat-capped-inline") {
                val registry = sim.services.getOrThrow<ISkillRegistry>() as SkillContents
                assertThat(registry.find(capped.id)?.maxTargets).isNull()
                registry.register(capped)
                val caster = player("caster", team = "red")
                val crowd = (1..3).map { n -> player("victim$n", Vec3(0.0, groundY, 1.0 + n * 0.5), team = "blue") }
                val before = crowd.associate { it.id to hp(it) }
                val tick = sim.tick
                sim.cast(caster, "sword-slash")
                assertThat(sim.tick).isEqualTo(tick)
                for (victim in crowd.take(2)) assertThat(hp(victim)).isLessThanOrEqualTo(before.getValue(victim.id) - 8.0)
                assertThat(hp(crowd.last())).isEqualTo(before.getValue(crowd.last().id))
            }
        } finally {
            Files.deleteIfExists(directory.resolve("sword-slash.yaml"))
            Files.deleteIfExists(directory)
        }
    }
}
