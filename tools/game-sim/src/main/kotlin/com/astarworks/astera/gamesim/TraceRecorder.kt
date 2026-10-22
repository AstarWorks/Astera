package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap

/**
 * Collects everything observable during a simulation, bucketed per tick, and
 * renders it as the JSON trace documented in `tools/game-sim/README.md`
 * (schema `astera-sim-trace/1`).
 *
 * Writers call the `record*` functions with the tick they happened on; the
 * simulator calls [snapshot] once at the end of every stepped tick to capture
 * entity and projectile positions.
 */
public class TraceRecorder(private val scenario: String, private val groundY: Double) {

    private class Bucket {
        val particles = mutableListOf<JsonElement>()
        val sounds = mutableListOf<JsonElement>()
        val projectileEvents = mutableListOf<JsonElement>()
        val damage = mutableListOf<JsonElement>()
        val status = mutableListOf<JsonElement>()
        val deaths = mutableListOf<JsonElement>()
        val teleports = mutableListOf<JsonElement>()
        val effects = mutableListOf<JsonElement>()
        val events = mutableListOf<JsonElement>()
        val hud = mutableListOf<JsonElement>()
        var hasSnapshot = false
        var entities: JsonElement = JsonArray(emptyList())
        var projectiles: JsonElement = JsonArray(emptyList())
    }

    /** One world effect (see [recordEffect]). */
    public data class Effect(val tick: Long, val kind: String, val at: Vec3?, val detail: String)

    private val effectLog: MutableList<Effect> = mutableListOf()

    /** Effects recorded so far, optionally only those of [kind]. */
    public fun effects(kind: String? = null): List<Effect> = effectLog.filter { kind == null || it.kind == kind }

    private val buckets: TreeMap<Long, Bucket> = TreeMap()
    private val entityInfo: MutableMap<PlayerId, JsonElement> = linkedMapOf()

    private fun bucket(tick: Long): Bucket = buckets.getOrPut(tick) { Bucket() }

    /** Registers (or refreshes) the static info of an entity so a viewer can label it. */
    public fun registerEntity(entity: SimEntity) {
        entityInfo[entity.id] = buildJsonObject {
            put("id", entity.id.uuid.toString())
            put("name", entity.name)
            put("kind", entity.kind.name.lowercase())
            put("team", entity.team?.value)
        }
    }

    public fun recordParticle(tick: Long, world: String, at: Vec3, key: String, count: Int, spec: ParticleSpec? = null) {
        bucket(tick).particles += buildJsonObject {
            put("key", key)
            put("count", count)
            // ADR-0187: animated particles carry colour / size / speed so a viewer can replay them.
            spec?.color?.let { put("color", buildJsonObject { put("r", it.r); put("g", it.g); put("b", it.b) }) }
            spec?.takeIf { it.color != null }?.let { put("scale", it.scale.toDouble()) }
            spec?.takeIf { it.extra != 0.0 }?.let { put("extra", it.extra) }
            put("world", world)
            vec(at)
        }
    }

    public fun recordSound(tick: Long, world: String, at: Vec3, key: String, volume: Double, pitch: Double) {
        bucket(tick).sounds += buildJsonObject {
            put("key", key)
            put("volume", volume)
            put("pitch", pitch)
            put("world", world)
            vec(at)
        }
    }

    /** [kind]: `spawn`, `hit_entity`, `hit_block`, `expire`, `cancel`. */
    public fun recordProjectileEvent(
        tick: Long,
        id: String,
        kind: String,
        at: Vec3,
        owner: PlayerId,
        target: PlayerId? = null,
    ) {
        bucket(tick).projectileEvents += buildJsonObject {
            put("id", id)
            put("kind", kind)
            put("owner", owner.uuid.toString())
            put("target", target?.uuid?.toString())
            vec(at)
        }
    }

    public fun recordDamage(
        tick: Long,
        attacker: PlayerId?,
        target: PlayerId,
        amount: Double,
        source: String,
        hpAfter: Double?,
    ) {
        bucket(tick).damage += buildJsonObject {
            put("attacker", attacker?.uuid?.toString())
            put("target", target.uuid.toString())
            put("amount", amount)
            put("source", source)
            put("hpAfter", hpAfter)
        }
    }

    /** [op]: `applied`, `removed`, `expired`. */
    public fun recordStatus(tick: Long, target: PlayerId, effect: String, op: String, stacks: Int?, remainingTicks: Int?) {
        bucket(tick).status += buildJsonObject {
            put("target", target.uuid.toString())
            put("effect", effect)
            put("op", op)
            put("stacks", stacks)
            put("remainingTicks", remainingTicks)
        }
    }

    /** [op]: `show`, `update`, `clear`; [summary] is the layout's text keys / placeholders (empty on clear). */
    public fun recordHud(tick: Long, player: PlayerId, region: String, op: String, summary: String) {
        bucket(tick).hud += buildJsonObject {
            put("player", player.uuid.toString())
            put("region", region)
            put("op", op)
            put("summary", summary.take(240))
        }
    }

    public fun recordDeath(tick: Long, victim: PlayerId, killer: PlayerId?, source: String) {
        bucket(tick).deaths += buildJsonObject {
            put("victim", victim.uuid.toString())
            put("killer", killer?.uuid?.toString())
            put("source", source)
        }
    }

    public fun recordTeleport(tick: Long, id: PlayerId, to: Vec3, world: String) {
        bucket(tick).teleports += buildJsonObject {
            put("id", id.uuid.toString())
            put("world", world)
            vec(to)
        }
    }

    /**
     * World effects of the non-projectile mechanics (WP5). [kind]: `explosion`, `lightning`,
     * `block_place`, `block_remove`, `display_spawn`, `display_remove`, `summon`, `despawn`,
     * `glow`, `camera_shake`. [at] is the place it happened (when it has one); [detail] is a
     * short human-readable description (block key, entity type, radius, ...).
     */
    public fun recordEffect(tick: Long, kind: String, at: Vec3?, detail: String) {
        effectLog += Effect(tick, kind, at, detail)
        bucket(tick).effects += buildJsonObject {
            put("kind", kind)
            put("detail", detail)
            if (at != null) vec(at)
        }
    }

    /** Catch-all for every domain event ([type] = simple class name). */
    public fun recordEvent(tick: Long, type: String, summary: String) {
        bucket(tick).events += buildJsonObject {
            put("type", type)
            put("summary", summary.take(SUMMARY_LIMIT))
        }
    }

    /** Captures entity + in-flight projectile positions at the end of [tick]. */
    public fun snapshot(
        tick: Long,
        entities: Collection<SimEntity>,
        hpOf: (PlayerId) -> Double?,
        projectiles: List<ProjectileSnapshot>,
    ) {
        val b = bucket(tick)
        b.hasSnapshot = true
        b.entities = buildJsonArray {
            entities.forEach { e ->
                add(
                    buildJsonObject {
                        put("id", e.id.uuid.toString())
                        vec(e.position)
                        put("dx", e.direction.x)
                        put("dy", e.direction.y)
                        put("dz", e.direction.z)
                        put("hp", hpOf(e.id))
                    },
                )
            }
        }
        b.projectiles = buildJsonArray {
            projectiles.forEach { p ->
                add(
                    buildJsonObject {
                        put("id", p.id)
                        put("owner", p.owner.uuid.toString())
                        vec(p.position)
                    },
                )
            }
        }
    }

    public fun hasSnapshot(tick: Long): Boolean = buckets[tick]?.hasSnapshot == true

    /** Particle keys recorded on any tick. */
    public fun particleKeys(): Set<String> = collectKeys { it.particles }

    public fun soundKeys(): Set<String> = collectKeys { it.sounds }

    private fun collectKeys(select: (Bucket) -> List<JsonElement>): Set<String> =
        buckets.values.flatMap(select).mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.get("key") }
            .map { (it as JsonPrimitive).content }.toSet()

    public fun toJson(): JsonElement = buildJsonObject {
        put("schema", SCHEMA)
        put("scenario", scenario)
        put("tickRate", TICK_RATE)
        put("groundY", groundY)
        put("entities", JsonArray(entityInfo.values.toList()))
        put(
            "ticks",
            buildJsonArray {
                buckets.forEach { (tick, b) ->
                    add(
                        buildJsonObject {
                            put("tick", tick)
                            put("entities", b.entities)
                            put("projectiles", b.projectiles)
                            put("projectileEvents", JsonArray(b.projectileEvents))
                            put("particles", JsonArray(b.particles))
                            put("sounds", JsonArray(b.sounds))
                            put("damage", JsonArray(b.damage))
                            put("status", JsonArray(b.status))
                            put("deaths", JsonArray(b.deaths))
                            put("teleports", JsonArray(b.teleports))
                            put("effects", JsonArray(b.effects))
                            put("events", JsonArray(b.events))
                            put("hud", JsonArray(b.hud))
                        },
                    )
                }
            },
        )
    }

    /** Writes `<dir>/<scenario>.json` (pretty printed) and returns the path. */
    public fun write(dir: Path): Path {
        Files.createDirectories(dir)
        val target = dir.resolve("$scenario.json")
        Files.writeString(target, PRETTY.encodeToString(JsonElement.serializer(), toJson()))
        return target
    }

    private fun JsonObjectBuilder.vec(v: Vec3) {
        put("x", v.x)
        put("y", v.y)
        put("z", v.z)
    }

    /** In-flight projectile position, as seen by [snapshot]. */
    public class ProjectileSnapshot(public val id: String, public val owner: PlayerId, public val position: Vec3)

    public companion object {
        public const val SCHEMA: String = "astera-sim-trace/1"
        public const val TICK_RATE: Int = 20
        private const val SUMMARY_LIMIT: Int = 400
        private val PRETTY: Json = Json { prettyPrint = true }
    }
}
