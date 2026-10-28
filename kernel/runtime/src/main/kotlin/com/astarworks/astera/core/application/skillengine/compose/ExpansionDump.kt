package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.domain.skillengine.table.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.joml.Quaterniond
import org.joml.Vector3d

/**
 * ADR-0303 11.3 dump. [render] is the text grammar (no `src`), [json] is one object per row (with `src`).
 * Values print as: scalars, "strings", v(x,y,z), q(x,y,z,w), [lists], {"k":v}, #"instance", <L:"name" bound=[lo,hi]>.
 */
public object ExpansionDump {
    public fun render(table: ExpansionTable): String = buildString {
        appendLine("skill ${table.skillId} seed=${table.sampleSeed}")
        for (st in table.states) {
            appendLine("state ${st.state.value} duration=${duration(st.duration)} then=${st.then?.value ?: "-"} final=${st.isFinal}")
            st.rows.forEach { appendLine("  " + row(it)) }
            st.branches.forEach { appendLine("  " + branch(it)) }
        }
        for (sub in table.subTables.values.sortedBy { it.event.value }) {
            appendLine("sub ${sub.event.value} owner=${sub.owner.value} window=[${sub.window.min},${sub.window.max}] max=${sub.maxFirings}")
            sub.rows.forEach { appendLine("  " + row(it)) }
            sub.branches.forEach { appendLine("  " + branch(it)) }
        }
    }

    public fun json(table: ExpansionTable): String {
        fun rowJson(scope: String, r: Row): JsonObject = buildJsonObject {
            put("state", scope)
            put("tick", r.tick)
            put("instance", r.instance.text)
            put("action", verb(r.action))
            put("payload", payload(r.action))
            put("src", r.src.value)
        }
        val rows = table.states.flatMap { st -> st.rows.map { rowJson(st.state.value, it) } } +
            table.subTables.values.sortedBy { it.event.value }.flatMap { sub -> sub.rows.map { rowJson(sub.event.value, it) } }
        return JsonObject(
            mapOf(
                "skill" to JsonPrimitive(table.skillId),
                "seed" to JsonPrimitive(table.sampleSeed),
                "rows" to JsonArray(rows),
            ),
        ).toString()
    }

    /**
     * Parses [render] output. Lossy: only structural rows (enter, exit, emit, end, cancel, lock) are rebuilt; a table with any other row,
     * or text that is not in the grammar, gives null. Bounds / writes / conditions / params are not printed, so they come back empty.
     * W1-17 (A1) extends it as the example `# expand:` blocks need.
     */
    public fun parse(text: String): ExpansionTable? {
        var skillId: String? = null
        var seed = 0L
        val states = ArrayList<StateTable>()
        val subs = LinkedHashMap<EventPath, SubTable>()
        var cur: Cur? = null

        fun flush() {
            val c = cur ?: return
            if (c.sub) subs[EventPath(c.name)] = SubTable(EventPath(c.name), StatePath(c.owner), c.window, c.max, c.rows, c.branches)
            else states += StateTable(StatePath(c.name), c.duration, c.then, c.final, c.rows, c.branches)
            cur = null
        }
        for (raw in text.lines()) {
            if (raw.isBlank()) continue
            val line = raw.trim()
            val tok = line.split(' ')
            when {
                line.startsWith("skill ") -> { skillId = tok[1]; seed = tok[2].removePrefix("seed=").toLongOrNull() ?: return null }
                line.startsWith("state ") -> {
                    flush()
                    val kv = kvs(tok.drop(2))
                    cur = Cur(sub = false, name = tok[1], duration = parseDuration(kv["duration"] ?: return null) ?: return null,
                        then = kv["then"]?.takeIf { it != "-" }?.let(::StatePath), final = kv["final"] == "true")
                }
                line.startsWith("sub ") -> {
                    flush()
                    val kv = kvs(tok.drop(2))
                    val w = parseRange(kv["window"] ?: return null) ?: return null
                    cur = Cur(sub = true, name = tok[1], owner = kv["owner"] ?: return null, window = w, max = kv["max"]?.toIntOrNull() ?: return null)
                }
                line.startsWith("branch ") -> {
                    val c = cur ?: return null
                    val kv = kvs(tok.drop(3))
                    c.branches += Branch(
                        EventPath(tok[1]), parseRange(tok[2]) ?: return null,
                        kv["guard"]?.takeIf { it != "-" }?.let { Guard.Named(it) },
                        kv["target"]?.takeIf { it != "-" }?.let(::StatePath),
                        kv["sub"]?.takeIf { it != "-" }?.let(::EventPath),
                    )
                }
                line.startsWith("@") -> (cur ?: return null).rows += (parseRow(line) ?: return null)
                else -> return null
            }
        }
        flush()
        val id = skillId ?: return null
        val empty = Bounds(emptyMap(), 0, 0, 0, 0, TickRange(0, 0), 0)
        return ExpansionTable(id, seed, emptyMap(), empty, states, subs, emptyMap(), emptyMap())
    }

    private class Cur(
        val sub: Boolean,
        val name: String,
        val owner: String = "",
        val window: TickRange = TickRange(0, 0),
        val max: Int = 1,
        val duration: Duration = Duration.Fixed(0),
        val then: StatePath? = null,
        val final: Boolean = false,
    ) {
        val rows = ArrayList<Row>()
        val branches = ArrayList<Branch>()
    }

    private fun kvs(tokens: List<String>): Map<String, String> =
        tokens.mapNotNull { t -> t.indexOf('=').takeIf { it > 0 }?.let { t.substring(0, it) to t.substring(it + 1) } }.toMap()

    private fun parseRange(s: String): TickRange? {
        val m = Regex("""\[(\d+),(\d+)]""").matchEntire(s) ?: return null
        val lo = m.groupValues[1].toInt()
        val hi = m.groupValues[2].toInt()
        return if (lo <= hi) TickRange(lo, hi) else null
    }

    private fun parseDuration(s: String): Duration? =
        s.toIntOrNull()?.let { Duration.Fixed(it) } ?: parseRange(s)?.let { Duration.Bounded(it) }

    private fun parseInstance(s: String): InstanceId? {
        if (s.isEmpty()) return InstanceId(emptyList())
        val segs = s.split('/').map { seg ->
            val m = Regex("""([^\[\]]+)(?:\[(\d+)])?""").matchEntire(seg) ?: return null
            InstanceId.Segment(OpId(m.groupValues[1]), m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt())
        }
        return InstanceId(segs)
    }

    private fun parseRow(line: String): Row? {
        val tok = line.split(' ')
        val tick = tok[0].removePrefix("@").toIntOrNull() ?: return null
        val inst = parseInstance(tok.getOrNull(1) ?: return null) ?: return null
        val verb = tok.getOrNull(2) ?: return null
        val kv = kvs(tok.drop(3))
        val action: Action = when (verb) {
            "enter" -> Action.Enter(StatePath(kv["state"] ?: return null))
            "exit" -> Action.Exit(StatePath(kv["state"] ?: return null), ExitReason.valueOf(kv["reason"] ?: return null))
            "emit" -> Action.Emit(EventPath(kv["event"] ?: return null))
            "end" -> Action.End(parseInstance((kv["instance"] ?: return null).removePrefix("#").trim('"')) ?: return null)
            "cancel" -> Action.Cancel(
                when (kv["scope"]) {
                    "cast" -> CancelScope.Cast
                    "state" -> CancelScope.State
                    else -> CancelScope.Instance(parseInstance((kv["scope"] ?: return null).removePrefix("#").trim('"')) ?: return null)
                },
            )
            "lock" -> Action.Lock(kv["move"] == "true", kv["pitch"] == "true", kv["yaw"] == "true", kv["until"]?.toIntOrNull() ?: return null)
            else -> return null
        }
        return Row(tick, inst, action, SrcPath(""))
    }

    private fun duration(d: Duration): String = when (d) {
        is Duration.Fixed -> d.ticks.toString()
        is Duration.Bounded -> "[${d.range.min},${d.range.max}]"
    }

    private fun branch(b: Branch): String {
        val g = when (val g = b.guard) { null -> "-"; is Guard.Named -> g.cond; is Guard.Expr -> "= " + g.source.replace(' ', '_') }
        return "branch ${b.event.value} [${b.window.min},${b.window.max}] guard=$g target=${b.target?.value ?: "-"} sub=${b.subTable?.value ?: "-"}"
    }

    private fun row(r: Row): String {
        val args = payloadText(r.action)
        return "@${r.tick} ${r.instance.text} ${verb(r.action)}" + if (args.isEmpty()) "" else " $args"
    }

    private fun verb(a: Action): String = when (a) {
        is Action.Spawn -> "spawn"
        is Action.Key -> "key"
        is Action.Teleport -> "teleport"
        is Action.TrailPoint -> "trail"
        is Action.Particle -> "particle"
        is Action.Sound -> "sound"
        is Action.Hit -> "hit"
        is Action.Damage -> "damage"
        is Action.Status -> "status"
        is Action.Effect -> "effect"
        is Action.Lock -> "lock"
        is Action.Call -> "call"
        is Action.Emit -> "emit"
        is Action.End -> "end"
        is Action.Cancel -> "cancel"
        is Action.Enter -> "enter"
        is Action.Exit -> "exit"
    }

    /** Field name -> value, in declaration order; the single source of truth for both text and json. */
    private fun fields(a: Action): List<Pair<String, Any?>> = when (a) {
        is Action.Spawn -> listOf("kind" to a.kind, "anchor" to a.anchor, "anchorName" to a.anchorName, "pos" to a.pose.position, "rot" to a.pose.rotation, "scale" to a.pose.scale, "look" to a.look.fields)
        is Action.Key -> listOf("display" to a.display, "dur" to a.durationTicks, "t" to a.translation, "r" to a.leftRotation, "s" to a.scale)
        is Action.Teleport -> listOf("display" to a.display, "dur" to a.durationTicks, "pos" to a.position, "yaw" to a.yaw, "pitch" to a.pitch)
        is Action.TrailPoint -> listOf("ribbon" to a.ribbon, "pos" to a.position)
        is Action.Particle -> listOf("type" to a.type, "count" to a.count, "offset" to a.offset, "extra" to a.extra, "data" to a.data, "pos" to a.position)
        is Action.Sound -> listOf("sound" to a.sound, "volume" to a.volume, "pitch" to a.pitch, "pos" to a.position, "category" to a.category)
        is Action.Hit -> listOf("shape" to a.shape, "frames" to a.frames, "targets" to a.targets, "once" to a.once)
        is Action.Damage -> listOf("targets" to a.targets, "components" to a.components, "chance" to a.chance)
        is Action.Status -> listOf("targets" to a.targets, "status" to a.status, "stacks" to a.stacks, "for" to a.forTicks)
        is Action.Effect -> listOf("op" to a.op, "payload" to a.payload)
        is Action.Lock -> listOf("move" to a.move, "pitch" to a.pitch, "yaw" to a.yaw, "until" to a.untilTick)
        is Action.Call -> listOf("callee" to a.callee, "id" to a.callId, "with" to a.with, "frames" to a.frames)
        is Action.Emit -> listOf("event" to a.event)
        is Action.End -> listOf("instance" to a.instance)
        is Action.Cancel -> listOf("scope" to a.scope)
        is Action.Enter -> listOf("state" to a.state)
        is Action.Exit -> listOf("state" to a.state, "reason" to a.reason)
    }

    private fun payloadText(a: Action): String = fields(a).joinToString(" ") { (k, v) -> "$k=${value(v)}" }

    private fun payload(a: Action): JsonObject = JsonObject(fields(a).associate { (k, v) -> k to jsonValue(v) })

    private fun num(d: Double): String = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e9) d.toLong().toString() else "%.4f".format(java.util.Locale.ROOT, d).trimEnd('0').trimEnd('.')

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Val.Known<*> -> value(v.value)
        is Val.Live<*> -> "<L:\"${v.name}\"" + (v.bound?.let { " bound=[${value(it.lo)},${value(it.hi)}]" } ?: "") + ">"
        is Double -> num(v)
        is Number, is Boolean -> v.toString()
        is String -> "\"$v\""
        is Enum<*> -> v.name
        is Vector3d -> "v(${num(v.x)},${num(v.y)},${num(v.z)})"
        is Quaterniond -> "q(${num(v.x)},${num(v.y)},${num(v.z)},${num(v.w)})"
        is InstanceId -> "#\"${v.text}\""
        is OpId -> v.value
        is StatePath -> v.value
        is EventPath -> v.value
        is List<*> -> v.joinToString(",", "[", "]") { value(it) }
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { "\"${it.key}\":${value(it.value)}" }
        is CancelScope -> when (v) { CancelScope.Cast -> "cast"; CancelScope.State -> "state"; is CancelScope.Instance -> value(v.id) }
        else -> opaque(v)
    }

    /** Data classes without a fixed grammar (ParticleData, DamageComponent, Callee, shapes, rules) print as Kotlin toString: lossy for [parse]. */
    private fun opaque(v: Any): String = v.toString().replace(' ', '_')

    private fun jsonValue(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Val.Known<*> -> jsonValue(v.value)
        is Val.Live<*> -> JsonObject(
            mapOf("live" to JsonPrimitive(v.name), "bound" to (v.bound?.let { JsonArray(listOf(jsonValue(it.lo), jsonValue(it.hi))) } ?: JsonNull)),
        )
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { jsonValue(it) })
        is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to jsonValue(it.value) })
        else -> JsonPrimitive(value(v))
    }
}
