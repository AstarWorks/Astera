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
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.domain.effect.ParticleData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
 *
 * `make skill-preview` (tools/skill-viewer, docs/contributing/vfx-team/skill-preview.md) reads the same file and needs three
 * additive things beside `ticks`: `bones.*.scale` (the box size multiplier), `caster` (the caster's feet per skill tick, relative
 * to where it stood at the cast) and `displays` (the fx Display entities as `astera-sim-trace/1` display events, ticks relative to
 * the cast, positions relative to the caster's feet at the cast; the performers -- skin bones and the main-hand weapon -- are left
 * out, `ticks` carries them).
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
        val feet = HashMap<Int, Vec3>()
        var origin = Vec3.ZERO
        var fx: List<JsonElement> = emptyList()
        var emissions: List<JsonObject> = emptyList()
        var start = 0
        var sampled: List<VfxPerformanceSampler.Tick> = emptyList()
        var slim = false
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
            origin = caster.position
            val casterLook = caster.direction
            caster.cast(skill)
            val last = def.durationTicks + 10
            for (t in 0..last) {
                counts[t] = probe.worstCount()
                feet[t] = caster.position
                if (t < last) advance(1)
            }
            emissions = sim.recorder.particleCalls().filter { it.tick >= castTick }
                .map { emissionJson(it, castTick, origin, casterLook, sim.recorder) }
            fx = fxDisplayEvents(sim.recorder.toJson(), castTick, origin)
            start = sim.displays.spawned.firstOrNull { it.spec.kind == DisplayKind.AnchorMainHand }
                ?.let { (it.tick - castTick).toInt() } ?: 0
            val body = def.body?.let { id ->
                VfxBodyContext(checkNotNull(sim.services.getOrThrow<ISkinRigRegistry>().find(id)) { "body animation '$id' not loaded" })
            }
            slim = body?.slim ?: false
            sampled = VfxPerformanceSampler.sample(def, body, freezesAfter(skillText, vfxId), startTick = start.coerceAtLeast(0))
        }
        return json(skill, slim, sampled, counts, feet.mapValues { it.value - origin }, fx, emissions)
    }

    /**
     * The `displays` events of [trace] from [castTick] on without the performers, ticks relative to the cast, `x/y/z` of spawns
     * and teleports relative to [origin] (the caster's feet at the cast).
     */
    private fun fxDisplayEvents(trace: JsonElement, castTick: Long, origin: Vec3): List<JsonElement> {
        val performers = HashSet<String>()
        val out = ArrayList<JsonElement>()
        for (tk in trace.jsonObject.getValue("ticks").jsonArray) {
            val tick = tk.jsonObject.getValue("tick").jsonPrimitive.longOrNull ?: continue
            if (tick < castTick) continue
            for (ev in tk.jsonObject.getValue("displays").jsonArray) {
                val o = ev.jsonObject
                val id = o.getValue("id").jsonPrimitive.content
                val op = o.getValue("op").jsonPrimitive.content
                if (op == "spawn" && o.getValue("kind").jsonPrimitive.content.let { it == "skin_bone" || it == "main_hand_weapon" }) performers += id
                if (id in performers) continue
                val place = op == "spawn" || op == "teleport"
                val shifted = LinkedHashMap<String, JsonElement>()
                shifted["tick"] = JsonPrimitive(tick - castTick)
                for ((k, v) in o) {
                    shifted[k] = when {
                        place && k == "x" -> JsonPrimitive(v.jsonPrimitive.doubleOrNull!! - origin.x)
                        place && k == "y" -> JsonPrimitive(v.jsonPrimitive.doubleOrNull!! - origin.y)
                        place && k == "z" -> JsonPrimitive(v.jsonPrimitive.doubleOrNull!! - origin.z)
                        else -> v
                    }
                }
                out += JsonObject(shifted)
            }
        }
        return out
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

    private fun json(
        skill: String,
        slim: Boolean,
        ticks: List<VfxPerformanceSampler.Tick>,
        counts: Map<Int, Int>,
        feet: Map<Int, Vec3>,
        fx: List<JsonElement>,
        emissions: List<JsonObject>,
    ): String {
        fun n(v: Double): String = String.format(Locale.ROOT, "%.6f", if (abs(v) < 5e-7) 0.0 else v)
        fun v3(v: Vec3) = "[${n(v.x)}, ${n(v.y)}, ${n(v.z)}]"
        fun q(q: com.astarworks.astera.core.domain.geometry.math.Quaternion) = "[${n(q.x)}, ${n(q.y)}, ${n(q.z)}, ${n(q.w)}]"
        val sb = StringBuilder()
        sb.append("{\n  \"skill\": \"").append(skill).append("\",\n  \"slim\": ").append(slim).append(",\n  \"weaponScale\": ").append(n(SkinRigTransforms.PLAYER_SCALE)).append(",\n  \"ticks\": [\n")
        ticks.forEachIndexed { i, t ->
            sb.append("    {\"tick\": ").append(t.tick).append(", \"bones\": {")
            sb.append(t.bones.entries.joinToString(", ") { (name, p) -> "\"$name\": {\"pos\": ${v3(p.pos)}, \"rot\": ${q(p.rot)}, \"scale\": ${v3(p.scale)}}" })
            sb.append("}")
            t.weapon?.let { w ->
                sb.append(", \"weapon\": {\"pos\": ${v3(w.pos)}, \"rot\": ${q(w.rot)}, \"tip\": ${v3(w.tip)}, \"edgeNormal\": ${v3(w.edgeNormal)}}")
            }
            sb.append(", \"visibleWeapons\": ").append(counts[t.tick] ?: 0).append("}")
            sb.append(if (i < ticks.size - 1) ",\n" else "\n")
        }
        sb.append("  ],\n  \"caster\": [")
        sb.append(feet.entries.sortedBy { it.key }.joinToString(", ") { (t, p) -> "{\"tick\": $t, \"pos\": ${v3(p)}}" })
        sb.append("],\n  \"displays\": [\n")
        sb.append(fx.joinToString(",\n") { "    $it" })
        sb.append("\n  ],\n  \"particles\": [")
        sb.append(emissions.joinToString(",\n    ", prefix = if (emissions.isEmpty()) "" else "\n    ", postfix = if (emissions.isEmpty()) "" else "\n  ") { it.toString() })
        sb.append("]\n}\n")
        return sb.toString()
    }

    /**
     * One particle call of the real runtime, in caster-local space (feet at the cast position, `+x` right, `+y` up, `+z`
     * forward along the cast-time look; same frame as the bones). `tick` = skill tick. `type` = vanilla particle key,
     * `count`/`delta`/`speed` = the packet fields as the client reads them: `count > 0` -> `delta` is the gaussian spread
     * per axis and `speed` the random-velocity scale; `count == 0` -> `delta` is the unit direction and `speed` the
     * velocity scale (vx = delta.x * speed). `data` = typed particle data (dust colour, item, trail ...), see
     * [TraceRecorder.particleDataJson], absolute positions inside it are caster-local too.
     */
    private fun emissionJson(c: TraceRecorder.ParticleCall, castTick: Long, feet: Vec3, look: Vec3, rec: TraceRecorder): JsonObject {
        val flat = Vec3(look.x, 0.0, look.z).let { if (it.lengthSquared() < 1e-9) Vec3(0.0, 0.0, 1.0) else it.normalize() }
        val right = Vec3(-flat.z, 0.0, flat.x)
        fun point(w: Vec3): Vec3 = (w - feet).let { Vec3(it.x * right.x + it.z * right.z, it.y, it.x * flat.x + it.z * flat.z) }
        fun dir(w: Vec3): Vec3 = Vec3(w.x * right.x + w.z * right.z, w.y, w.x * flat.x + w.z * flat.z)
        fun arr(v: Vec3): JsonArray = JsonArray(listOf(v.x, v.y, v.z).map { JsonPrimitive(it) })
        val spec = c.spec
        val data = spec.data ?: spec.color?.takeIf { spec.key == "dust" }?.let { ParticleData.Dust(it, spec.scale) }
        return buildJsonObject {
            put("tick", c.tick - castTick)
            put("type", spec.key.removePrefix("minecraft:"))
            put("count", spec.count)
            put("pos", arr(point(c.at)))
            put("delta", arr(if (spec.count == 0) dir(spec.offset) else spec.offset))
            put("speed", spec.extra)
            if (data != null) put("data", localData(rec.particleDataJson(data), ::point, ::arr))
            if (data == null && spec.color != null) put("color", buildJsonObject { put("r", spec.color!!.r); put("g", spec.color!!.g); put("b", spec.color!!.b) })
            put("force", spec.force)
        }
    }

    /** [data] with every `{x, y, z}` target converted by [point] into a `[x, y, z]` array. */
    private fun localData(data: JsonElement, point: (Vec3) -> Vec3, arr: (Vec3) -> JsonArray): JsonElement {
        val o = data as? JsonObject ?: return data
        return buildJsonObject {
            for ((k, v) in o) {
                val xyz = (v as? JsonObject)?.takeIf { "x" in it && "y" in it && "z" in it && "r" !in it }
                if (xyz != null) put(k, arr(point(Vec3(xyz["x"]!!.jsonPrimitive.double, xyz["y"]!!.jsonPrimitive.double, xyz["z"]!!.jsonPrimitive.double))))
                else put(k, v)
            }
        }
    }

    private fun contentDir(): Path =
        Paths.get(System.getProperty("astera.sim.contentDir") ?: "content").toAbsolutePath().normalize()
}
