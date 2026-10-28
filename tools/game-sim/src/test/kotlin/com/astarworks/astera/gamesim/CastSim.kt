package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.load.SkillCatalog
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.CastObserver
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineContents
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineGateways
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineKit
import com.astarworks.astera.core.application.skillengine.runtime.ops.ISkillWorldEffectGateway
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockDamageOutcome
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillCooldownGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVictimAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillCasterAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillScreenGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID
import com.astarworks.astera.core.domain.world.WorldId

/** The point `dist` in front of / `side` left of / `up` above [origin] for a caster facing [yawDeg] (Minecraft: yaw 0 faces +Z, left is +X). */
fun ahead(origin: Vec3, yawDeg: Double, dist: Double, side: Double = 0.0, up: Double = 0.0): Vec3 {
    val y = Math.toRadians(yawDeg)
    val fx = -Math.sin(y); val fz = Math.cos(y)
    val lx = Math.cos(y); val lz = Math.sin(y)
    return Vec3(origin.x + fx * dist + lx * side, origin.y + up, origin.z + fz * dist + lz * side)
}

val SIM_CASTER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c0a1")

fun simVictim(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-0000000d%04d".format(n))

/**
 * The real cast runner (decoder, compiler, CEL, op executors via `SkillEngineKit`) over the shipped `content/skills`
 * with fake ports: damage is recorded, the entity query returns the given victims, nothing else happens. Shared by the
 * ported-grammar and the cast-smoke scenarios (SKL-09).
 */
class CasterAnimCall(val tick: Int, val verb: String, val anim: String, val ticks: Int, val speed: Double = 1.0)

class VictimAnimCall(val tick: Int, val victim: UUID, val anim: String, val strength: Double)

class Dealt(val tick: Int, val victim: UUID, val amount: Double, val ignoreInvulnerability: Boolean, val skill: String = "")

class CastSim(
    val victims: Map<UUID, Vec3>,
    val casterStacks: Map<String, Int> = emptyMap(),
    val blockAtZ: Double? = null,
    /** A flat floor whose top face is at this y (feet level on a superflat world, as in the e2e); null = no floor. */
    val floorY: Double? = null,
    /** Half the victim box width / its height; the default is a generous 1.6 x 1.8 box, real players are 0.6 wide. */
    val halfWidth: Double = 0.8,
    val boxHeight: Double = 1.8,
    private val particleGateway: ISkillParticleGateway? = null,
    private val visualGateway: ISkillVisualGateway? = null,
    /** Caster feet in world space and yaw (Minecraft degrees: 0 faces +Z). Victims / floor / wall are WORLD coordinates; use [ahead] to place them relative to the caster. */
    val origin: Vec3 = Vec3.ZERO,
    val yawDeg: Double = 0.0,
    /** Loads the skills from this directory instead of the shipped `content/skills` (scenario-local skills). */
    private val skillsDir: Path? = null,
    /** Caster look pitch in Minecraft degrees (negative = looking up). */
    val pitchDeg: Double = 0.0,
    /** Extra skills for grammar scenarios: id -> `skill.yaml` text, composed beside a copy of the shipped skills. */
    extraSkills: Map<String, String> = emptyMap(),
    /** The world the caster stands in; sound, particles and entity queries must be bound to it (see [soundWorlds]). */
    val castWorld: WorldId = WorldId("world"),
) {
    /** World each sound / particle burst / entity query was bound to, in call order. */
    val soundWorlds = ArrayList<WorldId>()
    val particleWorlds = ArrayList<WorldId>()
    val queryWorlds = ArrayList<WorldId>()

    /** Every live frame's world position after each tick: (tick, frame id, position). */
    val frameTrace = ArrayList<Triple<Int, String, Vec3>>()

    /** Positions of frame [id] by tick (a missing tick = the frame did not exist). */
    fun trace(id: String): Map<Int, Vec3> = frameTrace.filter { it.second == id }.associate { it.first to it.third }

    /** World point `dist` blocks in front of the caster and `side` to its left, at feet level plus `up`. */
    fun ahead(dist: Double, side: Double = 0.0, up: Double = 0.0): Vec3 = ahead(origin, yawDeg, dist, side, up)

    /** Largest distance from the caster feet of any live frame (except `world`) seen in a tick, with the offender. */
    var farthest: Pair<String, Double> = "" to 0.0
        private set
    /**
     * A frame hanging directly off `world` at exactly (0,0,0) (the `enemy` frame before a hit, a declared body before
     * `place`) is not placed yet; it and its children are not a placement.
     */
    private fun unplaced(tree: FrameTree, id: FrameId): Boolean {
        var cur: FrameId? = id
        while (cur != null) {
            val node = tree.node(cur) ?: return false
            if (node.parent?.value == "world" && node.local.origin == Vec3.ZERO) return true
            cur = node.parent
        }
        return false
    }

    private val farObserver = CastObserver { _, tick, tree, nodes, events ->
        for (ev in events) {
            val tgt = ev.contact?.let { ",\"contact\":true" } ?: ""
            gp += "{\"t\":$tick,\"e\":\"phase\",\"name\":${jq(ev.name)},\"source\":${ev.source?.let { jq(it) }}$tgt}"
        }
        for (n in nodes) {
            tree.worldOf(FrameId(n)).getOrNull()?.let { frameTrace += Triple(tick, n, it.origin) }
            if (n == "world" || unplaced(tree, FrameId(n))) continue
            val o = tree.worldOf(FrameId(n)).getOrNull()?.origin ?: continue
            val d = o.distance(origin)
            if (d > farthest.second) farthest = "$n@${"%.1f,%.1f,%.1f".format(o.x, o.y, o.z)}" to d
        }
    }

    private val caster: UUID = SIM_CASTER
    var now = 0
    val dealt = ArrayList<Dealt>()
    val sounds = ArrayList<Pair<Int, String>>()
    /** Every sound with its pitch: (tick, id, pitch). */
    val soundPitches = ArrayList<Triple<Int, String, Double>>()
    /** Movement lock calls on the caster: (tick, speed factor), `null` factor = unlock. */
    val moveLocks = ArrayList<Pair<Int, Double?>>()
    val removed = ArrayList<Pair<UUID, String>>()
    /** Caster body animation calls in order: tick, verb (play / stop / release), clip, hold ticks. */
    val casterAnims = ArrayList<CasterAnimCall>()
    /** Victim reactions played through `victim_anim`. */
    val victimAnims = ArrayList<VictimAnimCall>()
    /** Flip to false to simulate the caster dying or quitting (the movement gateway then reports it gone). */
    var casterAlive: Boolean = true
    /**
     * Opt-in hit points for every victim (null = victims are immortal, as in the ordinary scenarios). A victim whose hp
     * reaches 0 logs `death`, stops being a target and is parked at the origin: v1's reference world respawns a dead
     * dummy at the spawn point, which the gameplay parity dump has to reproduce to line up.
     */
    var victimHp: Double? = null
    private val hp = HashMap<UUID, Double>()
    private val dead = HashSet<UUID>()
    /** Statuses applied through the gameplay gateway: (tick, victim, status id). */
    val statuses = ArrayList<Triple<Int, UUID, String>>()
    /** Hit-stop calls: (tick, frozen entities, ticks). */
    val frozen = ArrayList<Triple<Int, List<UUID>, Int>>()
    val blockStrikes = ArrayList<Triple<Int, Int, String?>>()

    /**
     * Gameplay parity log (SKL-17): one JSON object per line in the vocabulary of `tools/skill-convert/gameplay_diff.py`, in the
     * order the ports were called. Coordinates are relative to [origin]. Read by [GameplayDump]; the ports behave as before.
     */
    val gp = ArrayList<String>()

    /** Velocity last written to each entity (blocks per tick), as the sim of v1 kept it: `set` overwrites, `add` accumulates. */
    val velocities = HashMap<UUID, Vec3>()

    /** Where each entity stands: victims fixed, a teleported entity moves. The caster is [casterFeet]. */
    val standing = HashMap<UUID, Vec3>().also { m -> m.putAll(victims) }

    private fun r(d: Double): String { val v = Math.round(d * 1e3) / 1e3; return (if (v == 0.0) 0.0 else v).toString() }
    private fun jq(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ") + "\""
    fun rel(v: Vec3) = "\"x\":${r(v.x - origin.x)},\"y\":${r(v.y - origin.y)},\"z\":${r(v.z - origin.z)}"
    fun whoOf(e: UUID): String = if (e == SIM_CASTER) "caster" else if (e == victims.keys.firstOrNull()) "dummy" else e.toString()
    private fun cellAt(cell: BlockCell) = rel(Vec3(cell.x.toDouble(), cell.y.toDouble(), cell.z.toDouble()))
    private fun gpLine(kind: String, body: String) { gp += "{\"t\":$now,\"e\":\"$kind\"${if (body.isEmpty()) "" else ",$body"}}" }

    private inline fun <reified T> noop(): T = java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
        when (m.returnType) {
            java.lang.Boolean.TYPE -> true
            Integer.TYPE -> 0
            java.lang.Double.TYPE -> 0.0
            java.lang.Long.TYPE -> 0L
            else -> if (m.returnType == VisualHandle::class.java) VisualHandle(1) else null
        }
    } as T

    /** Where the caster stands now: starts at [origin] and follows every `teleport` (a dash moves it tick by tick). */
    var casterFeet: Vec3 = origin
        private set

    private val movement = object : ISkillMovementGateway by noop() {
        override fun isAlive(entity: UUID): Boolean = if (entity == caster) casterAlive else entity !in dead
        override fun teleport(entity: UUID, to: Vec3, yawDeg: Double?): Boolean {
            if (entity == caster) casterFeet = to
            standing[entity] = to
            gpLine("tp", "\"who\":\"${whoOf(entity)}\",${rel(to)}")
            return true
        }
        override fun setVelocity(entity: UUID, velocity: Vec3) { velocities[entity] = velocity }
        override fun addVelocity(entity: UUID, delta: Vec3) { velocities[entity] = (velocities[entity] ?: Vec3.ZERO) + delta }
        override fun grantInvulnerable(entity: UUID, safetyTicks: Int): Boolean { gpLine("invuln", "\"who\":\"${whoOf(entity)}\",\"on\":true,\"safety\":$safetyTicks"); return true }
        override fun releaseInvulnerable(entity: UUID) { gpLine("invuln", "\"who\":\"${whoOf(entity)}\",\"on\":false") }
        override fun lockMovement(entity: UUID, speedFactor: Double) { moveLocks += now to speedFactor; gpLine("lock", "\"who\":\"${whoOf(entity)}\",\"factor\":${r(speedFactor)}") }
        override fun unlockMovement(entity: UUID) { moveLocks += now to null; gpLine("lock", "\"who\":\"${whoOf(entity)}\",\"factor\":null") }
    }

    private val gameplay = object : ISkillGameplayGateway by noop() {
        override fun damage(caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3) =
            damage(caster, victim, components, hitPoint, false)
        override fun damage(caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3, ignoreInvulnerability: Boolean) =
            damage("", caster, victim, components, hitPoint, ignoreInvulnerability)
        override fun damage(skillId: String, caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3, ignoreInvulnerability: Boolean) {
            for (c in components) {
                dealt += Dealt(now, victim, c.amount, ignoreInvulnerability, skillId)
                gpLine("damage", "\"v\":\"${whoOf(victim)}\",\"amt\":${r(c.amount)},\"src\":${jq(skillId)},\"hit\":{${rel(hitPoint)}},\"ignoreIframes\":$ignoreInvulnerability")
                val max = victimHp
                if (max != null && victim != caster && victim !in dead) {
                    val left = (hp[victim] ?: max) - c.amount
                    hp[victim] = left
                    if (left <= 0.0) {
                        dead += victim
                        standing[victim] = origin
                        gpLine("tp", "\"who\":\"${whoOf(victim)}\",${rel(origin)}")
                        gpLine("death", "\"who\":\"${whoOf(victim)}\"")
                    }
                }
            }
        }
        override fun freezeEntities(entities: List<UUID>, ticks: Int) {
            frozen += Triple(now, entities, ticks)
            gpLine("freeze", "\"who\":${entities.joinToString(",", "[", "]") { jq(whoOf(it)) }},\"ticks\":$ticks")
        }
        override fun statusStacks(entity: UUID, statusId: String): Int = if (entity == caster) casterStacks[statusId] ?: 0 else 0
        override fun removeStatus(victim: UUID, statusId: String) {
            removed += victim to statusId
            gpLine("status", "\"who\":\"${whoOf(victim)}\",\"id\":${jq(statusId)},\"op\":\"removed\"")
        }
        override fun applyStatus(caster: UUID, victim: UUID, statusId: String, stacks: Int, durationTicks: Int) {
            statuses += Triple(now, victim, statusId)
            gpLine("status", "\"who\":\"${whoOf(victim)}\",\"id\":${jq(statusId)},\"op\":\"applied\",\"stacks\":$stacks,\"dur\":$durationTicks")
        }
        override fun cleanse(victim: UUID, negativeOnly: Boolean) { gpLine("cleanse", "\"who\":\"${whoOf(victim)}\",\"negativeOnly\":$negativeOnly") }
        override fun ignite(caster: UUID, victim: UUID, durationTicks: Int) { gpLine("status", "\"who\":\"${whoOf(victim)}\",\"id\":\"ignite\",\"op\":\"applied\",\"dur\":$durationTicks") }
        override fun silence(caster: UUID, victim: UUID, durationTicks: Int) { gpLine("status", "\"who\":\"${whoOf(victim)}\",\"id\":\"silence\",\"op\":\"applied\",\"dur\":$durationTicks") }
        override fun modifyAttribute(caster: UUID, victim: UUID, attribute: String, operation: String, amount: Double, durationTicks: Int?) {
            gpLine("attr", "\"who\":\"${whoOf(victim)}\",\"attr\":${jq(attribute)},\"op\":${jq(operation)},\"amount\":${r(amount)},\"dur\":$durationTicks")
        }
        override fun restoreResource(victim: UUID, resource: String, amount: Double) {
            gpLine("resource", "\"who\":\"${whoOf(victim)}\",\"res\":${jq(resource)},\"amount\":${r(amount)}")
        }
        override fun impulse(victim: UUID, vector: Vec3) {
            velocities[victim] = (velocities[victim] ?: Vec3.ZERO) + vector
            gpLine("kb", "\"who\":\"${whoOf(victim)}\",\"strength\":${r(vector.length())},\"vec\":[${r(vector.x)},${r(vector.y)},${r(vector.z)}],\"via\":\"impulse\"")
        }
    }

    private fun soundIn(w: WorldId) = object : ISkillSoundGateway {
        override fun play(sound: String, at: Vec3, volume: Double, pitch: Double) {
            sounds += now to sound; soundPitches += Triple(now, sound, pitch); soundWorlds += w
            gpLine("sound", "\"key\":${jq(sound)},${rel(at)},\"vol\":${r(volume)},\"pitch\":${r(pitch)}")
        }
    }

    private fun particlesIn(w: WorldId): ISkillParticleGateway {
        val inner = particleGateway ?: noop<ISkillParticleGateway>()
        return object : ISkillParticleGateway { override fun emit(burst: ParticleBurst) { particleWorlds += w; inner.emit(burst) } }
    }

    private fun entitiesIn(w: WorldId) = object : ISkillEntityQuery {
        override fun healthRatio(entity: UUID): Double = 1.0
        private fun box(feet: Vec3) = HitBox(Vec3(feet.x - halfWidth, feet.y, feet.z - halfWidth), Vec3(feet.x + halfWidth, feet.y + boxHeight, feet.z + halfWidth))
        override fun nearby(point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate> {
            queryWorlds += w
            return victims.filter { (id, feet) ->
                if (id in dead) return@filter false
                // Paper's getNearbyLivingEntities: the cube of half-size radius around the point must overlap the entity box.
                val b = box(feet)
                id != excluding && b.min.x <= point.x + radius && b.max.x >= point.x - radius &&
                    b.min.y <= point.y + radius && b.max.y >= point.y - radius &&
                    b.min.z <= point.z + radius && b.max.z >= point.z - radius
            }
                .map { (id, feet) -> HitCandidate(id, feet, 0.0, box(feet)) }
        }
        override fun rootOf(entity: UUID): EntityRoot? =
            if (entity == caster) EntityRoot(casterFeet, yawDeg, pitchDeg, 1.62, 1.8) else victims[entity]?.let { EntityRoot(it, 0.0, 0.0, 1.62, 1.8) }
        override fun raycastBlock(from: Vec3, to: Vec3): BlockHit? {
            val fy = floorY
            if (fy != null && from.y > fy && to.y <= fy) {
                val t = (from.y - fy) / (from.y - to.y)
                return BlockHit(from + (to - from) * t, Vec3(0.0, 1.0, 0.0), "minecraft:grass_block")
            }
            val z = blockAtZ ?: return null
            if (from.z > z || to.z < z || from.z == to.z) return null
            // Exact crossing of the wall plane along the segment (Paper: the face point of the ray trace).
            return BlockHit(from + (to - from) * ((z - from.z) / (to.z - from.z)), Vec3(0.0, 0.0, -1.0), "minecraft:stone")
        }
    }

    private val world = object : ISkillWorldGateway by noop() {
        // With a wall (blockAtZ) the only blocks are the cover wall z = blockAtZ, x -6..6, y 0..8 (the v1 reference scene); without one the world is open cover.
        private fun inWall(cell: BlockCell) = blockAtZ?.let { cell.z == it.toInt() && cell.x in -6..6 && cell.y in 0..8 } ?: true
        override fun blockAt(cell: BlockCell): String? = if (inWall(cell)) "minecraft:stone" else null
        override fun canEdit(cast: CastId, cell: BlockCell, outsideArena: Boolean) = true
        override fun isCover(cell: BlockCell) = inWall(cell)
        override fun damage(cast: CastId, cell: BlockCell, blockDamage: Int, toolClass: String?): BlockDamageOutcome {
            blockStrikes += Triple(now, blockDamage, toolClass)
            gpLine("block", "\"k\":\"damage\",${cellAt(cell)},\"amount\":$blockDamage,\"tool\":${toolClass?.let { jq(it) }}")
            return BlockDamageOutcome.CHIPPED
        }
        override fun place(cell: BlockCell, blockKey: String): Boolean {
            gpLine("block", "\"k\":\"place\",${cellAt(cell)},\"block\":${jq(blockKey)}")
            return true
        }
        override fun remove(cell: BlockCell) { gpLine("block", "\"k\":\"break\",${cellAt(cell)}") }
        override fun placeCover(cast: CastId, cell: BlockCell, blockKey: String): Boolean {
            gpLine("block", "\"k\":\"place\",${cellAt(cell)},\"block\":${jq(blockKey)},\"cover\":true")
            return true
        }
    }

    private val victimAnim = object : ISkillVictimAnimGateway {
        override fun play(victim: UUID, anim: String, strength: Double, awayFrom: Vec3) {
            gpLine("kb", "\"who\":\"${whoOf(victim)}\",\"strength\":${r(strength)},\"anim\":${jq(anim)},\"from\":{${rel(awayFrom)}},\"via\":\"victimAnim\"")
            victimAnims += VictimAnimCall(now, victim, anim, strength)
        }
    }

    private val worldEffects = object : ISkillWorldEffectGateway {
        override fun heal(caster: UUID, victim: UUID, amount: Double) { gpLine("world", "\"k\":\"heal\",\"detail\":\"${whoOf(victim)} ${r(amount)}\"") }
        override fun resurrect(caster: UUID, victim: UUID, healthFraction: Double) { gpLine("world", "\"k\":\"resurrect\",\"detail\":\"${whoOf(victim)} ${r(healthFraction)}\"") }
        override fun explode(caster: UUID, at: Vec3, power: Double, breakBlocks: Boolean, incendiary: Boolean) {
            gpLine("world", "\"k\":\"explosion\",${rel(at)},\"detail\":\"power=${r(power)} blocks=$breakBlocks fire=$incendiary\"")
        }
        override fun summon(caster: UUID, at: Vec3, entityType: String, count: Int, lifetimeTicks: Int) {
            gpLine("world", "\"k\":\"summon\",${rel(at)},\"detail\":\"$entityType x$count life=$lifetimeTicks\"")
        }
        override fun lightning(caster: UUID, at: Vec3, visualOnly: Boolean) { gpLine("world", "\"k\":\"lightning\",${rel(at)},\"detail\":\"visualOnly=$visualOnly\"") }
    }

    private val c = Coin2CastWiring.collaborators()
    val catalog: SkillCatalog = c.newCatalog().also {
        val base = skillsDir ?: c.skillsDir
        val dir = if (extraSkills.isEmpty()) base else withExtraSkills(base, extraSkills)
        val report = SkillEngineContents(it).reload(dir)
        assertThat(report.failed).describedAs("shipped skills load: ${report.failures}").isZero()
    }

    private val visuals: ISkillVisualGateway = visualGateway ?: object : ISkillVisualGateway by noop() {
        override fun sprite(sprite: String) = com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo(1, 32)
        override fun setPlateFrame(handle: VisualHandle, frame: Int, tintHex: String?) {}
        override fun setTrailLook(handle: VisualHandle, fadePower: Double, style: String) {}
    }

    val kit: SkillEngineKit = SkillEngineKit.build(
        gateways = SkillEngineGateways(
            portsIn = { w -> SkillRuntimePorts(visuals, particlesIn(w), soundIn(w), gameplay, entitiesIn(w), victimAnim) },
            movement = movement,
            worldIn = { _ -> world },
            casterAnim = object : ISkillCasterAnimGateway {
                override fun play(caster: UUID, anim: String, speed: Double, ticks: Int) { casterAnims += CasterAnimCall(now, "play", anim, ticks, speed) }
                override fun stop(caster: UUID, anim: String) { casterAnims += CasterAnimCall(now, "stop", anim, 0) }
                override fun release(caster: UUID, anim: String) { casterAnims += CasterAnimCall(now, "release", anim, 0) }
            },
            screen = noop<ISkillScreenGateway>(),
            cooldowns = noop<ISkillCooldownGateway>(),
            items = noop<ISkillItemGateway>(),
            effects = worldEffects,
        ),
        newTree = c.newTree, motion = c.motion, contacts = c.contacts, exprs = c.exprs, points = c.points,
        skills = catalog::get, observer = farObserver,
    )

    /** Starts [id] at the current tick without ticking; drive it with [step]. */
    fun start(id: String): CastId {
        val skill = requireNotNull(catalog.get(id)) { "skill $id not loaded" }
        val req = CastRequest(skill, caster, castWorld, origin, yawDeg, pitchDeg, seed = 7L, startTick = now)
        return when (val r = kit.runner.start(req)) {
            is Result.Success -> r.value
            is Result.Failure -> error("cast did not start: ${r.error}")
        }
    }

    /** Ticks the runner until the clock reads [untilTick] (exclusive). */
    fun step(untilTick: Int) {
        while (now < untilTick) {
            kit.runner.tick(now)
            kit.world.tick()
            now++
        }
    }

    /** A hit of [amount] from [attacker] reaches the caster now; returns the damage multiplier the cast's windows impose. */
    fun incoming(attacker: UUID, amount: Double): Double = kit.runner.intercept(caster, attacker, amount)

    fun cast(id: String, ticks: Int): CastId {
        val skill = requireNotNull(catalog.get(id)) { "skill $id not loaded" }
        val req = CastRequest(skill, caster, castWorld, origin, yawDeg, pitchDeg, seed = 7L, startTick = 0)
        val cast = when (val r = kit.runner.start(req)) {
            is Result.Success -> r.value
            is Result.Failure -> error("cast did not start: ${r.error}")
        }
        while (now < ticks) {
            kit.runner.tick(now)
            kit.world.tick()
            now++
        }
        assertThat(kit.runner.diagnostics()).describedAs("no op failed").isEmpty()
        return cast
    }
}


private fun withExtraSkills(shipped: java.nio.file.Path, extra: Map<String, String>): java.nio.file.Path {
    val dir = java.nio.file.Files.createTempDirectory("sim-skills")
    shipped.toFile().copyRecursively(dir.toFile(), overwrite = true)
    for ((id, yaml) in extra) {
        java.nio.file.Files.createDirectories(dir.resolve(id))
        java.nio.file.Files.writeString(dir.resolve(id).resolve("skill.yaml"), yaml)
    }
    return dir
}
