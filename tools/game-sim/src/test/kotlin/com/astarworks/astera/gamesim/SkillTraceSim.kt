package com.astarworks.astera.gamesim

import java.util.UUID
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
import com.astarworks.astera.core.application.skillengine.runtime.port.DisplayView
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo
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
import com.astarworks.astera.core.domain.skillengine.hit.ContactResolver
import com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume
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

internal val caster: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c0a1")
internal val dummy: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d033")

internal class Snapshot(
    val tick: Int,
    val poses: Map<String, Frame>,
    val parents: Map<String, String?>,
    val kinds: Map<String, String>,
    val events: List<CastEvent>,
) {
    val origins: Map<String, Vec3> get() = poses.mapValues { it.value.origin }
}

internal class Recorder : CastObserver {
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
internal class Capture {
    var now = 0
    class Obj(val handle: Long, val kind: String, val spawnTick: Int, val fields: JsonObject, var removeTick: Int? = null)
    val objects = LinkedHashMap<Long, Obj>()
    val objectUpdates = HashMap<Int, MutableMap<Long, JsonObject>>()
    val particles = HashMap<Int, MutableList<ParticleBurst>>()
    val sounds = HashMap<Int, MutableList<JsonObject>>()
    val damage = HashMap<Int, MutableList<JsonObject>>()
    val hitVolumes = HashMap<Int, MutableList<JsonObject>>()
    fun update(h: Long, v: JsonObject) { objectUpdates.getOrPut(now) { LinkedHashMap() }[h] = v }
}

internal fun poseJson(f: Frame): JsonArray = buildJsonArray {
    add(JsonPrimitive(f.origin.x)); add(JsonPrimitive(f.origin.y)); add(JsonPrimitive(f.origin.z))
    add(JsonPrimitive(f.rotation.x)); add(JsonPrimitive(f.rotation.y)); add(JsonPrimitive(f.rotation.z)); add(JsonPrimitive(f.rotation.w))
}

internal fun vecJson(v: Vec3): JsonArray = buildJsonArray { add(JsonPrimitive(v.x)); add(JsonPrimitive(v.y)); add(JsonPrimitive(v.z)) }

internal inline fun <reified T> noop(): T = java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
    when (m.returnType) {
        java.lang.Boolean.TYPE -> true
        Integer.TYPE -> 0
        java.lang.Double.TYPE -> 0.0
        java.lang.Long.TYPE -> 0L
        else -> null
    }
} as T

internal class Gameplay(private val cap: Capture, base: ISkillGameplayGateway) : ISkillGameplayGateway by base {
    val damage = ArrayList<SkillDamage>()
    val statuses = ArrayList<Triple<String, Int, Int>>()
    val impulses = ArrayList<Vec3>()
    override fun damage(
        skillId: String, caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3,
        ignoreInvulnerability: Boolean, ignoreArmor: Boolean,
    ) {
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

internal class Sim(val c: Coin2Collaborators, val catalog: SkillCatalog, val withDummy: Boolean) {
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
        override fun sprite(sprite: String): com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo = com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo(1, 32)
        override fun setPlateFrame(handle: com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle, frame: Int, tintHex: String?) {}
        override fun setTrailLook(handle: com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle, fadePower: Double, style: String) {}
        override fun setTrailShape(handle: com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle, bands: List<com.astarworks.astera.core.domain.skillengine.op.TrailBand>, edge: com.astarworks.astera.core.domain.skillengine.op.TrailEdge?, rim: com.astarworks.astera.core.domain.skillengine.op.TrailRim?, stepDeg: Double, doubleSided: Boolean) {}
        override fun spawnMesh(castOwner: UUID, item: String, part: String?, pose: VisualPose, brightness: Int?, look: com.astarworks.astera.core.application.skillengine.runtime.port.MeshLook, view: DisplayView, glow: String?) =
            spawn("mesh", buildJsonObject { put("item", item); put("part", part?.let { JsonPrimitive(it) } ?: JsonNull); put("size", vecJsonOf(pose.size)) }, poseObj(pose.frame))
        override fun spawnSkinBone(castOwner: UUID, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose) =
            spawn("skin_bone", buildJsonObject { put("bone", bone.yamlName); put("ghost", ghost) }, poseObj(pose.frame))
        override fun spawnPlate(castOwner: UUID, sprite: String, alpha: Double, tintHex: String?, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView, glow: String?) =
            spawn("plate", buildJsonObject { put("sprite", sprite); put("billboard", pose.billboard.name); put("size", pose.size.x); put("alpha", alpha); put("tint", tintHex?.let { JsonPrimitive(it) } ?: JsonNull); put("see_through", seeThrough) }, poseObj(pose.frame))
        override fun spawnBlock(castOwner: UUID, block: String, pose: VisualPose, brightness: Int?, rigShading: Boolean, view: DisplayView, glow: String?) =
            spawn("block", buildJsonObject { put("block", block); put("size", vecJsonOf(pose.size)); put("brightness", brightness ?: -1) }, poseObj(pose.frame))
        override fun spawnQuad(castOwner: UUID, colorHex: String, alpha: Double, text: String?, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView, glow: String?) =
            spawn("quad", buildJsonObject { put("color", colorHex); put("alpha", alpha); put("text", text?.let { JsonPrimitive(it) } ?: JsonNull); put("size", vecJsonOf(pose.size)) }, poseObj(pose.frame))
        override fun spawnText(castOwner: UUID, text: String, backgroundHex: String, backgroundAlpha: Double, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView) =
            spawn("text", buildJsonObject { put("text", text); put("background", backgroundHex); put("background_alpha", backgroundAlpha); put("see_through", seeThrough); put("size", vecJsonOf(pose.size)) }, poseObj(pose.frame))
        override fun spawnLine(castOwner: UUID, colorHex: String, pose: VisualPose, view: DisplayView) =
            spawn("line", buildJsonObject { put("thickness", pose.size.y); put("length", pose.size.x); put("color", colorHex) }, poseObj(pose.frame))
        override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double) =
            spawn("trail", buildJsonObject { put("ticks", ticks); put("widthFrac", widthFrac); put("color", colorHex); put("alpha", alpha) }, buildJsonObject {})
        override fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int) = capture.update(handle.id, poseObj(pose.frame, interpolationTicks))
        override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) =
            capture.update(handle.id, buildJsonObject { put("root", vecJsonOf(root)); put("tip", vecJsonOf(tip)) })
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
    override fun healthRatio(entity: UUID): Double = error("hp_ratio: this fake has no entity $entity")
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
            portsIn = { _ -> SkillRuntimePorts(visuals, particles, sounds, gameplay, entities, anim) },
            movement = noop<ISkillMovementGateway>(),
            worldIn = { _ -> noop<ISkillWorldGateway>() },
            casterAnim = noop<ISkillCasterAnimGateway>(),
            screen = noop<ISkillScreenGateway>(),
            cooldowns = noop<ISkillCooldownGateway>(),
            items = noop<ISkillItemGateway>(),
            effects = noop<ISkillWorldEffectGateway>(),
            glow = noop<com.astarworks.astera.core.application.skillengine.runtime.ops.IEntityGlowGateway>(),
        ),
        newTree = c.newTree,
        motion = c.motion,
        contacts = RecordingContacts(c.contacts, capture),
        exprs = c.exprs,
        points = c.points,
        skills = catalog::get,
        observer = recorder,
    )
    val runner = kit.runner

    fun request(skill: CompiledSkill, tick: Int, modifiers: ModifierStack = ModifierStack.EMPTY) =
        CastRequest(skill, caster, com.astarworks.astera.core.domain.world.WorldId("world"), Vec3.ZERO, 0.0, 0.0, seed = 7L, modifiers = modifiers, startTick = tick)

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

/** `astera-sim-trace/2` (tools/vfx-shot/SIM-TRACE-V2.md) to `build/sim-traces/v2/<skill-id>.json`. */
internal fun writeTrace(skill: CompiledSkill, sim: Sim, cast: CastId, endTick: Int) {
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
        put("diagnostics", buildJsonArray {
            for (d in sim.runner.diagnostics()) add(JsonPrimitive("tick ${d.tick} ${d.opId ?: "-"}: ${d.message}"))
        })
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
                    put("hitVolumes", JsonArray(cap.hitVolumes[t].orEmpty()))
                    put("casterBody", JsonNull)
                })
            }
        })
    }
    val out = Path.of("build", "sim-traces", "v2", "${skill.def.id}.json")
    Files.createDirectories(out.parent)
    Files.writeString(out, doc.toString())
}

/** The real contact resolver, recording the hit volume it was asked to test each tick (the overlay of the preview). */
internal class RecordingContacts(private val inner: ContactResolver, private val cap: Capture) : ContactResolver {
    override fun narrow(
        hitId: String,
        tick: Int,
        hitterFrame: String,
        volume: ResolvedVolume,
        hitterVelocity: Vec3,
        candidates: List<HitCandidate>,
    ): List<ContactEvent> {
        cap.hitVolumes.getOrPut(cap.now) { ArrayList() } += buildJsonObject {
            put("hit", hitId)
            put("frame", hitterFrame)
            when (volume) {
                is ResolvedVolume.Capsule -> { put("type", "capsule"); put("a", vecJson(volume.a)); put("b", vecJson(volume.b)); put("radius", volume.radius) }
                is ResolvedVolume.Sphere -> { put("type", "sphere"); put("center", vecJson(volume.center)); put("radius", volume.radius) }
                is ResolvedVolume.Sector -> {
                    put("type", "sector"); put("origin", vecJson(volume.origin)); put("forward", vecJson(volume.forward))
                    put("left", vecJson(volume.left)); put("angleDeg", volume.angleDeg); put("range", volume.range); put("height", volume.height)
                }
            }
        }
        return inner.narrow(hitId, tick, hitterFrame, volume, hitterVelocity, candidates)
    }
}
