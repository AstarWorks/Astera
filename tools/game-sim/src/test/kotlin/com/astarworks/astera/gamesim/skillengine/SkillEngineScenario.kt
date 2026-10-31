package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.ExpansionDump
import com.astarworks.astera.core.application.skillengine.compose.FrameTables
import com.astarworks.astera.core.application.skillengine.compose.PartCatalog
import com.astarworks.astera.core.application.skillengine.compose.SkillCompiler
import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.load.GrammarEnv
import com.astarworks.astera.core.application.skillengine.load.ItemFrames
import com.astarworks.astera.core.application.skillengine.load.ItemFramesDecoder
import com.astarworks.astera.core.application.skillengine.load.SkillCatalogView
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.SkillDocumentDecoder
import com.astarworks.astera.core.application.skillengine.load.SkillEngineSpecs
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpecRegistry
import com.astarworks.astera.core.application.skillengine.part.PartParser
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.ContactInfo
import com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.part.Part
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Branch
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Duration
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.HitWindow
import com.astarworks.astera.core.domain.skillengine.table.ExpansionTable
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.StateTable
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Harness base for v3 skill-engine scenarios (ADR-0303 section 12): real loaders, real [SkillCompiler], real [TablePlayer],
 * in-memory gateways ([GatewayRecorder]). Subclasses are the acceptance classes A1-A9; this class holds no test of its own.
 *
 * The oracle is the table itself: [expectedCalls] walks the compiled [ExpansionTable] (states in `then` order, sub-tables
 * pasted at event ticks) and lists the gateway calls it promises; [play] runs the player and lists what it did; [assertPlays]
 * diffs the two. Only gateway-observable verbs are compared (spawn, write, trail, remove, particle, sound); `hit`, `call`,
 * `emit`, `enter`, `exit`, `cancel` produce no gateway call, and damage/status/effect depend on victims (none by default).
 */
abstract class SkillEngineScenario {
    // ---- locations -------------------------------------------------------------------------------------------------

    protected val contentDir: Path = Paths.get(System.getProperty("astera.sim.contentDir")).toAbsolutePath()
    protected val repoRoot: Path = contentDir.parent
    protected val examplesDir: Path = repoRoot.resolve("docs/adr/0303-examples")
    protected val evidenceDir: Path =
        Paths.get(System.getProperty("astera.sim.traceDir")).toAbsolutePath().parent.resolve("skill-expand")

    // ---- loader ----------------------------------------------------------------------------------------------------

    private val specs = OpSpecRegistry(SkillEngineSpecs.all())
    private val exprs = CelExprCompiler()
    protected val decoder = SkillDocumentDecoder(specs, exprs)

    protected open val frameTables: FrameTables by lazy {
        val tables = ItemFramesDecoder.loadDirectory(contentDir.resolve("frames")).first
        object : FrameTables {
            override fun item(item: String): ItemFrames? = tables[item]
        }
    }

    private val compiler: SkillCompiler by lazy { SkillCompiler(specs, exprs, frameTables, EngineConfig(), com.astarworks.astera.gamesim.SimClutter.context(), com.astarworks.astera.core.application.skillengine.compose.ContentSpriteCatalog.load(contentDir.resolve("sprites"))) }

    /** Parts shipped under `content/skill-parts` (empty until wave 2 migrates content). */
    protected val shippedParts: Map<String, Part> by lazy {
        when (val r = PartParser.loadDirectory(contentDir.resolve("skill-parts"))) {
            is Result.Success -> r.value
            is Result.Failure -> emptyMap()
        }
    }

    /** Everything one YAML file declares: its skills (declaration order) and the parts visible to them. */
    protected class Loaded(val skills: Map<String, SkillDef>, val parts: Map<String, Part>)

    /** Decodes every document of [text]. Error = first failure of any document (parts first, then skills in order). */
    protected fun decodeAll(file: String, text: String, extraParts: Map<String, Part> = shippedParts): Result<Loaded, SkillDecodeError> {
        val docs = when (val d = PartParser.documents(file, text)) {
            is Result.Success -> d.value
            is Result.Failure -> return Result.failure(SkillDecodeError.Invalid("", file, d.error.toString()))
        }
        val parts = LinkedHashMap(extraParts)
        when (val p = PartParser.parseParts(file, text)) {
            is Result.Success -> p.value.forEach { parts[it.id] = it }
            is Result.Failure -> return Result.failure(SkillDecodeError.Invalid("", file, p.error.joinToString("; ")))
        }
        val skillDocs = docs.filter { it["kind"] == "Skill" }
        val env = GrammarEnv(parts.keys, skillDocs.mapNotNull { it["id"] as? String }.toSet())
        val skills = LinkedHashMap<String, SkillDef>()
        for (doc in skillDocs) {
            when (val s = decoder.decode(file, doc, env)) {
                is Result.Success -> skills[s.value.id] = s.value
                is Result.Failure -> return Result.failure(s.error)
            }
        }
        return Result.success(Loaded(skills, parts))
    }

    protected fun compileMain(loaded: Loaded, mainId: String = loaded.skills.keys.first()): Result<CompiledSkill, SkillDecodeError> {
        val def = loaded.skills.getValue(mainId)
        val parts = object : PartCatalog {
            override fun part(id: String): Part? = loaded.parts[id]
        }
        val skills = object : SkillCatalogView {
            override fun skill(id: String): SkillDef? = loaded.skills[id]
        }
        return compiler.compile(def, parts, skills)
    }

    /** Decodes one composed tree against a grammar that knows [skillIds] (so `use: skill:` names resolve). */
    protected fun decodeTree(file: String, tree: Map<String, Any?>, skillIds: Set<String>): Result<SkillDef, SkillDecodeError> =
        decoder.decode(file, tree, GrammarEnv(shippedParts.keys, skillIds))

    /** Compiles [def] with every decoded skill of [catalog] resolvable by `use: skill:<id>`, as on the server. */
    protected fun compileAgainst(def: SkillDef, catalog: Map<String, SkillDef>): Result<CompiledSkill, SkillDecodeError> =
        compileMain(Loaded(catalog, shippedParts), def.id)

    /** Compile result of one composed tree (a skill after `kind: Patch` documents were applied, e.g. a skin variant `<id>@<variant>`). */
    protected fun compileTree(file: String, tree: Map<String, Any?>): Result<CompiledSkill, SkillDecodeError> {
        val env = GrammarEnv(shippedParts.keys, setOfNotNull(tree["id"] as? String))
        return when (val d = decoder.decode(file, tree, env)) {
            is Result.Success -> compileMain(Loaded(mapOf(d.value.id to d.value), shippedParts), d.value.id)
            is Result.Failure -> Result.failure(d.error)
        }
    }

    /** Compile result of one file (first skill document is the main skill), without throwing. */
    protected fun compileText(file: String, text: String): Result<CompiledSkill, SkillDecodeError> =
        when (val l = decodeAll(file, text)) {
            is Result.Success -> compileMain(l.value)
            is Result.Failure -> Result.failure(l.error)
        }

    /** docs/adr/0303-examples/<name>.yaml (multi-document: parts and skills; the first skill document is the main skill). */
    fun loadExample(name: String): CompiledSkill {
        val file = examplesDir.resolve("$name.yaml")
        return when (val r = compileText(file.fileName.toString(), Files.readString(file))) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("example $name did not load: ${r.error}")
        }
    }

    fun loadInline(yaml: String): Result<CompiledSkill, SkillDecodeError> = compileText("<inline>", yaml)

    /** The hand- or tool-written `# expand:` block of an example (comment markers stripped, header line dropped); "" when absent. */
    fun dumpOf(name: String): String {
        val lines = Files.readAllLines(examplesDir.resolve("$name.yaml"))
        val at = lines.indexOfFirst { it.startsWith("# expand:") }
        if (at < 0) return ""
        return lines.drop(at + 1).joinToString("\n") { it.removePrefix("#").removePrefix(" ") }.trimEnd() + "\n"
    }

    /** Names (without extension) of the fixtures of [dir] in sorted order. */
    protected fun fixtureNames(dir: Path): List<String> =
        Files.list(dir).use { s -> s.filter { it.toString().endsWith(".yaml") }.map { it.fileName.toString().removeSuffix(".yaml") }.sorted().toList() }

    // ---- player ----------------------------------------------------------------------------------------------------

    /** What a `summon` row resolves its skill id to (ADR-0313); empty unless a scenario sets it, as the platform does from the skill catalog. */
    protected var summonSkills: (String) -> CompiledSkill? = { null }

    /** Runs the skill through the real [TablePlayer] on a fresh [GatewayRecorder]; ticks default to the table's longest path plus 2. */
    fun play(skill: CompiledSkill, seed: Long, events: List<RuntimeEvent> = emptyList(), ticks: Int? = null): List<RecordedCall> =
        play(skill, seed, events, ticks, GatewayRecorder())

    fun play(skill: CompiledSkill, seed: Long, events: List<RuntimeEvent>, ticks: Int?, rec: GatewayRecorder, yawDeg: Double = 0.0, pitchDeg: Double = 0.0, cancelAtEnd: Boolean = false, beforeTick: (Int) -> Unit = {}): List<RecordedCall> {
        rec.casterYawDeg = yawDeg
        rec.casterPitchDeg = pitchDeg
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, rec.live, rec, rec.movement, rec.items, rec.world)
        player.hooks = rec.hooks
        player.summonSkills = summonSkills
        rec.castItem = skill.def.item
        val request = CastRequest(
            skill = skill,
            caster = rec.caster,
            world = WorldId("world"),
            casterFeet = Vec3(0.0, 0.0, 0.0),
            casterYawDeg = yawDeg,
            casterPitchDeg = pitchDeg,
            seed = seed,
            startTick = 0,
            heldItem = skill.def.item?.takeIf { it != "any" },
        )
        val cast = when (val s = player.start(request, skill)) {
            is Result.Success -> s.value
            is Result.Failure -> throw AssertionError("start failed: ${s.error}")
        }
        val end = ticks ?: (skill.table.bounds.path.max + skill.table.bounds.tail + 2)
        for (now in 0..end) {
            rec.tick = now
            beforeTick(now)
            events.filter { it.tick == now }.forEach { player.deliver(cast, it) }
            player.tick(now)
        }
        // MatchEnded / quit: the cast is cancelled outside any row, with its instances still running
        if (cancelAtEnd) player.cancel(cast, com.astarworks.astera.core.domain.skillengine.table.CancelScope.Cast)
        // a row the player could not play is a Paper `[skill-error]` too: the recorded calls alone would only show a smaller trace
        check(player.faults().isEmpty()) { "${skill.def.id} faulted while playing: " + player.faults().joinToString("; ") { "tick ${it.tick} ${it.src}: ${it.message}" } }
        // the per-skill / per-kind counter (what `/astera skill faults` serves to e2e) must agree with the log: 0 faults after a full cast
        check(player.faultCounts.total() == 0) { "${skill.def.id} fault counter is ${player.faultCounts.total()}: ${player.faultCounts.counts()}" }
        return rec.calls
    }

    // ---- oracle ----------------------------------------------------------------------------------------------------

    /** Table instance id of each spawn ordinal (`#3` -> `blade`), as the last [expectedCalls] assigned them; the oracle names its rows with it. */
    protected var instanceNames: Map<String, String> = emptyMap()

    /** Spawn ordinals (`#3`) of the displays that ride a projectile body, as the last [expectedCalls] assigned them. The engine writes them whenever the body moves; where and when depends on the flight, so the table cannot promise those writes ([diff] sets them aside, [RiderFollow] judges them). */
    protected var bodyRiders: Set<String> = emptySet()

    /** What the table promises at the gateways, for [events] delivered while their owner state is live. */
    fun expectedCalls(skill: CompiledSkill, events: List<RuntimeEvent>): List<RecordedCall> {
        val table = skill.table
        val out = ArrayList<Pair<Int, RecordedCall>>()
        val ordinals = HashMap<String, Int>()
        val bodyOps = (table.worldRows + table.states.flatMap { it.rows } + table.subTables.values.flatMap { it.rows })
            .filter { (it.action as? Action.Spawn)?.kind == SpawnKind.PROJECTILE_BODY }.map { it.instance.text }.toSet()
        val riderOps = HashSet<String>()

        // rows are collected first and played in tick order, so an instance's ordinal follows the tick it spawns at (as the player assigns it)
        val pending = ArrayList<Pair<Int, Row>>()
        fun emit(base: Int, row: Row) {
            pending += (base + row.tick) to row
            val a = row.action
            if (a is Action.End) table.subTables[EventPath(a.instance.text + ".end")]?.rows?.forEach { emit(base + row.tick, it) }
        }

        fun render(tick: Int, row: Row) {
            val id = row.instance.text
            when (val a = row.action) {
                is Action.Spawn -> if (a.kind != SpawnKind.FRAME && a.kind != SpawnKind.PROJECTILE_BODY) {
                    ordinals[id] = ordinals.size
                    if (a.anchor?.text in bodyOps) riderOps += id
                    out += tick to RecordedCall(tick, "spawn", "#${ordinals.getValue(id)}", "kind=${a.kind.name.lowercase()}")
                }
                // a screen veil is an instance-lived quad per viewer; the sim's only viewer is the caster
                is Action.Effect -> if (a.op.value == "screen_veil") {
                    ordinals[id] = ordinals.size
                    out += tick to RecordedCall(tick, "spawn", "#${ordinals.getValue(id)}", "kind=veil")
                } else if (a.payload["kind"]?.let { known(it) } == "rig" && a.payload["of"] == null) {
                    // a rig that is its own frame carries the caster's held item as one mesh, written on every driver step of its span
                    ordinals[id] = ordinals.size
                    val n = ordinals.getValue(id)
                    out += tick to RecordedCall(tick, "spawn", "#$n", "kind=item")
                    val span = (known(a.payload.getValue("duration")) as Number).toInt()
                    for (t in tick..tick + span) out += t to RecordedCall(t, "write", "#$n", "dur=0")
                }
                is Action.Key -> ordinals[a.display.text]?.let { out += tick to RecordedCall(tick, "write", "#$it", "dur=${a.durationTicks}") }
                is Action.Teleport -> ordinals[a.display.text]?.let { out += tick to RecordedCall(tick, "write", "#$it", "") }
                is Action.TrailPoint -> ordinals[a.ribbon.text]?.let { out += tick to RecordedCall(tick, "trail", "#$it", "") }
                is Action.Particle -> out += tick to RecordedCall(tick, "particle", "-", "type=${a.type}" + known(a.count)?.let { " count=$it" }.orEmpty())
                is Action.ParticleDef -> out += tick to RecordedCall(tick, "particle", "-", "type=${a.def}")
                is Action.Sound -> out += tick to RecordedCall(
                    tick, "sound", "-",
                    listOfNotNull("sound=${a.sound}", known(a.volume)?.let { "vol=${RecordedCall.num(it)}" }, known(a.pitch)?.let { "pitch=${RecordedCall.num(it)}" }).joinToString(" "),
                )
                // ADR 3.6: an instance's natural end raises `<id>.end` exactly once; its sub-table is pasted at that tick (see emit)
                is Action.End -> ordinals[a.instance.text]?.let { out += tick to RecordedCall(tick, "remove", "#$it", "") }
                else -> Unit
            }
        }

        // R45: the machine-root world rows ride the cast clock from tick 0, owned by no state
        table.worldRows.forEach { emit(0, it) }

        // Walk the state chain; an event whose owner state is live inside its window pastes its sub-table and, when the
        // matching branch has a target, replaces the rest of the chain with the target state entered at the event tick.
        val byState = table.states.associateBy { it.state.value }
        var state: StateTable? = table.states.firstOrNull()
        var entry = 0
        var guard = 0
        var anyCut = false
        while (state != null && guard++ < 64) {
            val st: StateTable = state
            val length = when (val d = st.duration) {
                is Duration.Fixed -> d.ticks
                is Duration.Bounded -> d.range.max
            }
            val firing = events.filter { it.tick >= entry && it.tick <= entry + length }
                .mapNotNull { e -> st.branches.firstOrNull { it.event == e.path && inWindow(it, e.tick - entry) }?.let { e to it } }
                .minByOrNull { it.first.tick }
            val cut = firing?.takeIf { it.second.target != null }?.first?.tick
            st.rows.forEach { if (cut == null || entry + it.tick <= cut || it.detached) emit(entry, it) }
            // The sim has no victims, so a single-pass hit with a final detection raises `<op>.miss` on its own tick (TablePlayer.hit).
            for (r in st.rows) {
                val h = r.action as? Action.Hit ?: continue
                if (!h.finalDetection || h.window != HitWindow.Once || (cut != null && entry + r.tick > cut)) continue
                table.subTables[EventPath(r.instance.text + ".miss")]?.rows?.forEach { emit(entry + r.tick, it) }
            }
            // pasted sub-tables of this state (all firings inside the live part)
            for (e in events) {
                val b = st.branches.firstOrNull { it.event == e.path && inWindow(it, e.tick - entry) } ?: continue
                if (cut != null && e.tick > cut) continue
                b.subTable?.let { sub -> table.subTables[sub]?.rows?.forEach { emit(e.tick, it) } }
            }
            if (cut != null) {
                anyCut = true
                state = firing.second.target?.let { byState[it.value] }
                entry = cut
            } else {
                entry += length
                state = if (st.isFinal) null else st.then?.let { byState[it.value] }
            }
        }
        pending.sortedBy { it.first }.forEach { (t, r) -> render(t, r) }
        instanceNames = ordinals.entries.associate { "#${it.value}" to it.key }
        bodyRiders = riderOps.mapNotNull { ordinals[it]?.let { n -> "#$n" } }.toSet()
        // an early cast end (a scripted event) moves the run end to cast end + tail: detached visuals stop there, a live instance is removed there
        val runEnd = if (anyCut) entry + table.bounds.tail else null
        return out.sortedBy { it.first }.map { it.second }
            .mapNotNull { c -> if (runEnd == null || c.tick <= runEnd) c else if (c.verb == "remove") c.copy(tick = runEnd) else null }
    }

    private fun inWindow(b: Branch, rel: Int): Boolean = rel >= b.window.min && rel <= b.window.max

    private fun <T> known(v: Val<T>): T? = (v as? Val.Known<T>)?.value

    /**
     * Events a bot would script for [skill]: every sub-table-only branch fires once at the window's low edge (at least tick 1
     * after the owner is entered), plus the first state-changing branch of the initial state.
     */
    fun scriptedEvents(skill: CompiledSkill): List<RuntimeEvent> {
        val out = ArrayList<RuntimeEvent>()
        var entry = 0
        var state: StateTable? = skill.table.states.firstOrNull()
        val byState = skill.table.states.associateBy { it.state.value }
        var guard = 0
        var first = true
        while (state != null && guard++ < 64) {
            val st: StateTable = state
            val length = when (val d = st.duration) {
                is Duration.Fixed -> d.ticks
                is Duration.Bounded -> d.range.max
            }
            for (b in st.branches) {
                val rel = maxOf(b.window.min, 1).coerceAtMost(b.window.max)
                if (b.event.value == "end" || b.event.value == "cancel" || entry + rel > entry + length) continue
                if (b.target == null && b.subTable != null) out += event(b.event, entry + rel)
            }
            if (first) {
                // a timer that ties with a body `<op>.end` is not injected: in play the End row of that tick runs before the state leaves (a fused grenade)
                val endTicks = skill.table.subTables.filter { it.key.value.endsWith(".end") }.map { it.value.window.max }.toSet()
                st.branches.firstOrNull { it.target != null && !(it.event.value.startsWith("timer(") && maxOf(it.window.min, 1) in endTicks) }?.let { out += event(it.event, entry + maxOf(it.window.min, 1)) }
                first = false
            }
            entry += length
            state = if (st.isFinal) null else st.then?.let { byState[it.value] }
        }
        return out
    }

    protected fun event(path: EventPath, tick: Int, index: Int? = null): RuntimeEvent =
        RuntimeEvent(path, tick, index, ContactInfo(Vector3d(0.0, 0.0, 4.0), Vector3d(0.0, 1.0, 0.0)), null)

    /** The diff A2 asserts: compared verbs only, ordered by tick; expected tokens must be a subset of the recorded tokens. */
    fun diff(expected: List<RecordedCall>, actual: List<RecordedCall>): List<String> {
        val compared = setOf("spawn", "write", "trail", "remove", "particle", "sound")
        val expectedRemoves = expected.filter { it.verb == "remove" }.map { it.instance }.toSet()
        // several visuals that end on one tick (two detached rigs of a dual-wield skill) have no semantic order: the removes of a tick compare by instance
        val order = compareBy<RecordedCall>({ it.tick }, { if (it.verb == "remove") 1 else 0 }, { if (it.verb == "remove") it.instance else "" })
        // a rider's writes beyond the ones the table promises for that tick are the engine's follow writes
        val promised = expected.filter { it.verb == "write" }.groupingBy { it.tick to it.instance }.eachCount().toMutableMap()
        val a = actual.filter {
            if (it.verb == "write" && it.instance in bodyRiders) {
                val left = promised[it.tick to it.instance] ?: 0
                if (left > 0) { promised[it.tick to it.instance] = left - 1; true } else false
            } else it.verb in compared && (it.verb != "remove" || it.instance in expectedRemoves)
        }.sortedWith(order)
        val e = expected.filter { it.verb in compared }.sortedWith(order)
        val problems = ArrayList<String>()
        for (i in 0 until maxOf(a.size, e.size)) {
            val x = e.getOrNull(i)
            val y = a.getOrNull(i)
            when {
                x == null -> problems += "extra call #$i: $y"
                y == null -> problems += "missing call #$i: $x"
                x.tick != y.tick || x.verb != y.verb || x.instance != y.instance || !y.tokens.containsAll(x.tokens) ->
                    problems += "call #$i differs: expected $x, played $y"
            }
        }
        return problems
    }

    /** Plays [skill] with its scripted events and fails with every difference against the table. */
    fun assertPlays(skill: CompiledSkill, seed: Long = 7L, events: List<RuntimeEvent> = scriptedEvents(skill)) {
        val problems = diff(expectedCalls(skill, events), play(skill, seed, events))
        if (problems.isNotEmpty()) throw AssertionError("${skill.def.id} does not play its table:\n" + problems.take(20).joinToString("\n"))
    }

    // ---- evidence --------------------------------------------------------------------------------------------------

    /** Writes `build/skill-expand/<name>.txt` (ExpansionDump.render of the table) and returns the text. */
    fun writeEvidence(name: String, table: ExpansionTable, note: String? = null): String {
        val text = ExpansionDump.render(table)
        Files.createDirectories(evidenceDir)
        Files.writeString(evidenceDir.resolve("$name.txt"), (note?.let { "# $it\n" } ?: "") + text)
        return text
    }
}
