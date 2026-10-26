package com.astarworks.astera.particlesim

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One particle call of the plan (`plan.json` -> `particles[]`), caster-local space. */
class Emission(
    val tick: Int,
    val type: String,
    val count: Int,
    val pos: DoubleArray,
    val delta: DoubleArray,
    val speed: Double,
    val data: JsonObject?,
) {
    companion object {
        fun parse(o: JsonObject): Emission = Emission(
            tick = o["tick"]!!.jsonPrimitive.int,
            type = o["type"]!!.jsonPrimitive.content,
            count = o["count"]!!.jsonPrimitive.int,
            pos = vec(o["pos"]!!.jsonArray),
            delta = vec(o["delta"]!!.jsonArray),
            speed = o["speed"]?.jsonPrimitive?.double ?: 0.0,
            data = o["data"] as? JsonObject,
        )

        private fun vec(a: JsonArray): DoubleArray = DoubleArray(3) { a[it].jsonPrimitive.double }
    }
}

/** One rendered particle on one tick. */
class Row(
    val type: String,
    val pos: DoubleArray,
    val size: Double,
    val sprite: String,
    val rgba: FloatArray,
    val light: Int,
    val roll: Double,
    val age: Int,
)

/** How a particle type was simulated. */
class TypeReport(val type: String) {
    var emissions: Int = 0
    var particles: Int = 0
    var fallbackReason: String? = null
    var skinned: Boolean = false
    var partialReason: String? = null
}

/**
 * Plays the emissions of a skill through the real vanilla particle classes, one client tick at a time. Order of a tick:
 * the packets of that skill tick create their particles (age 0), the tick is recorded, then every live particle ticks once
 * (children created while ticking join the next tick, as in the client's particle engine).
 */
class ParticleSim(private val rt: McRuntime, private val catalog: SpriteCatalog) {

    private interface Live {
        val type: String
        fun tick()
        val alive: Boolean
        fun row(): Row
    }

    private inner class Vanilla(override val type: String, val p: Any) : Live {
        override fun tick() = rt.tick(p)
        override val alive: Boolean get() = rt.isAlive(p)
        override fun row(): Row {
            val s = rt.state(p)
            val rgba = s.rgba ?: floatArrayOf(1f, 1f, 1f, 1f)
            return Row(type, s.pos, s.size ?: DEFAULT_SIZE, s.sprite ?: FALLBACK_SPRITE, rgba, s.light, s.roll, s.age)
        }
    }

    /** A stand-in for a type the real classes could not run: straight flight with 2% drag, 20 ticks, a dot. */
    private class Dot(override val type: String, val pos: DoubleArray, val v: DoubleArray, val rgb: Int) : Live {
        var age = 0
        override fun tick() {
            for (i in 0..2) { pos[i] += v[i]; v[i] *= 0.98 }
            age++
        }
        override val alive: Boolean get() = age < LIFETIME
        override fun row() = Row(
            type, pos.copyOf(), DEFAULT_SIZE, FALLBACK_SPRITE,
            floatArrayOf((rgb shr 16 and 255) / 255f, (rgb shr 8 and 255) / 255f, (rgb and 255) / 255f, 1f - age.toFloat() / LIFETIME), FULL_BRIGHT, 0.0, age,
        )
        companion object { const val LIFETIME = 20 }
    }

    val reports = LinkedHashMap<String, TypeReport>()
    private val live = ArrayList<Live>()
    private val pending = ArrayList<Live>()
    private val nextGaussian = rt.random.javaClass.getMethod("nextGaussian")
    private val unsupportedTypes = HashMap<String, String>()
    private val boundTypes = HashSet<String>()
    private val usedTextures = LinkedHashSet<String>()

    /** Texture ids any emitted particle actually used. */
    val textures: Set<String> get() = usedTextures

    private fun report(type: String) = reports.getOrPut(type) { TypeReport(type).also { it.skinned = catalog.isSkinned(type) } }

    private fun gaussian(): Double = nextGaussian.invoke(rt.random) as Double

    init {
        rt.childSink = { type, p -> report(type).particles++; pending += Vanilla(type, p) }
    }

    /** Binds the sprite list (resource-pack aware) of [type] once. */
    private fun ensureSprites(type: String) {
        if (boundTypes.add(type)) rt.bindSprites(type, catalog.textures(type).also { usedTextures += it })
    }

    private fun spawnOne(e: Emission, x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double) {
        val rep = report(e.type)
        rep.particles++
        val reason = unsupportedTypes[e.type]
        if (reason == null) {
            try {
                val type = rt.typeOf(e.type) ?: throw Unsupported("not a vanilla particle type")
                if (!rt.hasProvider(type)) throw Unsupported("the client has no provider for it")
                ensureSprites(e.type)
                val options = rt.options(e.type, type, args(e.data))
                val p = rt.create(options, x, y, z, vx, vy, vz)
                live += Vanilla(e.type, p)
                return
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                val why = (cause as? Unsupported)?.reason ?: "${cause.javaClass.simpleName}: ${cause.message}".take(160)
                if (System.getenv("PARTICLE_SIM_DEBUG") != null) cause.printStackTrace()
                unsupportedTypes[e.type] = why
                rep.fallbackReason = why
            }
        }
        live += Dot(e.type, doubleArrayOf(x, y, z), doubleArrayOf(vx, vy, vz), rgbOf(e.data))
    }

    /** The packet logic of the client: `count == 0` is one directed particle, otherwise `count` gaussian-scattered ones. */
    private fun emit(e: Emission) {
        report(e.type).emissions++
        if (e.count == 0) {
            spawnOne(e, e.pos[0], e.pos[1], e.pos[2], e.delta[0] * e.speed, e.delta[1] * e.speed, e.delta[2] * e.speed)
            return
        }
        repeat(e.count) {
            val ox = gaussian() * e.delta[0]
            val oy = gaussian() * e.delta[1]
            val oz = gaussian() * e.delta[2]
            val vx = gaussian() * e.speed
            val vy = gaussian() * e.speed
            val vz = gaussian() * e.speed
            spawnOne(e, e.pos[0] + ox, e.pos[1] + oy, e.pos[2] + oz, vx, vy, vz)
        }
    }

    /** Runs the skill ticks `0..lastTick` (extended until nothing is alive, at most [maxExtra] more) and returns the rows per tick. */
    fun run(emissions: List<Emission>, maxExtra: Int = 160): List<List<Row>> {
        val byTick = emissions.groupBy { it.tick }
        val lastEmit = emissions.maxOfOrNull { it.tick } ?: -1
        val out = ArrayList<List<Row>>()
        var t = 0
        while (t <= lastEmit + maxExtra) {
            byTick[t]?.forEach { emit(it) }
            if (t > lastEmit && live.isEmpty()) break
            out += live.map { it.row() }
            tickAll()
            t++
        }
        return out
    }

    private fun tickAll() {
        val it = live.iterator()
        val keep = ArrayList<Live>(live.size)
        while (it.hasNext()) {
            val p = it.next()
            try {
                p.tick()
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                report(p.type).partialReason = "tick failed: ${cause.javaClass.simpleName}: ${cause.message}".take(160)
                continue
            }
            if (p.alive) keep += p
        }
        live.clear()
        live += keep
        live += pending
        pending.clear()
    }

    // ---- typed data ---------------------------------------------------------------------------------------------------

    private fun rgb(o: JsonObject?): Int? =
        o?.let { (it["r"]!!.jsonPrimitive.int shl 16) or (it["g"]!!.jsonPrimitive.int shl 8) or it["b"]!!.jsonPrimitive.int }

    private fun rgbOf(d: JsonObject?): Int =
        rgb(d?.get("color") as? JsonObject) ?: rgb(d?.get("from") as? JsonObject) ?: 0xFFFFFF

    private fun args(d: JsonObject?): DataArgs {
        if (d == null) return DataArgs()
        val scale = d["scale"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 1f
        return when (d["kind"]?.jsonPrimitive?.content) {
            "dust" -> DataArgs(dust = DataArgs.Dust(rgb(d["color"] as JsonObject)!!, scale))
            "dust_color_transition" -> DataArgs(transition = DataArgs.Transition(rgb(d["from"] as JsonObject)!!, rgb(d["to"] as JsonObject)!!, scale))
            "tint" -> DataArgs(tint = rgb(d["color"] as JsonObject))
            "spell" -> DataArgs(spellRgb = rgb(d["color"] as JsonObject), spellPower = d["power"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 1f)
            "power" -> DataArgs(power = d["power"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 1f)
            "sculk_charge" -> DataArgs(roll = d["roll"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f)
            "shriek" -> DataArgs(shriekDelay = d["delay"]?.jsonPrimitive?.intOrNull ?: 0)
            "trail" -> DataArgs(
                trailTarget = (d["target"] as? JsonArray)?.let { a -> DoubleArray(3) { a[it].jsonPrimitive.double } },
                trailRgb = rgb(d["color"] as? JsonObject) ?: 0xFFFFFF,
                trailTicks = d["duration_ticks"]?.jsonPrimitive?.intOrNull ?: 20,
            )
            else -> DataArgs()
        }
    }

    companion object {
        const val FALLBACK_SPRITE: String = "astera:particle_sim/fallback_dot"
        const val DEFAULT_SIZE: Double = 0.1
        const val FULL_BRIGHT: Int = 15728880
    }
}
