package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.load.CompiledSkill
import com.astarworks.astera.core.application.skillengine.load.SkillCatalog
import com.astarworks.astera.core.application.skillengine.runtime.CastEvent
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.CastObserver
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineGateways
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineKit
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineContents
import com.astarworks.astera.core.application.skillengine.runtime.ops.ISkillWorldEffectGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillCooldownGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillCasterAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillScreenGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVictimAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.hit.ContactEvent
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.modifier.ModOp
import com.astarworks.astera.core.domain.skillengine.modifier.ModifierStack
import com.astarworks.astera.core.domain.skillengine.modifier.ParamModifier
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlin.math.abs

/**
 * The proof of skill engine v2 (ADR-0299, SKL-08): coin 2 of couture-rapier-s2 run by the real cast runner.
 *
 * Runs on the real frame tree, motion laws, contact resolver, decoder / compiler / catalog, CEL layer, op executors
 * (assembled by `SkillEngineKit`) and the shipped coin-2 content; only the ports are fakes. Writes
 * `build/sim-traces/v2/<skill-id>.json` (`astera-sim-trace/2`, see `tools/vfx-shot/SIM-TRACE-V2.md`).
 */
private val caster: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c0a1")
private val dummy: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d033")

class Coin2CastScenarioTest {

    private val baseId = "couture-rapier-s2"
    private val skinId = "couture-rapier-s2@crimson"
    private val piecesNames = listOf("body", "blade_a", "blade_b")

    // ---------------------------------------------------------------- harness

    private class Snapshot(
        val tick: Int,
        val poses: Map<String, Frame>,
        val parents: Map<String, String?>,
        val kinds: Map<String, String>,
        val events: List<CastEvent>,
    ) {
        val origins: Map<String, Vec3> get() = poses.mapValues { it.value.origin }
    }

    private class Recorder : CastObserver {
        val byCast = LinkedHashMap<Long, MutableList<Snapshot>>()
        val trees = HashMap<Long, FrameTree>()
        override fun onTick(cast: CastId, tick: Int, tree: FrameTree, nodes: Set<String>, events: List<CastEvent>) {
            trees[cast.value] = tree
            val poses = LinkedHashMap<String, Frame>()
            val parents = LinkedHashMap<String, String?>()
            val kinds = LinkedHashMap<String, String>()
            for (n in nodes.sorted()) {
                val w = tree.worldOf(FrameId(n)).getOrNull() ?: continue
                val node = tree.node(FrameId(n))
                poses[n] = w
                parents[n] = node?.parent?.value
                kinds[n] = node?.kind?.name?.lowercase() ?: "point"
            }
            byCast.getOrPut(cast.value) { ArrayList() } += Snapshot(tick, poses, parents, kinds, events)
        }
    }

    /** What the gateways were asked for, per tick; the trace writer turns it into the v2 object table. */
    private class Capture {
        var now = 0
        class Obj(val handle: Long, val kind: String, val spawnTick: Int, val fields: JsonObject, var removeTick: Int? = null)
        val objects = LinkedHashMap<Long, Obj>()
        val objectUpdates = HashMap<Int, MutableMap<Long, JsonObject>>()
        val particles = HashMap<Int, MutableList<ParticleBurst>>()
        val sounds = HashMap<Int, MutableList<JsonObject>>()
        val damage = HashMap<Int, MutableList<JsonObject>>()
        fun update(h: Long, v: JsonObject) { objectUpdates.getOrPut(now) { LinkedHashMap() }[h] = v }
    }

    private fun poseJson(f: Frame): JsonArray = buildJsonArray {
        add(JsonPrimitive(f.origin.x)); add(JsonPrimitive(f.origin.y)); add(JsonPrimitive(f.origin.z))
        add(JsonPrimitive(f.rotation.x)); add(JsonPrimitive(f.rotation.y)); add(JsonPrimitive(f.rotation.z)); add(JsonPrimitive(f.rotation.w))
    }

    private fun vecJson(v: Vec3): JsonArray = buildJsonArray { add(JsonPrimitive(v.x)); add(JsonPrimitive(v.y)); add(JsonPrimitive(v.z)) }

    private inline fun <reified T> noop(): T = java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
        when (m.returnType) {
            java.lang.Boolean.TYPE -> true
            Integer.TYPE -> 0
            java.lang.Double.TYPE -> 0.0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    } as T

    private class Gameplay(private val cap: Capture, base: ISkillGameplayGateway) : ISkillGameplayGateway by base {
        val damage = ArrayList<SkillDamage>()
        val statuses = ArrayList<Triple<String, Int, Int>>()
        val impulses = ArrayList<Vec3>()
        override fun damage(caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3) {
            damage += components
            for (c in components) cap.damage.getOrPut(cap.now) { ArrayList() } += buildJsonObject { put("victim", "enemy"); put("kind", c.kind); put("amount", c.amount) }
        }
        override fun applyStatus(caster: UUID, victim: UUID, statusId: String, stacks: Int, durationTicks: Int) {
            statuses += Triple(statusId, stacks, durationTicks)
        }
        override fun impulse(victim: UUID, vector: Vec3) {
            impulses += vector
        }
        override fun freezeEntities(entities: List<UUID>, ticks: Int) = Unit
    }

    private inner class Sim(val c: Coin2Collaborators, val catalog: SkillCatalog, val withDummy: Boolean) {
        val recorder = Recorder()
        val capture = Capture()
        val gameplay = Gameplay(capture, noop())
        private var handles = 0L

        private fun faceJson(face: PlateFace): JsonObject = when (face) {
            PlateFace.ViewerCenter -> buildJsonObject { put("type", "viewer_center") }
            PlateFace.ViewerVertical -> buildJsonObject { put("type", "viewer_vertical") }
            is PlateFace.ViewerAxis -> buildJsonObject { put("type", "viewer_axis"); put("axis", vecJsonOf(Vec3(0.0, 1.0, 0.0))) }
            is PlateFace.Plane -> buildJsonObject { put("type", "plane"); put("axisA", vecJsonOf(Vec3(1.0, 0.0, 0.0))); put("axisB", vecJsonOf(Vec3(0.0, 1.0, 0.0))) }
        }

        private fun vecJsonOf(v: Vec3) = buildJsonArray { add(JsonPrimitive(v.x)); add(JsonPrimitive(v.y)); add(JsonPrimitive(v.z)) }

        private fun spawn(kind: String, fields: JsonObject, first: JsonObject): VisualHandle {
            val h = ++handles
            capture.objects[h] = Capture.Obj(h, kind, capture.now, fields)
            capture.update(h, first)
            return VisualHandle(h)
        }

        private fun poseObj(f: Frame, interp: Int = 1) = buildJsonObject {
            put("pose", buildJsonArray {
                add(JsonPrimitive(f.origin.x)); add(JsonPrimitive(f.origin.y)); add(JsonPrimitive(f.origin.z))
                add(JsonPrimitive(f.rotation.x)); add(JsonPrimitive(f.rotation.y)); add(JsonPrimitive(f.rotation.z)); add(JsonPrimitive(f.rotation.w))
            })
            put("interp", interp)
        }

        private val visuals = object : ISkillVisualGateway {
            override fun spawnMesh(castOwner: UUID, item: String, part: String?, scale: Double, pose: Frame) =
                spawn("mesh", buildJsonObject { put("item", item); put("part", part?.let { JsonPrimitive(it) } ?: JsonNull); put("scale", scale) }, poseObj(pose))
            override fun spawnPlate(castOwner: UUID, sprite: String, face: PlateFace, size: Double, alpha: Double, pose: Frame) =
                spawn("plate", buildJsonObject { put("sprite", sprite); put("face", faceJson(face)); put("size", size); put("alpha", alpha) }, poseObj(pose))
            override fun spawnLine(castOwner: UUID, from: Vec3, to: Vec3, thickness: Double, colorHex: String, face: PlateFace) =
                spawn("line", buildJsonObject { put("thickness", thickness); put("color", colorHex); put("face", faceJson(face)) },
                    buildJsonObject { put("from", vecJsonOf(from)); put("to", vecJsonOf(to)) })
            override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double) =
                spawn("trail", buildJsonObject { put("ticks", ticks); put("widthFrac", widthFrac); put("color", colorHex); put("alpha", alpha) }, buildJsonObject {})
            override fun updatePose(handle: VisualHandle, pose: Frame, interpolationTicks: Int) = capture.update(handle.id, poseObj(pose, interpolationTicks))
            override fun updateLine(handle: VisualHandle, from: Vec3, to: Vec3) =
                capture.update(handle.id, buildJsonObject { put("from", vecJsonOf(from)); put("to", vecJsonOf(to)) })
            override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) =
                capture.update(handle.id, buildJsonObject { put("root", vecJsonOf(root)); put("tip", vecJsonOf(tip)) })
            override fun updateScale(handle: VisualHandle, scale: Double) = Unit
            override fun remove(handle: VisualHandle) {
                capture.objects[handle.id]?.removeTick = capture.now
            }
        }
        private val particles = object : ISkillParticleGateway {
            override fun emit(burst: ParticleBurst) {
                capture.particles.getOrPut(capture.now) { ArrayList() } += burst
            }
        }
        private val sounds = object : ISkillSoundGateway {
            override fun play(sound: String, at: Vec3, volume: Double, pitch: Double) {
                capture.sounds.getOrPut(capture.now) { ArrayList() } += buildJsonObject {
                    put("sound", sound); put("at", vecJsonOf(at)); put("volume", volume); put("pitch", pitch)
                }
            }
        }
        private val anim = object : ISkillVictimAnimGateway {
            override fun play(victim: UUID, anim: String, strength: Double, awayFrom: Vec3) = Unit
        }

        private val dummyFeet = Vec3(0.0, 0.0, 6.0)
        private val entities = object : ISkillEntityQuery {
            override fun nearby(point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate> {
                if (!withDummy) return emptyList()
                val box = HitBox(Vec3(-0.3, 0.0, 5.7), Vec3(0.3, 1.8, 6.3))
                return if (box.center.distance(point) <= radius + 1.2) listOf(HitCandidate(dummy, dummyFeet, 0.0, box)) else emptyList()
            }
            override fun rootOf(entity: UUID): EntityRoot? = when (entity) {
                caster -> EntityRoot(Vec3.ZERO, 0.0, 0.0, 1.62, 1.8)
                dummy -> if (withDummy) EntityRoot(dummyFeet, 0.0, 0.0, 1.62, 1.8) else null
                else -> null
            }
            override fun raycastBlock(from: Vec3, to: Vec3): BlockHit? = null
        }

        val kit: SkillEngineKit = SkillEngineKit.build(
            gateways = SkillEngineGateways(
                ports = SkillRuntimePorts(visuals, particles, sounds, gameplay, entities, anim),
                movement = noop<ISkillMovementGateway>(),
                world = noop<ISkillWorldGateway>(),
                casterAnim = noop<ISkillCasterAnimGateway>(),
                screen = noop<ISkillScreenGateway>(),
                cooldowns = noop<ISkillCooldownGateway>(),
                items = noop<ISkillItemGateway>(),
                effects = noop<ISkillWorldEffectGateway>(),
            ),
            newTree = c.newTree,
            motion = c.motion,
            contacts = c.contacts,
            exprs = c.exprs,
            points = c.points,
            skills = catalog::get,
            observer = recorder,
        )
        val runner = kit.runner

        fun request(skill: CompiledSkill, tick: Int, modifiers: ModifierStack = ModifierStack.EMPTY) =
            CastRequest(skill, caster, Vec3.ZERO, 0.0, 0.0, seed = 7L, modifiers = modifiers, startTick = tick)

        fun start(skill: CompiledSkill, tick: Int, modifiers: ModifierStack = ModifierStack.EMPTY): CastId =
            when (val r = runner.start(request(skill, tick, modifiers))) {
                is Result.Success -> r.value
                is Result.Failure -> error("cast did not start: ${r.error}")
            }

        /** Ticks [from] (inclusive) until every listed cast ended or [limit] ticks passed. */
        fun run(from: Int, casts: List<CastId>, limit: Int = 160): Int {
            var t = from
            while (t < from + limit) {
                capture.now = t
                runner.tick(t)
                t++
                if (casts.none { runner.isRunning(it) }) break
            }
            return t
        }

        fun tickOnce(t: Int) {
            capture.now = t
            runner.tick(t)
        }

        fun snapshots(cast: CastId): List<Snapshot> = recorder.byCast[cast.value].orEmpty()
    }

    private fun catalog(c: Coin2Collaborators): SkillCatalog {
        val catalog = c.newCatalog()
        val report = SkillEngineContents(catalog).reload(c.skillsDir)
        assertThat(report.failed).describedAs("shipped coin-2 content loads: ${report.failures}").isZero()
        assertThat(catalog.ids()).contains(baseId, skinId)
        return catalog
    }

    private fun wired(): Coin2Collaborators = Coin2CastWiring.collaborators()

    private fun events(snaps: List<Snapshot>): List<CastEvent> = snaps.flatMap { it.events }

    private fun contactsOf(snaps: List<Snapshot>): List<ContactEvent> = events(snaps).mapNotNull { it.contact }

    // -------------------------------------------------------------- scenarios

    @Test
    fun `coin 2 plays release hit stick reel arrive with the pieces carried across`() {
        val c = wired()
        val cat = catalog(c)
        val sim = Sim(c, cat, withDummy = true)
        val skill = cat.get(baseId)!!
        val cast = sim.start(skill, 0)
        val end = sim.run(0, listOf(cast))
        val snaps = sim.snapshots(cast)

        val firstSeen = LinkedHashMap<String, Int>()
        for (e in events(snaps)) firstSeen.putIfAbsent(e.name, e.tick)
        val order = listOf("release", "hit", "reel", "arrive")
        for (n in order) assertThat(firstSeen).describedAs("event $n raised: $firstSeen").containsKey(n)
        assertThat(order.map { firstSeen.getValue(it) }).describedAs("event ticks of $order").isSorted()
        // "stick" is the stick op in the hit handler (not an event): the volley hangs off the victim until the reel
        val stuckParent = snaps.filter { it.tick > firstSeen.getValue("hit") && it.tick < firstSeen.getValue("reel") }
            .mapNotNull { it.parents["volley"] }
        assertThat(stuckParent).describedAs("volley is stuck to a contact/enemy frame between hit and reel").isNotEmpty()
        assertThat(stuckParent.last()).describedAs("stuck parents $stuckParent").isEqualTo("contact")
        assertThat(sim.runner.diagnostics()).describedAs("no op failed").isEmpty()

        // damage 18 on the throw, bleed 2 stacks
        assertThat(sim.gameplay.damage.first().amount).isEqualTo(18.0)
        assertThat(sim.gameplay.statuses.first().first).isEqualTo("bleed")
        assertThat(sim.gameplay.statuses.first().second).isEqualTo(2)

        // the 3 pieces ride the volley frame from release on, without a position jump
        val release = firstSeen.getValue("release")
        val at = snaps.associateBy { it.tick }
        val before = at.getValue(release - 1)
        val during = at.getValue(release)
        val after = at.getValue(release + 1)
        for (piece in piecesNames) {
            assertThat(during.parents[piece]).describedAs("$piece parent after release").isEqualTo("volley")
            val stepIn = during.origins.getValue(piece).distance(before.origins.getValue(piece))
            val stepNext = after.origins.getValue(piece).distance(during.origins.getValue(piece))
            assertThat(abs(stepIn - stepNext)).describedAs("$piece displacement on the release tick $stepIn vs next $stepNext").isLessThan(0.75)
        }

        // back on the shears at the end
        val last = snaps.last()
        for (piece in piecesNames) assertThat(last.parents[piece]).describedAs("$piece parent at the end").isEqualTo("shears")

        writeTrace(skill, sim, cast, end)
    }

    @Test
    fun `a skin patch changes colours only, contacts are identical`() {
        val c = wired()
        val cat = catalog(c)
        val sim = Sim(c, cat, withDummy = true)
        val base = sim.start(cat.get(baseId)!!, 0)
        val skin = sim.start(cat.get(skinId)!!, 0)
        val end = sim.run(0, listOf(base, skin))
        val a = contactsOf(sim.snapshots(base))
        val b = contactsOf(sim.snapshots(skin))
        assertThat(a).describedAs("base has contacts").isNotEmpty()
        assertThat(b).describedAs("skin contacts equal base contacts").isEqualTo(a)
        writeTrace(cat.get(skinId)!!, sim, skin, end)
    }

    @Test
    fun `range modifier of one half shortens the fly and the thread`() {
        val c = wired()
        val cat = catalog(c)
        val sim = Sim(c, cat, withDummy = false)
        val half = ModifierStack(listOf(ParamModifier("test", "range", ModOp.MULTIPLY, 0.5)))
        val full = sim.start(cat.get(baseId)!!, 0)
        val short = sim.start(cat.get(baseId)!!, 0, half)
        sim.run(0, listOf(full, short))

        fun reach(cast: CastId): Double = sim.snapshots(cast).maxOf { s ->
            val volley = s.origins["volley"]
            val palm = s.origins["palm"]
            if (volley == null || palm == null) 0.0 else volley.distance(palm)
        }
        val fullReach = reach(full)
        val shortReach = reach(short)
        assertThat(fullReach).describedAs("full fly/thread length").isGreaterThan(3.0)
        assertThat(shortReach).describedAs("half-range fly/thread length vs full $fullReach").isLessThan(fullReach * 0.8)
    }

    @Test
    fun `per skill reload changes the next cast only`(@TempDir tmp: Path) {
        val c = wired()
        val dir = tmp.resolve("skills")
        Files.createDirectories(dir)
        Files.list(c.skillsDir).use { s ->
            s.filter { it.fileName.toString().startsWith(baseId) }.forEach { src ->
                src.toFile().copyRecursively(dir.resolve(src.fileName.toString()).toFile(), overwrite = true)
            }
        }
        val catalog = c.newCatalog()
        val contents = SkillEngineContents(catalog)
        assertThat(contents.reload(dir).failed).isZero()

        val sim = Sim(c, catalog, withDummy = false)
        val first = sim.start(catalog.get(baseId)!!, 0)
        var t = 0
        while (t < 14) sim.tickOnce(t++)

        // edit the flight speed on disk and reload: only the coin-2 ids are recomposed
        val file = Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) && it.toString().contains("throw_speed") || Files.isRegularFile(it) && Files.readString(it).contains("throw_speed") }.findFirst().get() }
        val text = Files.readString(file)
        val edited = text.replace(Regex("throw_speed:\\s*1\\.7"), "throw_speed: 1.2")
        assertThat(edited).describedAs("coin-2 file declares throw_speed: 1.7").isNotEqualTo(text)
        Files.writeString(file, edited)
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        val report = contents.reload(dir)
        assertThat(report.failed).isZero()
        assertThat(report.loaded).describedAs("only the coin-2 skills were reloaded").isBetween(1, catalog.ids().size)

        val second = sim.start(catalog.get(baseId)!!, t)
        sim.run(t, listOf(first, second))

        fun flightSpeed(cast: CastId): Double {
            val snaps = sim.snapshots(cast)
            val release = events(snaps).first { it.name == "release" }.tick
            val at = snaps.associateBy { it.tick }
            return at.getValue(release + 3).origins.getValue("volley").distance(at.getValue(release + 2).origins.getValue("volley"))
        }
        assertThat(flightSpeed(first)).describedAs("cast in progress keeps its definition").isEqualTo(1.7, org.assertj.core.data.Offset.offset(0.05))
        assertThat(flightSpeed(second)).describedAs("next cast uses the reloaded one").isEqualTo(1.2, org.assertj.core.data.Offset.offset(0.05))
    }

    // ------------------------------------------------------------------ trace

    /** `astera-sim-trace/2` (tools/vfx-shot/SIM-TRACE-V2.md) to `build/sim-traces/v2/<skill-id>.json`. */
    private fun writeTrace(skill: CompiledSkill, sim: Sim, cast: CastId, endTick: Int) {
        val snaps = sim.snapshots(cast)
        val cap = sim.capture
        val first = snaps.first().tick
        val last = snaps.last().tick
        val byTick = snaps.associateBy { it.tick }
        val declared = skill.def.frames.associateBy { it.id }
        val firstParents = LinkedHashMap<String, String?>()
        val kinds = LinkedHashMap<String, String>()
        for (s in snaps) for ((k, v) in s.parents) {
            firstParents.putIfAbsent(k, v)
            kinds.putIfAbsent(k, s.kinds.getValue(k))
        }
        fun frameKind(id: String): String = when (kinds[id]) {
            "world", "caster", "bone", "item", "part", "body", "enemy", "contact" -> kinds.getValue(id)
            else -> "point"
        }
        val doc = buildJsonObject {
            put("format", "astera-sim-trace/2")
            put("skill", skill.def.id)
            put("item", skill.def.item ?: "")
            put("tickRate", 20)
            put("castTick", first)
            put("firstTick", first)
            put("lastTick", last)
            put("caster", buildJsonObject { put("id", "caster"); put("height", 1.8) })
            put("victims", buildJsonArray {
                if (sim.withDummy) add(buildJsonObject { put("id", "enemy"); put("height", 1.8); put("slim", false) })
            })
            put("frames", buildJsonArray {
                for ((id, parent) in firstParents) add(buildJsonObject {
                    put("id", id)
                    put("kind", frameKind(id))
                    put("parent", parent?.let { JsonPrimitive(it) } ?: JsonNull)
                    declared[id]?.takeIf { it.model != null }?.let { d ->
                        put("item", d.model!!)
                        d.part?.let { put("part", it) }
                    }
                })
            })
            put("objects", buildJsonArray {
                for (o in cap.objects.values) add(buildJsonObject {
                    put("handle", o.handle)
                    put("kind", o.kind)
                    put("op", "${o.kind}#${o.handle}")
                    put("spawnTick", o.spawnTick)
                    put("removeTick", o.removeTick?.let { JsonPrimitive(it) } ?: JsonNull)
                    for ((k, v) in o.fields) put(k, v)
                })
            })
            put("ticks", buildJsonArray {
                for (t in first..last) {
                    val s = byTick.getValue(t)
                    add(buildJsonObject {
                        put("tick", t)
                        put("poses", buildJsonObject { for ((id, f) in s.poses) put(id, poseJson(f)) })
                        put("objects", buildJsonObject { cap.objectUpdates[t]?.forEach { (h, v) -> put(h.toString(), v) } })
                        put("events", buildJsonArray {
                            for (e in s.events) add(buildJsonObject {
                                put("name", e.name)
                                put("source", e.source?.let { JsonPrimitive(it) } ?: JsonNull)
                                e.contact?.let { put("victim", "enemy") }
                            })
                        })
                        put("contacts", buildJsonArray {
                            for (e in s.events) e.contact?.let { k ->
                                add(buildJsonObject {
                                    put("piece", k.hitterFrame)
                                    put("victim", "enemy")
                                    put("point", vecJson(k.pointWorld))
                                    put("normal", vecJson(k.normalWorld))
                                })
                            }
                        })
                        put("particles", buildJsonArray {
                            for (b in cap.particles[t].orEmpty()) add(buildJsonObject {
                                put("particle", b.particle); put("at", vecJson(b.at)); put("count", b.count)
                                put("dir", b.dir?.let { vecJson(it) } ?: JsonNull)
                                put("spreadDeg", b.spreadDeg); put("speed", b.speed)
                                put("color", b.colorHex?.let { JsonPrimitive(it) } ?: JsonNull)
                            })
                        })
                        put("sounds", JsonArray(cap.sounds[t].orEmpty()))
                        put("damage", JsonArray(cap.damage[t].orEmpty()))
                        put("casterBody", JsonNull)
                    })
                }
            })
        }
        val out = Path.of("build", "sim-traces", "v2", "${skill.def.id}.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, doc.toString())
    }
}
