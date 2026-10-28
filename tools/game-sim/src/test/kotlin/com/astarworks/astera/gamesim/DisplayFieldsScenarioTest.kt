package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.domain.effect.DisplayItemMode
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * WP6-E: the vanilla Display fields a skill writes, per tick, reproduced from v1.
 *  - element brightness: `Display.Brightness(n, n)` (v1 PaperDisplayEntityGateway.kt:229) on mesh and block;
 *  - item element item_model + display_mode: ItemStack with the itemModel override, `itemDisplayTransform` FIXED
 *    (PaperDisplayEntityGateway.kt:156-166, VfxDefinition.kt:254);
 *  - glow_color: `isGlowing = true` + `glowColorOverride = Color.fromARGB(argb)` (PaperDisplayEntityGateway.kt:233-236),
 *    `#RRGGBB` or `#AARRGGBB` (VfxYamlConfig.kt:160-163, parseArgb :1020);
 *  - jitter: one draw set per slot, constant over the slot's life (VfxTimeline.kt:176-215) = `rand(stream, k)`, not per tick;
 *  - particle along_path: ceil(dist / 0.4) points, at most 24 per tick, the last at the current position (VfxTimeline.kt:581-615).
 */
class DisplayFieldsScenarioTest {

    private fun flat(yaml: String) = yaml.lines().joinToString(" ") { it.trim() }

    private fun skill(rows: String): Pair<String, String> =
        "t-fields" to """
            apiVersion: "astera/v2"
            kind: Skill
            id: t-fields
            item: pistol
            meta: {name: {ja: "t", en: "t"}, description: {ja: "t", en: "t"}, cooldown_ticks: 1, resource_cost: 0, tags: []}
            frames: [{id: anchor, kind: body, parent: caster}, {id: slow, kind: body, parent: caster}, {id: fast, kind: body, parent: caster}]
            gameplay: {timeline: []}
            presentation: ${flat("{timeline: [$rows]}")}
        """.trimIndent()

    private fun jitterBlock(id: String) =
        "{id: $id, op: block, parent: anchor, block: stone, size: [0.2, 0.2, 0.2], brightness: 7, times: 3, " +
            "at: \"= vec((rand('j', 7.0*i+0.0)*2.0-1.0)*0.1, (rand('j', 7.0*i+1.0)*2.0-1.0)*0.1, (rand('j', 7.0*i+2.0)*2.0-1.0)*0.1)\"}"

    private val rows = listOf(
        "{id: r0, at: 0, do: [" +
            "{id: m, op: mesh, frame: anchor, item: couture-rapier, part: blade_a, brightness: 12, glow: \"#80FF2040\"}," +
            "{id: v, op: mesh, frame: anchor, item: \"minecraft:shears\"}," +
            "{id: vm, op: mesh, frame: anchor, item: \"minecraft:shears\", part: \"astera:couture-rapier-blade-b\"}," +
            "{id: qa, op: block, parent: anchor, block: dirt, size: [0.2, 0.2, 0.2], turn: \"= rotvec(quat(vec(0.0, 90.0, 0.0)) * quat_axis_angle(vec(1.0, 0.0, 0.0), 90.0))\"}," +
            "{id: qb, op: block, parent: anchor, block: sand, size: [0.2, 0.2, 0.2], turn: [0.0, 90.0, 0.0]}," +
            "{id: g, op: block, parent: anchor, block: glass, size: [0.2, 0.2, 0.2], glow: \"#00ff00\"}," +
            jitterBlock("jb0") + "," +
            "{id: t1, op: track, target: slow, space: caster, keys: [{t: 0, offset: [0, 1, 0]}, {t: 10, offset: [0, 1, 5]}]}," +
            "{id: t2, op: track, target: fast, space: caster, keys: [{t: 0, offset: [0, 1, 0]}, {t: 6, offset: [0, 1, 0]}, {t: 7, offset: [0, 1, 20]}]}]}",
        "{id: r5, at: 5, do: [" + jitterBlock("jb5") + "]}",
    ) + (0..9).map { t ->
        "{id: e$t, at: $t, do: [" +
            "{id: es$t, op: emit, at: slow, particle: hit-spark, spacing: 0.4, trace: s}," +
            "{id: ef$t, op: emit, at: fast, particle: hit-spark, spacing: 0.4, trace: f}]}"
    } + listOf(0, 2, 4, 6, 8).map { t ->
        "{id: w$t, at: $t, do: [{id: ew$t, op: emit, at: slow, particle: end-rod, spacing: 0.4, trace: w}]}"
    }

    private class Sink : ISkillParticleGateway {
        val bursts = ArrayList<Pair<Int, ParticleBurst>>()
        var now: () -> Int = { 0 }
        override fun emit(burst: ParticleBurst) { bursts += now() to burst }
    }

    private fun run(): Pair<RecordingVisuals, Sink> {
        lateinit var sim: CastSim
        val rec = RecordingVisuals({ sim.now }, { sim.casterFeet })
        val sink = Sink().also { it.now = { sim.now } }
        sim = CastSim(emptyMap(), origin = Vec3(10.0, 64.0, 10.0), visualGateway = rec, particleGateway = sink, extraSkills = mapOf(skill(rows.joinToString(","))))
        sim.cast("t-fields", 12)
        return rec to sink
    }

    @Test
    fun `item model, display mode, brightness and glow are written as vanilla fields`() {
        val (rec, _) = run()
        val items = rec.specs.values.filter { it.kind is DisplayKind.Item }
        val pack = items.single { (it.kind as DisplayKind.Item).itemModel != null && (it.kind as DisplayKind.Item).itemId.value == "paper" }
        val kind = pack.kind as DisplayKind.Item
        assertThat(kind.itemModel).isEqualTo("astera:couture-rapier-blade-a")
        assertThat(kind.displayMode).isEqualTo(DisplayItemMode.FIXED)
        assertThat(pack.brightness).isEqualTo(12)
        assertThat(pack.glowArgb).describedAs("#AARRGGBB, alpha first").isEqualTo(0x80FF2040.toInt())
        assertThat(pack.weaponRigShading).describedAs("an explicit brightness is an effect prop, not the held weapon").isFalse()
        val stacked = items.single { (it.kind as DisplayKind.Item).itemModel == "astera:couture-rapier-blade-b" }
        assertThat((stacked.kind as DisplayKind.Item).itemId.value).describedAs("v1 keeps its base material under item_model").isEqualTo("shears")
        val vanilla = items.single { (it.kind as DisplayKind.Item).itemModel == null }
        assertThat((vanilla.kind as DisplayKind.Item).itemId.value).isEqualTo("shears")
        assertThat(vanilla.glowArgb).isNull()
        val glass = rec.specs.values.single { (it.kind as? DisplayKind.Block)?.blockKey?.contains("glass") == true }
        assertThat(glass.glowArgb).describedAs("#RRGGBB is opaque").isEqualTo(0xFF00FF00.toInt())
        assertThat(glass.brightness).isNull()
    }

    @Test
    fun `jitter is constant per slot over time and differs between slots`() {
        val (rec, _) = run()
        val stone = rec.specs.filter { (it.value.kind as? DisplayKind.Block)?.blockKey?.contains("stone") == true }
        assertThat(stone.values.map { it.brightness }).describedAs("brightness 7 on all six jittered blocks").containsOnly(7)
        fun slots(tick: Int) = rec.calls.filter { it.spawn && it.tick == tick && it.handle in stone.keys }.sortedBy { it.handle }.map { it.write.at }
        val early = slots(0)
        val late = slots(5)
        assertThat(early).hasSize(3)
        assertThat(late).describedAs("same slot, same jitter, five ticks later").containsExactlyElementsOf(early)
        assertThat(early.map { it.x }.toSet()).describedAs("three slots, three different draws").hasSize(3)
        val centre = Vec3(early.sumOf { it.x } / 3, early.sumOf { it.y } / 3, early.sumOf { it.z } / 3)
        assertThat(early.maxOf { it.distance(centre) }).describedAs("within +-0.1 per axis of the slot position").isLessThan(0.1 * 2 * 1.8)
    }

    @Test
    fun `jitter rotation composes as quaternions like v1, not as summed angles`() {
        val (rec, _) = run()
        fun rot(block: String) = rec.specs.values.single { (it.kind as? DisplayKind.Block)?.blockKey?.contains(block) == true }.transform.rotation
        val composed = rot("dirt")
        val base = rot("sand")
        // dirt turn = Y90 * X90, sand turn = Y90 and both share the same frame rotation: base^-1 * composed = X90
        val rel = base.conjugate() * composed
        val x90 = com.astarworks.astera.core.domain.geometry.math.Quaternion.axisAngle(Vec3(1.0, 0.0, 0.0), Math.toRadians(90.0))
        assertThat(kotlin.math.abs(rel.dot(x90))).describedAs("relative rotation $rel").isGreaterThan(0.9999)
    }

    @Test
    fun `along path emits ceil(dist over 0_4) points per tick, at most 24, ending at the current position`() {
        val (_, sink) = run()
        val hit = sink.bursts.filter { it.second.particle == "hit-spark" }
        val perTick = hit.groupBy { it.first }
        // slow: 0.5 blocks per tick -> 2 points; fast: 20 blocks in one tick -> 50 points capped at v1 MAX_PATH_POINTS (24)
        val counts = perTick.mapValues { (_, v) -> v.size }
        assertThat(counts[0]).describedAs("the first play has no previous one: a single point each (v1 u > 0 only), $counts").isEqualTo(2)
        assertThat(counts.values).describedAs("per-tick burst counts $counts").contains(2 + 24)
        assertThat(counts.values.max()).describedAs("never above 2 + 24").isEqualTo(2 + 24)
    }

    @Test
    fun `along path with every 2 ticks sweeps from the previous emission, not the last tick`() {
        val (_, sink) = run()
        val w = sink.bursts.filter { it.second.particle == "end-rod" }.groupBy { it.first }.mapValues { (_, v) -> v.size }
        // slow moves 0.5 blocks/tick, so 1.0 block between plays 2 ticks apart -> ceil(1.0 / 0.4) = 3 points; the first play is one point
        assertThat(w[0]).isEqualTo(1)
        assertThat(w.filterKeys { it > 0 }.values).describedAs("plays at 2,4,6,8: $w").containsOnly(3)
    }
}
