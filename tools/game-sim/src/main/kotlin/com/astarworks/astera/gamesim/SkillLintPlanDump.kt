package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.ISkinRigRegistry
import com.astarworks.astera.core.application.port.outbound.IVfxRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.vfx.VfxBodyContext
import com.astarworks.astera.core.domain.effect.vfx.VfxFreeze
import com.astarworks.astera.core.domain.effect.vfx.VfxPerformanceSampler
import com.astarworks.astera.core.domain.effect.vfx.VfxId
import com.astarworks.astera.core.domain.geometry.Vec3
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.math.abs

/**
 * Writes `build/skill-lint/<skill>/plan.json` for `skill-lint` (ADR-0254, docs/contributing/vfx-team/skill-lint.md).
 *
 * ```
 * make skill-lint-plan SKILL=bastion-greatsword-s1        # = ./gradlew :tools:game-sim:skillLintPlan -Pskill=...
 * make skill-lint SKILL=bastion-greatsword-s1 PLAN=build/skill-lint/bastion-greatsword-s1/plan.json
 * ```
 *
 * The skill is cast for real in the headless sim (real content, real wiring). Per skill tick: the body bones and the weapon
 * come from the planner math ([VfxPerformanceSampler], caster-local space, +x right), and `visibleWeapons` is the worst count
 * over every viewer of the lease model ([PerformanceProbe]) -- the number that must be 1 on every tick.
 */
public object SkillLintPlanDump {

    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "usage: SkillLintPlanDump <skill-id> [out-dir]" }
        val skill = args[0]
        val out = Paths.get(args.getOrNull(1) ?: "build/skill-lint").toAbsolutePath()
        val file = write(skill, out)
        println("skill-lint plan: $file")
    }

    public fun write(skill: String, outDir: Path): Path {
        val file = outDir.resolve(skill).resolve("plan.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, render(skill))
        return file
    }

    /** The plan.json text. */
    public fun render(skill: String): String {
        val skillText = Files.readString(contentDir().resolve("skills/$skill.yaml"))
        val counts = HashMap<Int, Int>()
        var start = 0
        var sampled: List<VfxPerformanceSampler.Tick> = emptyList()
        scenario("skill-lint-$skill") {
            val caster = player("caster", team = "red")
            player("observer", at = Vec3(4.0, groundY, 0.0), team = "blue")
            val registry = sim.services.getOrThrow<IVfxRegistry>()
            val vfxId = Regex("""vfx_id:\s*([a-z0-9-]+)""").findAll(skillText).map { it.groupValues[1] }
                .firstOrNull { registry.find(VfxId(it))?.isPerformance == true }
                ?: error("skill '$skill' plays no performance vfx (a rig with weapon: main_hand)")
            val def = registry.find(VfxId(vfxId))!!
            val probe = PerformanceProbe(sim, caster)
            val castTick = sim.tick
            caster.cast(skill)
            val last = def.durationTicks + 10
            for (t in 0..last) {
                counts[t] = probe.worstCount()
                if (t < last) advance(1)
            }
            start = sim.displays.spawned.firstOrNull { it.spec.kind == DisplayKind.AnchorMainHand }
                ?.let { (it.tick - castTick).toInt() } ?: 0
            val body = def.body?.let { id ->
                VfxBodyContext(checkNotNull(sim.services.getOrThrow<ISkinRigRegistry>().find(id)) { "body animation '$id' not loaded" })
            }
            sampled = VfxPerformanceSampler.sample(def, body, freezesAfter(skillText, vfxId), startTick = start.coerceAtLeast(0))
        }
        return json(skill, sampled, counts)
    }

    /** The `freeze: [{at, ticks}]` windows of the play_vfx step that plays [vfxId]. */
    private fun freezesAfter(skillText: String, vfxId: String): List<VfxFreeze> {
        val at = skillText.indexOf("vfx_id: $vfxId")
        if (at < 0) return emptyList()
        val rest = skillText.substring(at)
        val end = Regex("""\n  - type:""").find(rest)?.range?.first ?: rest.length
        return Regex("""\{\s*at:\s*(\d+),\s*ticks:\s*(\d+)\s*\}""").findAll(rest.substring(0, end))
            .map { VfxFreeze(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.toList()
    }

    private fun json(skill: String, ticks: List<VfxPerformanceSampler.Tick>, counts: Map<Int, Int>): String {
        fun n(v: Double): String = String.format(Locale.ROOT, "%.6f", if (abs(v) < 5e-7) 0.0 else v)
        fun v3(v: Vec3) = "[${n(v.x)}, ${n(v.y)}, ${n(v.z)}]"
        fun q(q: com.astarworks.astera.core.domain.geometry.math.Quaternion) = "[${n(q.x)}, ${n(q.y)}, ${n(q.z)}, ${n(q.w)}]"
        val sb = StringBuilder()
        sb.append("{\n  \"skill\": \"").append(skill).append("\",\n  \"ticks\": [\n")
        ticks.forEachIndexed { i, t ->
            sb.append("    {\"tick\": ").append(t.tick).append(", \"bones\": {")
            sb.append(t.bones.entries.joinToString(", ") { (name, p) -> "\"$name\": {\"pos\": ${v3(p.pos)}, \"rot\": ${q(p.rot)}}" })
            sb.append("}")
            t.weapon?.let { w ->
                sb.append(", \"weapon\": {\"pos\": ${v3(w.pos)}, \"rot\": ${q(w.rot)}, \"tip\": ${v3(w.tip)}, \"edgeNormal\": ${v3(w.edgeNormal)}}")
            }
            sb.append(", \"visibleWeapons\": ").append(counts[t.tick] ?: 0).append("}")
            sb.append(if (i < ticks.size - 1) ",\n" else "\n")
        }
        sb.append("  ]\n}\n")
        return sb.toString()
    }

    private fun contentDir(): Path =
        Paths.get(System.getProperty("astera.sim.contentDir") ?: "content").toAbsolutePath().normalize()
}
