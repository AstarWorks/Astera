package com.astarworks.astera.gamesim.playable.agent

import com.astarworks.astera.gamesim.GameSim
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.File
import java.nio.file.Path

/**
 * The JSON-lines agent port (ADR-0316): one command per input line, exactly one observation line per command.
 *
 * ```
 * {"op":"new","mode":"annihilation","stage":"annihilation-arena","me":"red","seed":7}
 * {"op":"act","do":"goto shop:red","until":"enemy<=12","max":1200}
 * {"op":"skip","until":"phase=engage|stars>=3","max":6000}
 * {"op":"look"}   {"op":"end"}
 * ```
 * Every observation carries `ok`; a failed command answers `{"ok":false,"error":...}` and the match carries on.
 */
class AgentPort(
    private val factory: MatchHostFactory,
    private val contentDir: File,
    private val traceDir: Path,
) {
    private var session: Session? = null
    private var host: MatchHost? = null

    /** Reads commands from [input] until `end` or EOF, writing one line per command to [out]. */
    fun run(input: BufferedReader, out: Appendable) {
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val (reply, finished) = handle(line)
            out.append(JSON.encodeToString(JsonElement.serializer(), reply)).append('\n')
            (out as? java.io.Flushable)?.flush()
            if (finished) return
        }
        close()
    }

    fun close() {
        host?.close()
        host = null
        session = null
    }

    /** Executes one command line; returns the observation and whether the port is finished. */
    fun handle(line: String): Pair<JsonObject, Boolean> {
        val cmd = try {
            JSON.parseToJsonElement(line) as? JsonObject ?: return error("a command is a JSON object") to false
        } catch (e: Exception) {
            return error("invalid JSON: ${e.message?.take(80)}") to false
        }
        val op = cmd.str("op") ?: return error("missing \"op\" (new act skip look end)") to false
        return try {
            when (op) {
                "new" -> newMatch(cmd) to false
                "end" -> end() to true
                else -> {
                    val s = session ?: return error("no match: send {\"op\":\"new\",...} first") to false
                    when (op) {
                        "act" -> act(s, cmd) to false
                        "skip" -> skip(s, cmd) to false
                        "look" -> observe(s, cmd, StepOutcome(true, "look", "local view"), extra = { put("look", JsonArray(s.look().map(::JsonPrimitive))) }) to false
                        else -> error("unknown op '$op' (new act skip look end)") to false
                    }
                }
            }
        } catch (e: Exception) {
            error("${e.javaClass.simpleName}: ${e.message?.take(160)}") to false
        }
    }

    private fun newMatch(cmd: JsonObject): JsonObject {
        close()
        val request = MatchRequest(
            mode = cmd.str("mode") ?: "annihilation",
            stage = cmd.str("stage"),
            teams = cmd.int("teams") ?: 3,
            botsPerTeam = cmd.int("bots_per_team") ?: 3,
            me = cmd.str("me") ?: "red",
            seed = cmd.long("seed") ?: GameSim.DEFAULT_SEED,
        )
        val h = factory.boot(request)
        host = h
        val s = Session(h, contentDir)
        session = s
        return observe(s, cmd, StepOutcome(true, "new", "match started: ${s.requestInfo()}"))
    }

    private fun act(s: Session, cmd: JsonObject): JsonObject {
        val text = cmd.str("do") ?: cmd.str("action") ?: return error("act needs \"do\", e.g. {\"op\":\"act\",\"do\":\"goto shop:red\"}")
        s.onNewDecision()
        return observe(s, cmd, s.act(text, cmd.str("until"), cmd.int("max") ?: DEFAULT_ACT_TICKS, cmd.bool("interrupts") ?: true))
    }

    private fun skip(s: Session, cmd: JsonObject): JsonObject {
        s.onNewDecision()
        return observe(s, cmd, s.skip(cmd.str("until"), cmd.int("max") ?: DEFAULT_SKIP_TICKS, cmd.bool("interrupts") ?: true))
    }

    private fun end(): JsonObject {
        val s = session
        val h = host
        if (s == null || h == null) return buildJsonObject { put("ok", true); put("ended", true) }
        val trace = runCatching { h.sim.writeTrace(traceDir) }.getOrNull()
        val stats = s.summaryStats()
        val reply = buildJsonObject {
            put("ok", true)
            put("ended", true)
            putJsonObject("summary") { stats.forEach { (k, v) -> put(k, v.toString().toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(v.toString())) } }
            putJsonObject("timeline") {
                put("first_star_pickup", moment(s.firstStar))
                put("first_kill", moment(s.firstKill))
                put("first_norma_levelup", moment(s.firstNormaLevelUp))
                putJsonArray("failed_decisions") { s.failureLog.takeLast(10).forEach { f -> add(buildJsonObject { put("tick", f.tick); put("do", f.label); put("stopped", f.stopped); put("note", f.note.take(100)) }) } }
            }
            putJsonArray("failed_decisions") { s.failures.takeLast(10).forEach { add(JsonPrimitive(it)) } }
            putJsonArray("star_moments") { s.starMoments.takeLast(8).forEach { add(JsonPrimitive(it)) } }
            putJsonArray("questions") { reflection(s).forEach { add(JsonPrimitive(it)) } }
            put("trace", trace?.toString() ?: "")
        }
        close()
        return reply
    }

    private fun reflection(s: Session): List<String> = listOf(
        "Wait time: ${s.skippedTicks} ticks were skipped or waited over ${s.decisions} decisions (longest: ${s.longestWaitAt.ifEmpty { "none" }}). Where did waiting feel too long, and what could you have done with that time?",
        "Not knowing what to do: ${s.emptyMenus} observations had little more than wait/cast in the menu. At which moments did you not know what to do next, and what information or action was missing?",
        "Stars: you collected ${s.starsGained} and reached norma level ${s.summaryStats()["norma_level"]}. Which moments made the stars feel meaningful, and which felt like busywork? What would you change?",
    )

    // --- observation ------------------------------------------------------------------------------------------------
    private fun observe(s: Session, cmd: JsonObject, outcome: StepOutcome, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val v = s.view()
        val events = s.drainEvents()
        val menu = s.menu()
        val mapMode = mapMode(cmd, if (cmd.str("op") == "act" || cmd.str("op") == "skip") "near" else "full")
        val scale = cmd.int("scale") ?: DEFAULT_SCALE
        return buildJsonObject {
            put("ok", outcome.ok)
            put("stopped", outcome.stopped)
            put("result", outcome.note)
            if (outcome.failed) put("failed", true)
            outcome.shop?.let { lines -> put("shop", buildJsonArray { lines.forEach { add(shopLine(it)) } }) }
            put("t", s.tick())
            put("phase", s.phaseId())
            if (s.isOver()) put("match_over", true)
            putJsonObject("me") {
                put("hp", v.hp)
                if (v.dead) { put("dead", true); put("respawn_in", v.respawnIn) }
                putJsonArray("pos") { v.pos.forEach { add(JsonPrimitive(it)) } }
                put("held", v.held?.let(::JsonPrimitive) ?: JsonNull)
                put("stars", v.stars)
                putJsonObject("ore") { v.ore.forEach { (k, n) -> put(k, n) } }
                putJsonObject("norma") { put("lv", v.normaLevel); put("goal", v.normaGoal); put("have", v.normaHave); put("need", v.normaNeed); if (v.ready) put("ready", true) }
                putJsonObject("wallet") { v.wallet.forEach { (k, n) -> put(k, n) } }
                put("tool", v.tool?.let(::JsonPrimitive) ?: JsonNull)
                if (v.cooldowns.isNotEmpty()) putJsonObject("cooldowns") { v.cooldowns.forEach { (k, n) -> put(k, n) } }
                if (v.inv.isNotEmpty()) putJsonObject("inv") { v.inv.forEach { (k, n) -> put(k, n) } }
            }
            put("team", buildJsonArray { v.team.forEach { (n, p, h) -> add(entity(n, p, h)) } })
            put("enemies", buildJsonArray { v.enemies.forEach { (n, p, h) -> add(entity(n, p, h)) } })
            if (mapMode != "off") {
                val m = s.map(scale).let { if (mapMode == "near") it.around('@', NEAR_RADIUS) else it }
                put("map", "x0=${m.originX} z0=${m.originZ} 1ch=${m.scale}\n" + m.text())
            }
            putJsonArray("events") { events.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("menu") { menu.forEach { add(JsonPrimitive(it)) } }
            extra()
        }
    }

    /** `map`: "full" | "near" (window around `@`) | "off" (also false); anything else = the op's default. */
    private fun mapMode(cmd: JsonObject, default: String): String {
        val p = cmd["map"] as? JsonPrimitive ?: return default
        return when (p.contentOrNull) {
            "full", "near", "off" -> p.content
            "false" -> "off"
            else -> default
        }
    }

    private fun shopLine(l: ShopLine): JsonObject = buildJsonObject {
        put("id", l.id)
        put("tab", l.tab)
        putJsonObject("price") { l.price.forEach { (k, n) -> put(k, n) } }
        put("affordable", l.affordable)
        if (l.missing.isNotEmpty()) putJsonObject("missing") { l.missing.forEach { (k, n) -> put(k, n) } }
        l.lockedUntilLevel?.let { put("locked", "needs norma lv$it") }
        l.needsBase?.let { put("locked_base", "needs $it first") }
    }

    private fun moment(m: Moment?): JsonElement = m?.let { buildJsonObject { put("tick", it.tick); put("seconds", it.seconds) } } ?: JsonNull

    private fun entity(name: String, pos: IntArray, hp: String): JsonObject = buildJsonObject {
        put("name", name)
        putJsonArray("pos") { pos.forEach { add(JsonPrimitive(it)) } }
        put("hp", hp)
    }

    private fun error(message: String): JsonObject = buildJsonObject { put("ok", false); put("error", message) }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val DEFAULT_ACT_TICKS = 1200
        const val DEFAULT_SKIP_TICKS = 6000
        const val DEFAULT_SCALE = 4
        const val NEAR_RADIUS = 9
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
