package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.runtime.presentation.CameraShakeRequest
import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.application.skillengine.load.presentation.presentationDecoders
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
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
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillCasterAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillScreenGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.PresentationCastInfo
import com.astarworks.astera.core.application.skillengine.runtime.presentation.PresentationRuntime
import com.astarworks.astera.core.application.skillengine.runtime.presentation.presentationExecutors
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.op.AnchorOrient
import com.astarworks.astera.core.domain.skillengine.op.PullKey
import com.astarworks.astera.core.domain.skillengine.frame.FrameError
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.frame.FrameNode
import com.astarworks.astera.core.domain.skillengine.frame.FramePoint
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import com.astarworks.astera.core.domain.skillengine.op.BodyOp
import com.astarworks.astera.core.domain.skillengine.op.FxOp
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import com.astarworks.astera.core.domain.skillengine.op.Repeat
import com.astarworks.astera.core.domain.skillengine.op.ScreenOp
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.TrackKey
import com.astarworks.astera.core.domain.skillengine.op.VisualOp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * W15 presentation ops (mesh, plate, line, trail, track, remove, emit, sound, victim/caster anim, title,
 * action bar, camera shake): real decoders + executors + PresentationRuntime against a fake frame tree
 * and recording gateways. Proves: ops produce the expected gateway calls, plates have exactly two face
 * modes, and presentation never moves a gameplay frame.
 */
class PresentationOpsScenarioTest {

    // ---- fakes -------------------------------------------------------------------------------------

    private class Tree : FrameTree {
        val nodes = HashMap<String, FrameNode>()
        val points = HashMap<Pair<String, String>, FramePoint>()
        override fun node(id: FrameId) = nodes[id.value]
        override fun children(id: FrameId) = nodes.values.filter { it.parent == id }.map { it.id }
        override fun add(node: FrameNode): Result<Unit, FrameError> { nodes[node.id.value] = node; return Result.Success(Unit) }
        override fun remove(id: FrameId) { nodes.remove(id.value) }
        override fun setLocal(id: FrameId, local: Frame): Result<Unit, FrameError> {
            val n = nodes[id.value] ?: return Result.Failure(FrameError.Unknown(id.value))
            nodes[id.value] = n.copy(local = local)
            return Result.Success(Unit)
        }
        override fun worldOf(id: FrameId): Result<Frame, FrameError> {
            val n = nodes[id.value] ?: return Result.Failure(FrameError.Unknown(id.value))
            val p = n.parent ?: return Result.Success(n.local)
            return worldOf(p).map { it.compose(n.local) }
        }
        override fun reparent(id: FrameId, newParent: FrameId, keepWorld: Boolean, snap: Frame?): Result<Unit, FrameError> =
            error("presentation must never reparent")
        override fun definePoint(point: FramePoint) { points[point.node.value to point.name] = point }
        override fun point(node: FrameId, name: String) = points[node.value to name]
        override fun pointWorld(node: FrameId, name: String?): Result<Vec3, FrameError> =
            worldOf(node).map { w -> name?.let { points[node.value to it] }?.let { w.toWorld(it.offset) } ?: w.origin }

        fun put(id: String, parent: String?, origin: Vec3, kind: FrameKind = FrameKind.PART) =
            add(FrameNode(FrameId(id), kind, parent?.let(::FrameId), Frame(origin)))
        fun snapshot() = nodes.mapValues { it.value.local }
    }

    private class Resolver(val params: Map<String, Double> = emptyMap(), val texts: Map<String, String> = emptyMap()) : ValueResolver {
        override fun num(value: Value<Double>) = when (value) {
            is Value.Literal -> value.value
            is Value.ParamRef -> params.getValue(value.name)
            is Value.Expr -> error("expr ${value.source}")
        }
        override fun vec3(value: Value<Vec3>) = (value as Value.Literal).value
        override fun quat(value: Value<Quaternion>) = (value as Value.Literal).value
        override fun bool(value: Value<Boolean>) = (value as Value.Literal).value
        override fun text(value: Value<String>) = when (value) {
            is Value.Literal -> value.value
            is Value.ParamRef -> texts.getValue(value.name)
            is Value.Expr -> error("expr")
        }
    }

    private val calls = ArrayList<String>()
    private var nextHandle = 1L
    private val poses = HashMap<Long, Frame>()
    private val scales = HashMap<Long, Double>()
    private val lanes = HashMap<Long, com.astarworks.astera.core.domain.effect.VfxLane>()
    private val teleports = HashMap<Long, Int?>()
    private val spawnedBillboards = HashMap<Long, com.astarworks.astera.core.domain.effect.DisplayBillboard>()
    private val lines = HashSet<Long>()
    private val meshLooks = ArrayList<com.astarworks.astera.core.application.skillengine.runtime.port.MeshLook>()
    private val blockShading = ArrayList<Boolean>()
    private fun ends(pose: VisualPose): Pair<Vec3, Vec3> {
        val half = pose.frame.directionToWorld(Vec3(1.0, 0.0, 0.0)) * (pose.size.x / 2.0)
        return (pose.frame.origin - half) to (pose.frame.origin + half)
    }
    private val lineEnds = HashMap<Long, Pair<Vec3, Vec3>>()
    private val trailPoints = ArrayList<Pair<Vec3, Vec3>>()
    private val plateFrames = ArrayList<Triple<Long, Int, String?>>()
    private val seeThroughPlates = ArrayList<Long>()
    private val textSpawns = ArrayList<List<Any>>()
    private val textSwaps = ArrayList<Pair<Int, String>>()
    private val trailLooks = ArrayList<Triple<Long, Double, String>>()
    private val sounds = ArrayList<Triple<String, Vec3, Pair<Double, Double>>>()
    private val bursts = ArrayList<ParticleBurst>()
    private val removed = ArrayList<Long>()
    private val raised = HashSet<String>()
    private val owner = UUID.randomUUID()
    private val victim = UUID.randomUUID()

    private val visuals = object : ISkillVisualGateway {
    override fun setTrailShape(handle: com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle, bands: List<com.astarworks.astera.core.domain.skillengine.op.TrailBand>, edge: com.astarworks.astera.core.domain.skillengine.op.TrailEdge?, rim: com.astarworks.astera.core.domain.skillengine.op.TrailRim?, stepDeg: Double, doubleSided: Boolean) {}
        private fun h(kind: String): VisualHandle { calls += kind; return VisualHandle(nextHandle++) }
        override fun spawnMesh(castOwner: UUID, item: String, part: String?, pose: VisualPose, brightness: Int?, look: com.astarworks.astera.core.application.skillengine.runtime.port.MeshLook, view: DisplayView, glow: String?) =
            h("mesh:$item").also { poses[it.id] = pose.frame; scales[it.id] = pose.size.x; meshLooks += look; lanes[it.id] = view.lane }
        override fun spawnSkinBone(castOwner: UUID, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose) =
            h("skin_bone:${bone.yamlName}").also { poses[it.id] = pose.frame; scales[it.id] = pose.size.x }
        override fun spawnPlate(castOwner: UUID, sprite: String, alpha: Double, tintHex: String?, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView, glow: String?) =
            h("plate:$sprite").also {
                poses[it.id] = pose.frame; scales[it.id] = pose.size.x; spawnedBillboards[it.id] = pose.billboard
                if (tintHex != null) plateFrames += Triple(it.id, 0, tintHex)
                if (seeThrough) seeThroughPlates += it.id
            }
        override fun spawnBlock(castOwner: UUID, block: String, pose: VisualPose, brightness: Int?, rigShading: Boolean, view: DisplayView, glow: String?) = h("block").also { poses[it.id] = pose.frame; scales[it.id] = pose.size.x; blockShading += rigShading }
        override fun spawnQuad(castOwner: UUID, colorHex: String, alpha: Double, text: String?, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView, glow: String?) = h("quad").also { poses[it.id] = pose.frame; scales[it.id] = pose.size.x }
        override fun spawnText(castOwner: UUID, text: String, backgroundHex: String, backgroundAlpha: Double, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView) = h("text").also {
            poses[it.id] = pose.frame; scales[it.id] = pose.size.x
            textSpawns += listOf(text, backgroundHex, backgroundAlpha, shadow, seeThrough)
        }
        override fun setText(handle: VisualHandle, text: String) { textSwaps += handle.id.toInt() to text }
        override fun localize(castOwner: UUID, byLocale: Map<String, String>): String = byLocale["ja"] ?: super.localize(castOwner, byLocale)
        override fun spawnLine(castOwner: UUID, colorHex: String, pose: VisualPose, view: DisplayView) =
            h("line:$colorHex").also { lineEnds[it.id] = ends(pose); lines += it.id }
        override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double) = h("trail")
        override fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int) {
            teleports[handle.id] = pose.teleportTicks
            if (handle.id in lines) lineEnds[handle.id] = ends(pose) else { poses[handle.id] = pose.frame; scales[handle.id] = pose.size.x }
        }
        override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) { trailPoints += root to tip }
        override fun remove(handle: VisualHandle) { removed += handle.id }
        override fun sprite(sprite: String) = SpriteInfo(if (sprite == "ring") 6 else 1, 32)
        override fun setPlateFrame(handle: VisualHandle, frame: Int, tintHex: String?) { plateFrames += Triple(handle.id, frame, tintHex) }
        override fun setTrailLook(handle: VisualHandle, fadePower: Double, style: String) { trailLooks += Triple(handle.id, fadePower, style) }
    }

    private val ports = SkillRuntimePorts(
        visuals = visuals,
        particles = object : ISkillParticleGateway { override fun emit(burst: ParticleBurst) { bursts += burst } },
        sounds = object : ISkillSoundGateway {
            override fun play(sound: String, at: Vec3, volume: Double, pitch: Double) { sounds += Triple(sound, at, volume to pitch) }
        },
        gameplay = object : ISkillGameplayGateway {
    override fun statusStacks(entity: UUID, statusId: String): Int = 0
            override fun removeStatus(victim: UUID, statusId: String) = Unit
            override fun cleanse(victim: UUID, negativeOnly: Boolean) = Unit
            override fun ignite(caster: UUID, victim: UUID, durationTicks: Int) = Unit
            override fun silence(caster: UUID, victim: UUID, durationTicks: Int) = Unit
            override fun modifyAttribute(caster: UUID, victim: UUID, attribute: String, operation: String, amount: Double, durationTicks: Int?) = Unit
            override fun restoreResource(victim: UUID, resource: String, amount: Double) = Unit
            override fun damage(skillId: String, caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3, ignoreInvulnerability: Boolean, ignoreArmor: Boolean) = error("gameplay")
            override fun applyStatus(caster: UUID, victim: UUID, statusId: String, stacks: Int, durationTicks: Int) = error("gameplay")
            override fun impulse(victim: UUID, vector: Vec3) = error("gameplay")
            override fun freezeEntities(entities: List<UUID>, ticks: Int) = error("gameplay")
        },
        entities = object : ISkillEntityQuery {
    override fun healthRatio(entity: UUID): Double = error("hp_ratio: this fake has no entity $entity")
            override fun nearby(point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate> = emptyList()
            override fun rootOf(entity: UUID): EntityRoot? = null
            override fun raycastBlock(from: Vec3, to: Vec3): BlockHit? = null
        },
        victimAnim = object : ISkillVictimAnimGateway {
            override fun play(victim: UUID, anim: String, strength: Double, awayFrom: Vec3) { calls += "victim_anim:$anim:$strength:${awayFrom.x}" }
        },
    )

    private val info = object : PresentationCastInfo {
        override fun owner(cast: CastId) = owner
        override fun targets(cast: CastId, target: TargetRef) = when (target) {
            TargetRef.Victim -> listOf(victim)
            TargetRef.Caster -> listOf(owner)
            else -> emptyList()
        }
        override fun hasRaised(cast: CastId, event: String) = event in raised
    }

    private val rt = PresentationRuntime(
        info,
        object : ISkillCasterAnimGateway {
            override fun clip(anim: String): com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation? = SimClips.clip(anim)
            override fun isSlim(caster: UUID) = false
            override fun play(caster: UUID, anim: String, speed: Double, ticks: Int) { calls += "caster_anim:$anim:$speed" }
            override fun stop(caster: UUID, anim: String) { calls += "caster_anim_stop:$anim" }
            override fun release(caster: UUID, anim: String) { calls += "caster_anim_release:$anim" }
        },
        object : ISkillScreenGateway {
            override fun title(viewer: UUID, text: String, subtitle: String?, ticks: Int) { calls += "title:$text:$subtitle:$ticks" }
            override fun actionBar(viewer: UUID, text: String) { calls += "action_bar:$text" }
            override fun cameraShake(viewer: UUID, shake: CameraShakeRequest) { calls += "shake:${shake.intensity}:${shake.ticks}" }
            override fun flash(viewer: UUID, overlay: com.astarworks.astera.core.domain.effect.ScreenOverlay, colorRgb: Int, fadeIn: Int, stay: Int, fadeOut: Int) {
                calls += "flash:${overlay.yamlName}:${colorRgb.toString(16)}:$fadeIn:$stay:$fadeOut"
            }
            override fun veil(viewer: UUID, sprite: String, colorRgb: Int, side: Double, distance: Double, ticks: Int) {
                calls += "veil:$sprite:${colorRgb.toString(16)}:$side:$distance:$ticks"
            }
            override fun stopSound(viewer: UUID, sound: String) { calls += "stop_sound:$sound" }
        },
    )

    private val tree = Tree().also {
        it.put("world", null, Vec3(0.0, 0.0, 0.0), FrameKind.WORLD)
        it.put("shears", "world", Vec3(0.0, 2.0, 0.0), FrameKind.ITEM)
        it.put("palm", "world", Vec3(0.0, 1.0, 0.0), FrameKind.POINT)
        it.put("volley", "world", Vec3(5.0, 2.0, 0.0), FrameKind.BODY)
        it.put("contact", "world", Vec3(6.0, 1.0, 0.0), FrameKind.CONTACT)
        it.definePoint(FramePoint(FrameId("shears"), "tip", Vec3(0.0, 0.0, 2.0)))
        it.definePoint(FramePoint(FrameId("shears"), "root", Vec3(0.0, 0.0, 0.0)))
        it.definePoint(FramePoint(FrameId("volley"), "axis", Vec3(0.0, 0.0, 0.0), axis = Vec3(0.0, 0.0, 1.0)))
    }

    private val executors: Map<Class<*>, OpExecutor<*>> =
        presentationExecutors(rt).associateBy { it.opClass.java }

    /** Presentation ops reach the cast host only for the caster look (emit), the caster id and the bound victim and the tick (display audience, camera shake); everything else would be a bug here. */
    private val lookOnlyHost: com.astarworks.astera.core.application.skillengine.runtime.OpHost = java.lang.reflect.Proxy.newProxyInstance(
        com.astarworks.astera.core.application.skillengine.runtime.OpHost::class.java.classLoader,
        arrayOf(com.astarworks.astera.core.application.skillengine.runtime.OpHost::class.java),
    ) { _, m, a -> if (m.name == "lookDirection") Vec3(0.0, 0.0, 1.0) else if (m.name == "withIndex") (a[2] as () -> Unit)() else if (m.name == "withSample") (a[1] as () -> Unit).invoke() else if (m.name == "getCurrentVictim") null else if (m.name == "getCaster") SIM_CASTER else if (m.name == "getTick") 0 else error("this scenario never reaches the cast host (${m.name})") } as com.astarworks.astera.core.application.skillengine.runtime.OpHost

    private val ctx = object : OpContext {
        override val host: com.astarworks.astera.core.application.skillengine.runtime.OpHost get() = lookOnlyHost
        override val cast = CastId(7)
        override val world = com.astarworks.astera.core.domain.world.WorldId("world")
        override val tree: FrameTree = this@PresentationOpsScenarioTest.tree
        override val values: ValueResolver = Resolver(params = mapOf("n" to 6.0), texts = mapOf("c" to "#ff5fa2"))
        override val ports = this@PresentationOpsScenarioTest.ports
        override fun raise(event: String) = error("presentation must not raise events")
        override fun run(ops: List<SkillOp>) { ops.forEach { this@PresentationOpsScenarioTest.run(it) } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun run(op: SkillOp): Unit = (executors.getValue(op.javaClass) as OpExecutor<SkillOp>).execute(op, ctx)

    private fun tick(times: Int = 1): Unit = repeat(times) { rt.tickCast(ctx.cast) }

    private fun lit(d: Double) = Value.Literal(d)
    private fun v(x: Double, y: Double, z: Double) = Value.Literal(Vec3(x, y, z))
    private fun near(a: Double, b: Double) = assertThat(a).isCloseTo(b, Offset.offset(1e-6))

    // ---- decoder -----------------------------------------------------------------------------------

    private class MapReader(val m: Map<String, Any?>) : OpReader {
        override fun textValue(key: String): Value<String>? = null
        override fun entries(key: String): List<OpReader> = emptyList()
        override val path = "test"
        override fun string(key: String) = m[key] as? String
        override fun requireString(key: String) = string(key) ?: fail("missing $key")
        override fun int(key: String) = (m[key] as? Number)?.toInt()
        override fun bool(key: String) = m[key] as? Boolean
        override fun number(key: String): Value<Double>? = when (val r = m[key]) {
            null -> null
            is Number -> Value.Literal(r.toDouble())
            is String -> if (r.startsWith("\${")) Value.ParamRef(r.removePrefix("\${").removeSuffix("}")) else Value.Expr(r.removePrefix("= "))
            else -> fail("bad number")
        }
        override fun requireNumber(key: String) = number(key) ?: fail("missing $key")
        override fun vec3(key: String): Value<Vec3>? = (m[key] as? List<*>)?.let { l -> Value.Literal(Vec3((l[0] as Number).toDouble(), (l[1] as Number).toDouble(), (l[2] as Number).toDouble())) }
        override fun boolValue(key: String): Value<Boolean>? = null
        override fun point(key: String) = (m[key] as? String)?.let(PointRef::parse)
        override fun stringList(key: String) = (m[key] as? List<*>)?.map { it as String }
        override fun ops(key: String): List<SkillOp> = emptyList()
        override fun raw(key: String) = m[key]
        override fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    }

    private fun decode(op: String, m: Map<String, Any?>): SkillOp {
        val d = presentationDecoders().first { op in it.opNames }
        assertThat(d.section).isEqualTo(OpSection.PRESENTATION)
        return d.decode(op, "id_$op", null, MapReader(m))
    }

    @Test
    fun `mesh carries item_model display_mode and rig_shading to the gateway and a rig part block sits at its offset`() {
        val mesh = decode("mesh", mapOf("frame" to "volley", "item" to "minecraft:iron_nugget", "item_model" to "astera:bolt", "display_mode" to "none", "rig_shading" to false)) as VisualOp.Mesh
        assertThat(mesh.itemModel).isEqualTo("astera:bolt")
        assertThat(mesh.displayMode).isEqualTo(com.astarworks.astera.core.domain.effect.DisplayItemMode.NONE)
        assertThatThrownBy { decode("mesh", mapOf("frame" to "volley", "item" to "minecraft:stick", "display_mode" to "sideways")) }.isInstanceOf(IllegalArgumentException::class.java)
        run(mesh)
        val look = meshLooks.single()
        assertThat(look.itemModel).isEqualTo("astera:bolt")
        assertThat(look.mode).isEqualTo(com.astarworks.astera.core.domain.effect.DisplayItemMode.NONE)
        assertThat(look.rigShading).isFalse()

        // A rig part: a block at `at` in its rig frame, lit by the weapon-rig shader rule.
        val part = decode("block", mapOf("id" to "part", "parent" to "volley", "block" to "iron_block", "size" to listOf(1.0, 1.0, 1.0),
            "at" to listOf(0.0, 0.0, 2.0), "rig_shading" to true)) as VisualOp.Block
        run(part)
        assertThat(blockShading.single()).isTrue()
        val origin = poses.getValue(2L).origin
        near(origin.x, 5.0)
        near(origin.y, 2.0)
        near(origin.z, 2.0)
    }

    @Test
    fun `decoders cover every presentation op and a plate has exactly two face modes`() {
        val names = presentationDecoders().flatMap { it.opNames }.toSet()
        assertThat(names).containsExactlyInAnyOrder(
            "mesh", "plate", "block", "quad", "text", "line", "trail", "track", "remove", "rig", "emit", "sound",
            "victim_anim", "caster_anim", "glow", "title", "action_bar", "camera_shake", "screen_flash", "screen_veil", "stop_sound", "anchor", "pull",
        )
        val viewer = decode("plate", mapOf("parent" to "volley", "sprite" to "slash_fan", "face" to mapOf("viewer" to "vertical"))) as VisualOp.Plate
        assertThat(viewer.face).isEqualTo(PlateFace.ViewerVertical)
        val locked = decode("plate", mapOf("parent" to "volley", "sprite" to "s", "face" to mapOf("viewer" to "axis", "axis" to "world.up"))) as VisualOp.Plate
        assertThat(locked.face).isEqualTo(PlateFace.ViewerAxis(AxisRef("world.up")))
        val plane = decode("plate", mapOf("parent" to "volley", "sprite" to "s", "face" to mapOf("plane" to mapOf("frame" to "volley", "axes" to listOf("axis", "up"))))) as VisualOp.Plate
        assertThat(plane.face).isEqualTo(PlateFace.Plane("volley", AxisRef("axis"), AxisRef("up")))
        for (bad in listOf(
            mapOf("euler" to listOf(0, 90, 0)),
            mapOf("viewer" to "center", "plane" to mapOf("frame" to "volley", "axes" to listOf("axis", "up"))),
            mapOf("viewer" to "axis"),
            mapOf("viewer" to "center", "axis" to "world.up"),
            mapOf("plane" to mapOf("frame" to "volley", "axes" to listOf("axis"))),
        )) {
            assertThatThrownBy { decode("plate", mapOf("parent" to "volley", "sprite" to "s", "face" to bad)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { decode("plate", mapOf("parent" to "volley", "sprite" to "s")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `track decodes keys with value forms and rejects unknown channels`() {
        val t = decode(
            "track",
            mapOf(
                "target" to "m", "space" to "world",
                "keys" to listOf(
                    mapOf("t" to 0, "offset" to listOf(0, 0, 0)),
                    mapOf("t" to 4, "offset" to listOf(0, 2, 0), "scale" to "\${n}", "ease" to "out_quad"),
                ),
            ),
        ) as VisualOp.Track
        assertThat(t.keys.map { it.tick }).containsExactly(0, 4)
        assertThat(t.keys[1].scale).isEqualTo(Value.ParamRef("n"))
        assertThat(t.space).isEqualTo("world")
        assertThatThrownBy { decode("track", mapOf("target" to "m", "keys" to listOf(mapOf("t" to 0, "pos" to listOf(0, 0, 0))))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---- executors ---------------------------------------------------------------------------------

    @Test
    fun `ops attach to frames and points and never move a frame`() {
        val before = tree.snapshot()

        run(VisualOp.Mesh("mesh_shears", null, "shears", "couture-shears", null, null))
        run(VisualOp.Plate("fan_plane", null, "volley", PlateFace.Plane("volley", AxisRef("axis"), AxisRef("up")), "slash_fan", lit(2.0), lit(0.5)))
        run(VisualOp.Plate("fan_view", null, "volley", PlateFace.ViewerCenter, "glow", lit(1.0), lit(1.0)))
        run(VisualOp.Line("thread", null, PointRef("palm"), PointRef("volley"), lit(0.06), Value.ParamRef("c"), PlateFace.ViewerAxis(AxisRef("line.along"))))
        run(VisualOp.Trail("trail_a", null, PointRef("shears", "tip"), PointRef("shears", "root"), 6, lit(0.6), Value.ParamRef("c"), lit(0.35), "release"))

        assertThat(calls).containsExactly("mesh:couture-shears", "plate:slash_fan", "plate:glow", "line:#ff5fa2", "trail")
        // mesh sits on the shears frame
        assertThat(poses[1]!!.origin).isEqualTo(Vec3(0.0, 2.0, 0.0))
        // plane plate: lies in the plane of axis (+Z) and up (+Y), so its normal is Z x Y = -X; origin = volley
        val plane = poses[2]!!
        assertThat(plane.origin).isEqualTo(Vec3(5.0, 2.0, 0.0))
        val n = plane.forward()
        near(n.x, -1.0); near(n.y, 0.0); near(n.z, 0.0)
        // viewer plate keeps the parent pose and its face mode is passed to the adapter untouched
        assertThat(poses[3]!!).isEqualTo(Frame(Vec3(5.0, 2.0, 0.0)))
        assertThat(spawnedBillboards[3]).isEqualTo(com.astarworks.astera.core.domain.effect.DisplayBillboard.CENTER)
        near(lineEnds[4]!!.first.x, 0.0); near(lineEnds[4]!!.first.y, 1.0); near(lineEnds[4]!!.first.z, 0.0)
        assertThat(lineEnds[4]!!.second).isEqualTo(Vec3(5.0, 2.0, 0.0))

        // the shears move (gameplay does that); presentation follows next tick
        tree.setLocal(FrameId("shears"), Frame(Vec3(1.0, 3.0, 0.0)))
        tree.setLocal(FrameId("volley"), Frame(Vec3(8.0, 2.0, 0.0)))
        val moved = tree.snapshot()
        tick()
        assertThat(tree.snapshot()).isEqualTo(moved)
        assertThat(poses[1]!!.origin).isEqualTo(Vec3(1.0, 3.0, 0.0))
        assertThat(lineEnds[4]!!.second).isEqualTo(Vec3(8.0, 2.0, 0.0))
        assertThat(trailPoints.last()).isEqualTo(Vec3(1.0, 3.0, 0.0) to Vec3(1.0, 3.0, 2.0))
        assertThat(moved.keys - setOf("mesh_shears", "fan_plane", "fan_view")).isEqualTo(before.keys) // only the spawned objects joined the tree

        // trail stops at `release`, ribbon leaves after its own length
        raised += "release"
        val n0 = trailPoints.size
        val lastBefore = trailPoints.last()
        // the runtime keeps re-pushing the last cross-section so the quads age (v1 fades them after the trail ends, VfxRigPlanner.kt:313-362); no new geometry
        tick(7)
        assertThat(trailPoints.drop(n0).distinct()).isEqualTo(listOf(lastBefore))
        assertThat(removed).contains(5L)

        // remove by op id
        run(VisualOp.Remove("fan_off", null, listOf("fan_plane", "thread")))
        assertThat(removed).contains(2L, 4L)
        val after = poses[2]
        tree.setLocal(FrameId("volley"), Frame(Vec3(9.0, 2.0, 0.0)))
        tick()
        assertThat(poses[2]).isEqualTo(after)
    }

    @Test
    fun `track keyframes only the visual object, relative to its frame or a named space`() {
        run(VisualOp.Mesh("m", null, "shears", "couture-shears", null, null))
        run(
            VisualOp.Track(
                "swing", null, "m",
                listOf(TrackKey(0, v(0.0, 0.0, 0.0), null, v(1.0, 1.0, 1.0), null), TrackKey(4, v(0.0, 2.0, 0.0), null, v(3.0, 3.0, 3.0), null)),
                null,
            ),
        )
        val frames = tree.snapshot()
        tick(3) // cast tick 2 pushes the value of key tick 2 (v1 VfxPlayer.kt:365-375 age++, :429 `u.tick <= age`)
        assertThat(poses[1]!!.origin.y).isCloseTo(3.0, Offset.offset(1e-9)) // 2.0 base + 1.0 (half of 2.0)
        near(scales[1]!!, 2.0)
        tick(10)
        near(poses[1]!!.origin.y, 4.0) // holds the last key
        near(scales[1]!!, 3.0)
        // gameplay frames never move; the tracked object is itself a node and holds the tracked pose (ADR-0303)
        assertThat(tree.snapshot() - "m").isEqualTo(frames - "m")
        near(tree.snapshot().getValue("m").origin.y, 2.0) // local to its parent: the track offset

        // `space: world` expresses the offset in world axes even when the frame is rotated
        tree.setLocal(FrameId("shears"), Frame(Vec3(0.0, 2.0, 0.0), Quaternion.axisAngle(Vec3(1.0, 0.0, 0.0), Math.PI / 2)))
        run(VisualOp.Track("lift", null, "m", listOf(TrackKey(0, v(0.0, 1.0, 0.0), null, null, null)), "world"))
        tick()
        near(poses[1]!!.origin.y, 2.0 + 1.0) // swing's +2 is along the rotated frame's up (now world Z)
    }

    @Test
    fun `sound repeats until its event, emit is deterministic and inherits frame velocity`() {
        run(FxOp.Sound("whoosh", null, "couture.whoosh", PointRef("shears", "tip"), lit(0.8), lit(1.1), 4, "release"))
        assertThat(sounds).hasSize(1)
        assertThat(sounds[0].second).isEqualTo(Vec3(0.0, 2.0, 2.0))
        tick(9) // plays at ticks 4 and 8
        assertThat(sounds).hasSize(3)
        raised += "release"
        tick(8)
        assertThat(sounds).hasSize(3)

        tree.setLocal(FrameId("palm"), Frame(Vec3(0.0, 1.0, 0.0)))
        run(FxOp.Emit("puff", null, PointRef("palm"), "dust", lit(6.0), null, null, Value.ParamRef("c"), null, v(0.0, 0.0, -1.0), lit(20.0), lit(0.5), "s"))
        tick()
        // presentation runs after the tick's motion: the frame moved 2 blocks in the tick the second emit runs in
        tree.setLocal(FrameId("palm"), Frame(Vec3(0.0, 1.0, 2.0)))
        run(FxOp.Emit("puff", null, PointRef("palm"), "dust", lit(6.0), null, null, Value.ParamRef("c"), null, v(0.0, 0.0, -1.0), lit(20.0), lit(0.5), "s"))
        assertThat(bursts).hasSize(2)
        assertThat(bursts[0].count).isEqualTo(6)
        assertThat(bursts[0].colorHex).isEqualTo("#ff5fa2")
        assertThat(bursts[1].seed).isEqualTo(bursts[0].seed)
        assertThat(bursts[1].inheritVelocity).isEqualTo(Vec3(0.0, 0.0, 1.0)) // 2 blocks last tick * 0.5
    }

    @Test
    fun `when guard, body and screen ops reach their gateways`() {
        run(FxOp.Sound("never", Value.Literal(false), "x", PointRef("palm"), null, null, null, null))
        assertThat(sounds).isEmpty()

        run(BodyOp.VictimAnim("stagger", null, TargetRef.Victim, "stagger_back", lit(0.7)))
        run(BodyOp.CasterAnim("cast", null, "couture-s2-coin2", null, null, null))
        run(ScreenOp.Title("t", null, TargetRef.Caster, "COIN 2", "sub", 30))
        run(ScreenOp.ActionBar("ab", null, TargetRef.Caster, "bar"))
        run(ScreenOp.CameraShake("cs", null, TargetRef.Victim, lit(0.4), 6))
        assertThat(calls).containsExactly(
            "victim_anim:stagger_back:0.7:6.0", // away-from = the contact frame point
            "caster_anim:couture-s2-coin2:1.0",
            "title:COIN 2:sub:30",
            "action_bar:bar",
            "shake:0.4:6",
        )

        run(VisualOp.Mesh("m", null, "shears", "couture-shears", null, null))
        rt.endCast(ctx.cast)
        assertThat(removed).hasSize(1)
    }

    @Test
    fun `screen_veil clamps distance and side like v1 ScreenVeilMath and defaults the colour to white`() {
        val math = com.astarworks.astera.core.domain.effect.ScreenVeilMath
        run(ScreenOp.Veil("v", null, TargetRef.Caster, "burst", 0xFFC84A, lit(0.4), lit(1.4), lit(0.15), 5))
        run(ScreenOp.Veil("v2", null, TargetRef.Caster, "burst", null, lit(5.0), lit(20.0), lit(0.9), 3))
        val near = math.clampSide(0.2 * 1.4, 0.4, 0.15)
        val far = math.clampSide(0.2 * 20.0, 1.0, 0.9)
        assertThat(calls).containsExactly("veil:burst:ffc84a:$near:0.4:5", "veil:burst:ffffff:$far:1.0:3")
        assertThat(far).isLessThan(4.0) // the hard 0.18 screen-fraction cap, not the requested side
    }

    // ---- SKL-14 restored v1 behaviour --------------------------------------------------------------

    @Test
    fun `track clock - a keyframe at tick N is the pose pushed on tick N`() {
        run(VisualOp.Mesh("m", null, "shears", "couture-shears", null, null))
        run(VisualOp.Track("t", null, "m", listOf(TrackKey(0, v(0.0, 0.0, 0.0), null, null, null), TrackKey(5, v(0.0, 5.0, 0.0), null, null, null)), null))
        // v1 advances the element age once per tick and pushes every update with `tick <= age` on that tick (VfxPlayer.kt:365-375, :429)
        val shown = (1..6).map { tick(); poses[1]!!.origin.y - 2.0 }
        assertThat(shown.map { Math.round(it * 1000) / 1000.0 }).containsExactly(0.0, 1.0, 2.0, 3.0, 4.0, 5.0)
    }

    @Test
    fun `rig pivot rest and tilt - display is frame pivot tilt swing rest`() {
        // shears frame at (0,2,0), identity rotation. pivot = 1 left (+x), tilt rolls 90 about the frame axis, rest yaws 90
        val pivot = v(1.0, 0.0, 0.0)
        val rest = v(90.0, 0.0, 0.0)
        val tilt = v(0.0, 0.0, 0.0)
        run(VisualOp.Mesh("m", null, "shears", "couture-shears", null, null, pivot, rest, tilt))
        assertThat(poses[1]!!.origin).isEqualTo(Vec3(1.0, 2.0, 0.0))
        val expect = Quaternion.axisAngle(Vec3(1.0, 0.0, 0.0), Math.toRadians(90.0))
        assertThat(poses[1]!!.rotation.rotate(Vec3(0.0, 0.0, 1.0)).distance(expect.rotate(Vec3(0.0, 0.0, 1.0)))).isLessThan(1e-9)
        // a swing (track rotation) turns the model about the pivot AFTER rest, so rest stays the model's own orientation
        run(VisualOp.Track("sw", null, "m", listOf(TrackKey(0, null, v(0.0, 0.0, 0.0), null, null), TrackKey(1, null, v(0.0, 90.0, 0.0), null, null)), null))
        tick(3)
        val swung = Quaternion.axisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(90.0)) * expect
        assertThat(poses[1]!!.rotation.rotate(Vec3(0.0, 0.0, 1.0)).distance(swung.rotate(Vec3(0.0, 0.0, 1.0)))).isLessThan(1e-9)
        assertThat(poses[1]!!.origin).isEqualTo(Vec3(1.0, 2.0, 0.0)) // the pivot did not move
    }

    @Test
    fun `text op - spawns the markup with its background box and a track key swaps the text`() {
        run(VisualOp.Text("cipher", null, "volley", PlateFace.ViewerCenter, v(0.2, 0.2, 1.0), "<#397BDB><b>7#A", Value.Literal("#101628"), lit(0.627), shadow = false, seeThrough = true))
        assertThat(textSpawns).containsExactly(listOf("<#397BDB><b>7#A", "#101628", 0.627, false, true))
        run(VisualOp.Track("t", null, "cipher", listOf(TrackKey(0, v(0.0, 0.0, 0.0), null, null, null), TrackKey(2, null, null, null, null, text = "CLEAR")), null))
        tick(4)
        assertThat(textSwaps.map { it.second }.distinct()).containsExactly("CLEAR")
    }

    @Test
    fun `text op - text_by_locale is picked by the gateway for the cast owner and one cast spawns at most 32 texts`() {
        val d = decode("text", mapOf("parent" to "volley", "size" to listOf(0.2, 0.2), "text_by_locale" to mapOf("en" to "Quake!", "ja" to "クエイク！"))) as VisualOp.Text
        assertThat(d.text).isEqualTo("Quake!") // the fallback is the English entry
        assertThat(d.textByLocale).containsEntry("ja", "クエイク！")
        run(d.copy(id = "q0"))
        assertThat(textSpawns.map { it.first() }).containsExactly("クエイク！") // v1 messageRenderer.renderText(actorId, ..): the owner's locale
        for (k in 1 until 32) run(d.copy(id = "q$k"))
        assertThat(textSpawns).hasSize(32)
        assertThatThrownBy { run(d.copy(id = "q32")) }.isInstanceOf(IllegalStateException::class.java) // v1 MAX_DISPLAYS_PER_CAST = 32
        assertThat(textSpawns).hasSize(32)
    }

    @Test
    fun `text op instance - each firing is its own world-fixed display at the resolved origin with v1 yaw 0, and the enemy moving later moves nothing`() {
        // v1 WorldMechanics.display (:471) resolves the Location once per firing: TargeterResolver.resolveLocations (:120) returns bare Vec3s
        // and PaperDisplayEntityGateway.spawn (:146) builds Location(world, x, y, z), so yaw = pitch = 0 whatever way the enemy faces.
        tree.add(FrameNode(FrameId("enemy"), FrameKind.ENEMY, FrameId("world"), Frame(Vec3(10.0, 0.0, 0.0), Quaternion.axisAngle(Vec3(0.0, 1.0, 0.0), Math.PI / 2))))
        val plane = PlateFace.Plane("world", AxisRef("left"), AxisRef("up"))
        fun quake() = VisualOp.Text("quake", null, "enemy", plane, v(0.2, 0.2, 1.0), "Quake!", Value.Literal("#000000"), lit(0.25),
            at = v(0.0, 2.5, 0.0), repeat = Repeat(lit(1.0), 0, 5, instance = true))
        run(VisualOp.Text("ref", null, "world", plane, v(0.2, 0.2, 1.0), "ref", Value.Literal("#000000"), lit(0.25))) // a text on the world frame: yaw 0
        val worldFacing = poses.getValue(1L).rotation
        run(quake())
        tick(2)
        tree.setLocal(FrameId("enemy"), Frame(Vec3(20.0, 0.0, 5.0), Quaternion.axisAngle(Vec3(0.0, 1.0, 0.0), -Math.PI / 3)))
        run(quake())
        tick(3)
        near(poses.getValue(2L).origin.x, 10.0); near(poses.getValue(2L).origin.y, 2.5); near(poses.getValue(2L).origin.z, 0.0)
        near(poses.getValue(3L).origin.x, 20.0); near(poses.getValue(3L).origin.y, 2.5); near(poses.getValue(3L).origin.z, 5.0)
        for (id in listOf(2L, 3L)) {
            val r = poses.getValue(id).rotation
            near(r.x, worldFacing.x); near(r.y, worldFacing.y); near(r.z, worldFacing.z); near(r.w, worldFacing.w)
        }
        assertThat(removed).isEmpty() // the second firing did not replace the first
        tick(2)
        assertThat(removed).containsExactly(2L) // the first lives `life` ticks after its own spawn, the second is untouched
        tick(3)
        assertThat(removed).containsExactly(2L, 3L)
        assertThat(tree.nodes.keys.filter { it.startsWith("quake") }).isEmpty() // instance groups leave no frame behind
    }

    @Test
    fun `plate flipbook - frame follows age over frame_ticks, loops or holds, and carries the tint`() {
        run(VisualOp.Plate("held", null, "volley", PlateFace.ViewerCenter, "ring", lit(1.0), lit(1.0), 2, false, Value.Literal("#FFE070")))
        run(VisualOp.Plate("looped", null, "volley", PlateFace.ViewerCenter, "ring", lit(1.0), lit(1.0), 1, true, null))
        run(VisualOp.Plate("still", null, "volley", PlateFace.ViewerCenter, "glow", lit(1.0), lit(1.0), 1, true, null, seeThrough = true))
        assertThat(seeThroughPlates).containsExactly(3L) // see_through reaches the gateway at spawn
        assertThat(plateFrames).containsExactly(Triple(1L, 0, "#FFE070")) // tint applied at spawn
        tick(14)
        val held = plateFrames.filter { it.first == 1L }.map { it.second }
        val looped = plateFrames.filter { it.first == 2L }.map { it.second }
        assertThat(held).containsExactly(0, 1, 2, 3, 4, 5) // 6 frames x 2 ticks, then holds the last
        assertThat(looped.take(8)).containsExactly(1, 2, 3, 4, 5, 0, 1, 2) // one frame per tick, wraps
        assertThat(plateFrames.none { it.first == 3L }).isTrue() // a one-frame sprite is never swapped
    }

    @Test
    fun `trail style and fade power reach the gateway only when authored`() {
        run(VisualOp.Trail("a", null, PointRef("shears", "tip"), PointRef("shears", "root"), 6, lit(0.6), Value.ParamRef("c"), lit(1.0), null))
        run(VisualOp.Trail("b", null, PointRef("shears", "tip"), PointRef("shears", "root"), 6, lit(0.6), Value.ParamRef("c"), lit(1.0), null, lit(0.3), "both"))
        assertThat(trailLooks).containsExactly(Triple(2L, 0.3, "both"))
        assertThat((decode("trail", mapOf("of" to "shears.tip", "ticks" to 5, "fade" to 0.3, "style" to "crisp")) as VisualOp.Trail).style).isEqualTo("crisp")
        assertThatThrownBy { decode("trail", mapOf("of" to "shears.tip", "ticks" to 5, "style" to "neon")) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `anchor places a world frame by v1 orient, follow keeps the spawn rotation`() {
        tree.put("caster", "world", Vec3(0.0, 1.0, -4.0), FrameKind.CASTER)
        run(VisualOp.Anchor("a1", null, "volley", PointRef("contact"), AnchorOrient.WORLD))
        assertThat(tree.nodes.getValue("volley").local).isEqualTo(Frame(Vec3(6.0, 1.0, 0.0)))

        run(VisualOp.Anchor("a2", null, "volley", PointRef("contact"), AnchorOrient.YAW))
        val yaw = tree.worldOf(FrameId("volley")).getOrNull()!!
        near(yaw.forward().x, 0.0); near(yaw.forward().z, 1.0) // caster look (0,0,1), flattened

        run(VisualOp.Anchor("a3", null, "volley", PointRef("contact"), AnchorOrient.TO_TARGET))
        val toward = tree.worldOf(FrameId("volley")).getOrNull()!!.forward()
        val len = Math.sqrt(6.0 * 6.0 + 4.0 * 4.0)
        near(toward.x, 6.0 / len); near(toward.y, 0.0); near(toward.z, 4.0 / len)

        run(VisualOp.Anchor("a4", null, "volley", PointRef("contact"), AnchorOrient.TO_TARGET, follow = true))
        val spawnRotation = tree.worldOf(FrameId("volley")).getOrNull()!!.rotation
        tree.setLocal(FrameId("contact"), Frame(Vec3(9.0, 1.0, 3.0)))
        tick()
        val followed = tree.worldOf(FrameId("volley")).getOrNull()!!
        assertThat(followed.origin).isEqualTo(Vec3(9.0, 1.0, 3.0))
        assertThat(followed.rotation).isEqualTo(spawnRotation)

        run(VisualOp.Anchor("a5", null, "volley", PointRef("contact"), AnchorOrient.WORLD)) // not following any more
        tree.setLocal(FrameId("contact"), Frame(Vec3(1.0, 1.0, 1.0)))
        tick()
        assertThat(tree.worldOf(FrameId("volley")).getOrNull()!!.origin).isEqualTo(Vec3(9.0, 1.0, 3.0))

        val d = decode("anchor", mapOf("frame" to "volley", "at" to "contact", "orient" to "to_target", "follow" to true)) as VisualOp.Anchor
        assertThat(d.orient).isEqualTo(AnchorOrient.TO_TARGET)
        assertThat(d.follow).isTrue()
        assertThatThrownBy { decode("anchor", mapOf("frame" to "volley", "at" to "contact", "orient" to "sideways")) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `pull shifts its target toward the tracked point by the keyed weight`() {
        run(VisualOp.Mesh("m", null, "shears", "couture-shears", null, null))
        run(VisualOp.Pull("m_pull", null, "m", PointRef("palm"), PointRef("contact"), listOf(PullKey(0, 0.0), PullKey(4, 1.0)), false, emptyList()))
        tick()
        val early = poses[1]!!.origin.x
        tick(12)
        // palm (0,1,0) -> contact (6,1,0): the held weight is 1.0, the mesh sits 6 along +X from its frame
        near(poses[1]!!.origin.x, 6.0)
        near(poses[1]!!.origin.y, 2.0)
        assertThat(early).isLessThan(6.0)
    }

    @Test
    fun `lane reaches the spawned pose and decodes`() {
        run(VisualOp.Mesh("core", null, "shears", "couture-shears", null, null))
        run(VisualOp.Mesh("amb", null, "shears", "couture-shears", null, null, view = com.astarworks.astera.core.domain.skillengine.op.ViewScope(lane = com.astarworks.astera.core.domain.effect.VfxLane.AMBIENT)))
        assertThat(lanes[1]).isEqualTo(com.astarworks.astera.core.domain.effect.VfxLane.CORE)
        assertThat(lanes[2]).isEqualTo(com.astarworks.astera.core.domain.effect.VfxLane.AMBIENT)
        val d = decode("mesh", mapOf("frame" to "shears", "item" to "x", "lane" to "accent")) as VisualOp.Mesh
        assertThat(d.view.lane).isEqualTo(com.astarworks.astera.core.domain.effect.VfxLane.ACCENT)
        assertThatThrownBy { decode("mesh", mapOf("frame" to "shears", "item" to "x", "lane" to "loud")) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `followed anchor teleports its displays over teleport_ticks and the live pose is read only without pose`() {
        run(VisualOp.Anchor("a", null, "volley", PointRef("contact"), AnchorOrient.WORLD, follow = true, teleportTicks = 2))
        run(VisualOp.Mesh("m", null, "volley", "couture-shears", null, null))
        tick()
        assertThat(teleports[1]).isEqualTo(0) // the spawn write teleports over 0; the follow loop starts the tick after (VfxPlayer.kt:365-375, :620)
        tick()
        assertThat(teleports[1]).isEqualTo(2)
        run(VisualOp.Anchor("b", null, "volley", PointRef("contact"), AnchorOrient.WORLD))
        tick()
        assertThat(teleports[1]).isEqualTo(0) // no longer followed and not moved: not teleported (v1 duration 0)

        val d = decode("anchor", mapOf("frame" to "volley", "at" to "contact", "follow" to true, "pitch" to true, "pose" to "snap", "teleport_ticks" to 3)) as VisualOp.Anchor
        assertThat(d.pitch).isTrue()
        assertThat(d.pose).isEqualTo("snap")
        assertThat(d.teleportTicks).isEqualTo(3)
        assertThatThrownBy { decode("anchor", mapOf("frame" to "volley", "at" to "contact", "teleport_ticks" to 60)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `server floor drops ambient then accent lanes as the live-display budget fills`() {
        val q = com.astarworks.astera.core.domain.effect.VfxQualityProfile
        val full = com.astarworks.astera.core.domain.effect.VfxIntensity.FULL
        val lane = com.astarworks.astera.core.domain.effect.VfxLane.entries.associateBy { it.name }
        assertThat(q.serverFloor(0, 100)).isEqualTo(full)
        assertThat(q.admits(q.serverFloor(70, 100), lane.getValue("AMBIENT"))).isFalse()
        assertThat(q.admits(q.serverFloor(70, 100), lane.getValue("ACCENT"))).isTrue()
        assertThat(q.admits(q.serverFloor(90, 100), lane.getValue("ACCENT"))).isFalse()
        assertThat(q.admits(q.serverFloor(90, 100), lane.getValue("CORE"))).isTrue()
        assertThat(q.admits(q.serverFloor(1, 0), lane.getValue("CORE"))).isTrue()
    }
}
