package com.astarworks.astera.particlesim

import java.lang.classfile.ClassFile
import java.lang.classfile.ClassTransform
import java.lang.classfile.CodeTransform
import java.lang.classfile.TypeKind
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.constant.ClassDesc
import java.lang.constant.MethodTypeDesc
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import java.util.IdentityHashMap
import java.util.jar.JarFile

/** A particle type the simulator cannot run with the real classes, and why. */
class Unsupported(val reason: String) : RuntimeException(reason)

/**
 * The vanilla client jar, loaded through a [URLClassLoader] of our own, driven by reflection. What it does and does not touch:
 *
 *  - It calls each particle type's `ParticleProvider` directly (the providers come out of `ParticleResources`, which only
 *    registers them; its resource reload is never run).
 *  - `ParticleEngine` and `ParticleRenderType` are never initialised.
 *  - `ClientLevel` is a subclass generated at run time, instantiated without a constructor (so no networking, no registries
 *    of a world), whose few methods a particle calls are routed to [StubHooks] (our code: fixed random source, full sky light,
 *    no blocks, no fluids).
 *  - `SpriteSet`s are the vanilla `MutableSpriteSet`s, rebound to placeholder sprites that only carry a texture id.
 *  - Determinism: the one source of nondeterminism in the particle constructors is `RandomSource.create()` ->
 *    `RandomSupport.generateUniqueSeed()` = `uniquifier ^ System.nanoTime()`. The class is patched IN MEMORY (the jar is never
 *    modified) so the `nanoTime` term is 0, and the uniquifier is reset to the run's seed.
 */
class McRuntime(private val dist: McDistribution, val seed: Long) {

    private val hooksName = StubHooks::class.java.name

    inner class Loader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        override fun findClass(name: String): Class<*> {
            if (name == RANDOM_SUPPORT) {
                val bytes = getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
                val patched = patchRandomSupport(bytes)
                // The jar is signed: the patched class must carry the signers of its package (a sibling's), or the JVM refuses it.
                val sibling = super.findClass("net.minecraft.world.level.levelgen.PositionalRandomFactory")
                return defineClass(name, patched, 0, patched.size, sibling.protectionDomain)
            }
            return super.findClass(name)
        }

        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    val loader: Loader = Loader(
        (listOf(dist.clientJar) + dist.libraries).map { it.toUri().toURL() }.toTypedArray(),
        StubHooks::class.java.classLoader,
    )

    private fun cls(name: String): Class<*> = Class.forName(name, true, loader)

    // ---- reflection handles -------------------------------------------------------------------------------------------

    private val unsafe: Any
    private val allocate: Method
    private val particleClass = cls("net.minecraft.client.particle.Particle")
    private val singleQuadClass = cls("net.minecraft.client.particle.SingleQuadParticle")
    private val providerClass = cls("net.minecraft.client.particle.ParticleProvider")
    private val createParticle: Method = providerClass.methods.first { it.name == "createParticle" }
    private val randomSourceClass = cls("net.minecraft.util.RandomSource")
    private val identifierClass = cls("net.minecraft.resources.Identifier")
    private val parseIdentifier: Method = identifierClass.getMethod("parse", String::class.java)
    private val simpleTypeClass = cls("net.minecraft.core.particles.SimpleParticleType")
    private val registry: Any
    private val registryGetValue: Method
    private val registryGetId: Method
    private val registryGetKey: Method
    private val registryKeySet: Method
    private val providers: Map<*, *>
    private val spriteSets: Map<*, *>
    private val levelClass: Class<*>

    /** Run-wide level random: also what `ClientLevel.getRandom()` and the provider `random` argument return. */
    val random: Any

    /** sprite object -> texture id (`minecraft:flame`, `minecraft:astera_ember_3`). */
    private val spriteNames = IdentityHashMap<Any, String>()

    /** The generated `ClientLevel` stand-in. */
    val level: Any

    /** What the stand-in level returns for every block / fluid query: an empty world. */
    val air: Any by lazy {
        val blocks = cls("net.minecraft.world.level.block.Blocks")
        val b = blocks.getField("AIR").get(null)
        b.javaClass.getMethod("defaultBlockState").invoke(b)
    }
    val emptyFluid: Any by lazy {
        val f = cls("net.minecraft.world.level.material.Fluids").getField("EMPTY").get(null)
        f.javaClass.getMethod("defaultFluidState").invoke(f)
    }

    private val fields = HashMap<String, Field>()
    private val getLightCoords: Method
    private val getQuadSize: Method

    init {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        unsafe = unsafeClass.getDeclaredField("theUnsafe").also { it.isAccessible = true }.get(null)
        allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)

        // The registries refuse to build before Bootstrap.bootStrap(), which would initialise the whole game. Only the
        // flag is flipped; ParticleTypes (and the registries it registers into) then initialise on their own.
        // ClientLevel's static initialiser touches Items (-> blocks, entities, the data fixer), which wants a game version.
        cls("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null)
        val bootstrap = cls("net.minecraft.server.Bootstrap")
        bootstrap.getDeclaredField("isBootstrapped").also { it.isAccessible = true }.setBoolean(null, true)

        val rs = cls(RANDOM_SUPPORT)
        (rs.getDeclaredField("SEED_UNIQUIFIER").also { it.isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicLong).set(seed)
        random = randomSourceClass.getMethod("create", Long::class.javaPrimitiveType).invoke(null, seed xor 0x5DEECE66DL)!!

        // Order matters: ParticleTypes must be the first to touch the registries (BuiltInRegistries initialised on its own
        // tries to register every item / entity, which needs a game version).
        val resources = cls("net.minecraft.client.particle.ParticleResources").getDeclaredConstructor().newInstance()
        providers = resources.javaClass.getMethod("getProviders").invoke(resources) as Map<*, *>
        spriteSets = resources.javaClass.getDeclaredField("spriteSets").also { it.isAccessible = true }.get(resources) as Map<*, *>

        val builtIn = cls("net.minecraft.core.registries.BuiltInRegistries")
        registry = builtIn.getField("PARTICLE_TYPE").get(null)
        val regIface = cls("net.minecraft.core.Registry")
        registryGetValue = regIface.methods.first { it.name == "getValue" && it.parameterTypes.singleOrNull() == identifierClass }
        registryGetId = regIface.methods.first { it.name == "getId" }
        registryGetKey = regIface.methods.first { it.name == "getKey" && it.parameterCount == 1 && it.returnType == identifierClass }
        registryKeySet = regIface.getMethod("keySet")

        // A few providers read `Minecraft.getInstance()` (the local player for the spyglass check, `particleEngine` handed to
        // firework sparks for their trails). A constructor-less instance with every field null stands in; trails are not
        // part of the plain `firework` particle, and the engine is deliberately never created.
        val mc = cls("net.minecraft.client.Minecraft")
        mc.getDeclaredField("instance").also { it.isAccessible = true }.set(null, allocate.invoke(unsafe, mc))

        levelClass = cls("net.minecraft.client.multiplayer.ClientLevel")
        level = allocate.invoke(unsafe, generateLevelStub())!!

        getLightCoords = particleClass.getDeclaredMethod("getLightCoords", Float::class.javaPrimitiveType).also { it.isAccessible = true }
        getQuadSize = singleQuadClass.getMethod("getQuadSize", Float::class.javaPrimitiveType)
        StubHooks.rt = this
    }

    // ---- bytecode: deterministic seeds + the level stand-in ---------------------------------------------------------------

    private fun patchRandomSupport(bytes: ByteArray): ByteArray {
        val cf = ClassFile.of()
        val model = cf.parse(bytes)
        val dropNanoTime = CodeTransform { b, e ->
            if (e is InvokeInstruction && e.name().equalsString("nanoTime")) b.lconst_0() else b.with(e)
        }
        return cf.transformClass(model, ClassTransform.transformingMethodBodies({ it.methodName().equalsString("generateUniqueSeed") }, dropNanoTime))
    }

    private fun generateLevelStub(): Class<*> {
        val object_ = ClassDesc.of("java.lang.Object")
        fun desc(c: Class<*>): ClassDesc = c.describeConstable().get()
        fun erased(c: Class<*>): ClassDesc = if (c.isPrimitive) desc(c) else object_

        // (method name, parameter count, required parameter simple names or empty) of every ClientLevel method a particle calls.
        val wanted = listOf(
            Triple("addParticle", 7, emptyList<String>()),
            Triple("addAlwaysVisibleParticle", 7, emptyList()),
            Triple("getFluidState", 1, emptyList()),
            Triple("getBlockState", 1, emptyList()),
            Triple("getRandom", 0, emptyList()),
            Triple("playLocalSound", 8, emptyList()),
            Triple("hasChunkAt", 1, listOf("BlockPos")),
            Triple("getNearestPlayer", 5, emptyList()),
            Triple("getBrightness", 2, listOf("LightLayer", "BlockPos")),
        )
        val name = "com.astarworks.astera.particlesim.gen.StubClientLevel"
        val bytes = ClassFile.of().build(ClassDesc.of(name)) { cb ->
            cb.withFlags(ClassFile.ACC_PUBLIC)
            cb.withSuperclass(desc(levelClass))
            for ((mName, count, paramNames) in wanted) {
                val m = levelClass.methods.firstOrNull {
                    it.name == mName && it.parameterCount == count && !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) &&
                        (paramNames.isEmpty() || it.parameterTypes.map { p -> p.simpleName } == paramNames)
                } ?: continue
                val real = MethodTypeDesc.of(desc(m.returnType), m.parameterTypes.map { desc(it) })
                val hook = MethodTypeDesc.of(erased(m.returnType), m.parameterTypes.map { erased(it) })
                cb.withMethodBody(mName, real, ClassFile.ACC_PUBLIC) { code ->
                    var slot = 1
                    for (p in m.parameterTypes) {
                        val k = TypeKind.from(desc(p))
                        code.loadLocal(k, slot)
                        slot += k.slotSize()
                    }
                    code.invokestatic(ClassDesc.of(hooksName), mName, hook)
                    if (!m.returnType.isPrimitive) code.checkcast(desc(m.returnType))
                    code.return_(TypeKind.from(desc(m.returnType)))
                }
            }
        }
        return loader.define(name, bytes)
    }

    // ---- sprites -------------------------------------------------------------------------------------------------------

    /** Names of every particle type that has a vanilla sprite set. */
    fun spriteSetTypes(): List<String> = spriteSets.keys.map { (it as Any).toString().removePrefix("minecraft:") }

    /** Rebinds the vanilla sprite set of [type] to one placeholder sprite per texture id (frame order = list order). */
    fun bindSprites(type: String, textures: List<String>) {
        val set = spriteSets.entries.firstOrNull { it.key.toString().removePrefix("minecraft:") == type }?.value ?: return
        val spriteClass = cls("net.minecraft.client.renderer.texture.TextureAtlasSprite")
        val list = textures.map { tex ->
            val s = allocate.invoke(unsafe, spriteClass)!!
            spriteNames[s] = tex
            s
        }
        set.javaClass.getMethod("rebind", java.util.List::class.java).also { it.isAccessible = true }.invoke(set, list)
    }

    // ---- particle types ------------------------------------------------------------------------------------------------

    /** `ParticleTypes` constants by registry path (`flame`, `dust`, ...). The registry's own by-name lookup is unusable: it is initialised half-way (see init). */
    private val typesByName: Map<String, Any> by lazy {
        val typeClass = cls("net.minecraft.core.particles.ParticleType")
        cls("net.minecraft.core.particles.ParticleTypes").fields
            .filter { Modifier.isStatic(it.modifiers) && typeClass.isAssignableFrom(it.type) }
            .associate { it.name.lowercase() to it.get(null)!! }
    }

    private val namesByType: IdentityHashMap<Any, String> by lazy {
        IdentityHashMap<Any, String>().also { m -> typesByName.forEach { (n, t) -> m[t] = n } }
    }

    /** Registered particle type names (`flame`, `dust`, ...). */
    fun typeNames(): List<String> = typesByName.keys.sorted()

    fun typeOf(name: String): Any? = typesByName[name]

    fun hasProvider(type: Any): Boolean = providers.containsKey(registryGetId.invoke(registry, type) as Int)

    private fun nameOfType(type: Any): String = namesByType[type] ?: type.toString()

    /** Builds the options object of [type] (the type itself for a `SimpleParticleType`). Throws [Unsupported]. */
    fun options(name: String, type: Any, data: DataArgs): Any {
        if (simpleTypeClass.isInstance(type)) return type
        fun c(cname: String) = cls("net.minecraft.core.particles.$cname")
        return when (name) {
            "dust" -> data.dust?.let { c("DustParticleOptions").getConstructor(Int::class.javaPrimitiveType, Float::class.javaPrimitiveType).newInstance(it.rgb, it.scale) }
                ?: c("DustParticleOptions").getField("REDSTONE").get(null)
            "dust_color_transition" -> data.transition?.let {
                c("DustColorTransitionOptions").getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    .newInstance(it.from, it.to, it.scale)
            } ?: c("DustColorTransitionOptions").getField("SCULK_TO_REDSTONE").get(null)
            "entity_effect", "flash", "tinted_leaves" ->
                c("ColorParticleOption").getMethod("create", c("ParticleType"), Int::class.javaPrimitiveType).invoke(null, type, 0xFF000000.toInt() or (data.tint ?: 0xFFFFFF))
            "effect", "instant_effect" ->
                c("SpellParticleOption").getMethod("create", c("ParticleType"), Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    .invoke(null, type, data.spellRgb ?: 0xFFFFFF, data.spellPower)
            "dragon_breath" -> c("PowerParticleOption").getMethod("create", c("ParticleType"), Float::class.javaPrimitiveType).invoke(null, type, data.power)
            "sculk_charge" -> c("SculkChargeParticleOptions").getConstructor(Float::class.javaPrimitiveType).newInstance(data.roll)
            "shriek" -> c("ShriekParticleOption").getConstructor(Int::class.javaPrimitiveType).newInstance(data.shriekDelay)
            "trail" -> {
                val t = data.trailTarget ?: throw Unsupported("trail without a target")
                val vec3 = cls("net.minecraft.world.phys.Vec3").getConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
                    .newInstance(t[0], t[1], t[2])
                c("TrailParticleOption").getConstructor(vec3.javaClass, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .newInstance(vec3, data.trailRgb, data.trailTicks)
            }
            else -> throw Unsupported("options of '$name' need game registries (blocks / items / position sources)")
        }
    }

    /** Runs the type's provider. Returns the vanilla particle (never `null`), or throws [Unsupported]. */
    fun create(options: Any, x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double): Any {
        val typeObj = options.javaClass.getMethod("getType").invoke(options)
        val provider = providers[registryGetId.invoke(registry, typeObj) as Int] ?: throw Unsupported("no client provider")
        return createParticle.invoke(provider, options, level, x, y, z, vx, vy, vz, random)
            ?: throw Unsupported("the provider declined to create the particle")
    }

    /** `ClientLevel.addParticle` from inside a particle (e.g. trails): the child is queued on [childSink]. */
    var childSink: ((type: String, particle: Any) -> Unit)? = null

    fun spawnFromHook(options: Any, x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double) {
        val type = options.javaClass.getMethod("getType").invoke(options)
        val sink = childSink ?: return
        runCatching { create(options, x, y, z, vx, vy, vz) }.onSuccess { sink(nameOfType(type), it) }
    }

    // ---- particle state --------------------------------------------------------------------------------------------------

    private fun field(owner: Class<*>, name: String): Field =
        fields.getOrPut(owner.name + "." + name) { owner.getDeclaredField(name).also { it.isAccessible = true } }

    fun tick(p: Any) { particleClass.getMethod("tick").invoke(p) }

    fun isAlive(p: Any): Boolean = particleClass.getMethod("isAlive").invoke(p) as Boolean

    fun setPhysics(p: Any, on: Boolean) { field(particleClass, "hasPhysics").setBoolean(p, on) }

    private fun d(p: Any, n: String): Double = field(particleClass, n).getDouble(p)

    class State(
        val pos: DoubleArray, val size: Double?, val sprite: String?, val rgba: FloatArray?, val light: Int, val roll: Double,
        val age: Int, val lifetime: Int,
    )

    fun state(p: Any): State {
        val pos = doubleArrayOf(d(p, "x"), d(p, "y"), d(p, "z"))
        val age = field(particleClass, "age").getInt(p)
        val lifetime = field(particleClass, "lifetime").getInt(p)
        val light = getLightCoords.invoke(p, 0f) as Int
        if (!singleQuadClass.isInstance(p)) return State(pos, null, null, null, light, 0.0, age, lifetime)
        val f = { n: String -> field(singleQuadClass, n).getFloat(p) }
        val sprite = field(singleQuadClass, "sprite").get(p)?.let { spriteNames[it] }
        // quadSize is the half extent of the billboard (the client builds the quad at +-size around the centre); reported as is.
        val size = (getQuadSize.invoke(p, 0f) as Float).toDouble()
        return State(pos, size, sprite, floatArrayOf(f("rCol"), f("gCol"), f("bCol"), f("alpha")), light, f("roll").toDouble(), age, lifetime)
    }

    fun particleClassName(p: Any): String = p.javaClass.name.removePrefix("net.minecraft.client.particle.")

    fun typeNameOf(options: Any): String = nameOfType(options.javaClass.getMethod("getType").invoke(options))

    companion object {
        const val RANDOM_SUPPORT: String = "net.minecraft.world.level.levelgen.RandomSupport"

        /** Texture ids a client jar sprite set uses by default, read from `assets/minecraft/particles/<type>.json`. */
        fun jarEntryExists(jar: java.nio.file.Path, entry: String): Boolean = JarFile(jar.toFile()).use { it.getJarEntry(entry) != null }
    }
}

/** Typed particle data arguments, already converted to what the vanilla option classes take. */
class DataArgs(
    val dust: Dust? = null,
    val transition: Transition? = null,
    val tint: Int? = null,
    val spellRgb: Int? = null,
    val spellPower: Float = 1f,
    val power: Float = 1f,
    val roll: Float = 0f,
    val shriekDelay: Int = 0,
    val trailTarget: DoubleArray? = null,
    val trailRgb: Int = 0xFFFFFF,
    val trailTicks: Int = 20,
) {
    class Dust(val rgb: Int, val scale: Float)
    class Transition(val from: Int, val to: Int, val scale: Float)
}

/**
 * Static entry points of the generated `ClientLevel` subclass. References are `Object` so the generated bytecode needs no
 * Minecraft types here; the results are cast back by the stub.
 */
object StubHooks {
    @Volatile
    lateinit var rt: McRuntime

    @JvmStatic fun addParticle(o: Any?, x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double) {
        if (o != null) rt.spawnFromHook(o, x, y, z, vx, vy, vz)
    }

    @JvmStatic fun addAlwaysVisibleParticle(o: Any?, x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double) =
        addParticle(o, x, y, z, vx, vy, vz)

    @JvmStatic fun getFluidState(pos: Any?): Any? = rt.emptyFluid

    @JvmStatic fun getBlockState(pos: Any?): Any? = rt.air

    @JvmStatic fun getRandom(): Any = rt.random

    @JvmStatic fun playLocalSound(x: Double, y: Double, z: Double, sound: Any?, source: Any?, volume: Float, pitch: Float, distanceDelay: Boolean) {}

    @JvmStatic fun hasChunkAt(pos: Any?): Boolean = true

    @JvmStatic fun getNearestPlayer(x: Double, y: Double, z: Double, d: Double, creative: Boolean): Any? = null

    /** Open sky, no lamps: full sky light, no block light. */
    @JvmStatic fun getBrightness(layer: Any?, pos: Any?): Int = if ((layer as? Enum<*>)?.name == "SKY") 15 else 0
}
