package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.ExpansionDump
import com.astarworks.astera.core.application.skillengine.compose.PatchMeta
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * rev4.12 (design-tier-and-detach 2.3): `detach: true` presentation rows outlive their state, `machine.tail` is how long the run lingers
 * after the cast ends. The cast end (locks, cooldown, next input) never moves; only the detached visuals run to the tail.
 */
class DetachTailScenarioTest : SkillEngineScenario() {
    private fun skill(stateFor: Int, tail: Int?, vararg ops: String): String = (
        listOf("apiVersion: astera/v3", "kind: Skill", "id: tail-probe", "item: couture-rapier", "machine:", "  start: s") +
            listOfNotNull(tail?.let { "  tail: $it" }) +
            listOf("  states:", "    s:", "      for: $stateFor", "      then: end", "      enter:") + ops.map { "        - $it" } + listOf("    end: {final: true}")
        ).joinToString("\n") + "\n"

    private fun failure(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> throw AssertionError("expected a load error but it compiled")
        is Result.Failure -> r.error.message
    }

    private fun ok(yaml: String): CompiledSkill = when (val r = loadInline(yaml)) {
        is Result.Success -> r.value
        is Result.Failure -> throw AssertionError("did not load: ${r.error}")
    }

    private val block = "{id: d, op: display, kind: block, block: white_concrete, anchor: caster, at: 0, for: 10, detach: true}"

    private fun removeTicks(rec: GatewayRecorder): List<Int> = rec.calls.filter { it.verb == "remove" }.map { it.tick }

    @Test
    fun detachedRowPlaysAllItsTicksInAOneTickState() {
        val s = ok(skill(1, 10, block))
        val dump = ExpansionDump.render(s.table)
        assertThat(dump).contains("detached from=s")
        val rec = GatewayRecorder()
        play(s, 7L, emptyList(), null, rec)
        assertThat(rec.calls.filter { it.verb == "spawn" }.map { it.tick }).containsExactly(0)
        assertThat(removeTicks(rec)).describedAs("the display is removed at its own end tick 10, not at the state exit (1)").containsExactly(10)
        assertPlays(s)
    }

    @Test
    fun tailMovesTheRunEndButNotTheCastEnd() {
        val withTail = ok(skill(1, 10, block))
        val recA = GatewayRecorder()
        play(withTail, 7L, emptyList(), null, recA)
        val exits = recA.trace.filter { it.startsWith("exit:") }
        assertThat(exits.map { it.substringAfterLast('@') }).describedAs("the state exit (cast end) stays at tick 1").containsExactly("1")
        assertThat(ExpansionDump.render(withTail.table)).contains("tail=10").contains("run=[0,11]")
        assertThat(withTail.table.bounds.tail).isEqualTo(10)
        // the same skill without the detach has no tail and the same cast end
        val plain = ok(skill(1, null, "{id: d, op: display, kind: block, block: white_concrete, anchor: caster, at: 0, for: 1}"))
        val recB = GatewayRecorder()
        play(plain, 7L, emptyList(), null, recB)
        assertThat(recB.trace.filter { it.startsWith("exit:") }.map { it.substringAfterLast('@') }).isEqualTo(exits.map { it.substringAfterLast('@') })
    }

    @Test
    fun detachedRowPastTheTailIsALoadError() {
        val msg = failure(skill(1, 5, block))
        assertThat(msg).contains("ends after cast.end + tail").contains("tail=5").contains("needs tail >= 9")
        ok(skill(1, 9, block))
    }

    @Test
    fun nextCastDuringTheTailIsAllowedAndEarlierVisualsKeepPlaying() {
        val s = ok(skill(1, 10, block))
        val rec = GatewayRecorder()
        rec.castItem = s.def.item
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, rec.live, rec, rec.movement, rec.items, rec.world)
        player.hooks = rec.hooks
        fun request(at: Int) = CastRequest(skill = s, caster = rec.caster, world = WorldId("world"), casterFeet = Vec3(0.0, 0.0, 0.0), casterYawDeg = 0.0, casterPitchDeg = 0.0, seed = 7L, startTick = at)
        for (now in 0..16) {
            rec.tick = now
            if (now == 0 || now == 3) {
                val started = player.start(request(now), s)
                assertThat(started).describedAs("cast at tick $now").isInstanceOf(Result.Success::class.java)
            }
            player.tick(now)
        }
        assertThat(player.faults()).isEmpty()
        assertThat(rec.calls.filter { it.verb == "spawn" }.map { it.tick }).containsExactly(0, 3)
        assertThat(removeTicks(rec)).describedAs("the first display survives the second cast and ends on its own tick").containsExactly(10, 13)
    }

    @Test
    fun shippedHandlerVisualsAreDetachedAndPlay() {
        val composer = SkillComposer(contentDir.resolve("skills"))
        val composed = (composer.composeAll() as? Result.Success)?.value ?: throw AssertionError("compose failed")
        val ids = composed.keys.toSet()
        val defs = LinkedHashMap<String, SkillDef>()
        for ((id, c) in composed) if (c.patch == PatchMeta.BASE) defs[id] = (decodeTree(id, c.tree, ids) as? Result.Success)?.value ?: throw AssertionError("decode $id")
        for (id in listOf("ember-rod-spark", "fireball-cast", "frost-shard", "sword-slash", "sword-slash-t4", "spear-charge", "couture-rapier-s2", "shortbow-arrow", "sticky-bomb-throw")) {
            val compiled = (compileAgainst(defs.getValue(id), defs) as? Result.Success)?.value ?: throw AssertionError("$id did not compile")
            assertThat(compiled.table.bounds.tail).describedAs("$id tail").isGreaterThan(0)
            val handlerRows = compiled.table.subTables.values.flatMap { it.rows }
            assertThat(handlerRows.any { it.detached }).describedAs("$id has detached handler rows").isTrue()
            assertThat(ExpansionDump.render(compiled.table)).describedAs("$id dump").contains("detached from=")
            assertPlays(compiled)
        }
    }

    @Test
    fun aRigPointThatThePartDoesNotDeclareIsALoadError() {
        val rig = "{id: rig, op: rig, parent: caster, for: 6}"
        val msg = failure(skill(8, null, rig, "{id: p, op: particle, particle: flame, anchor: rig.root, at: 0, count: 1}"))
        assertThat(msg).contains("no point 'root'").contains("grip").contains("tip")
        ok(skill(8, null, rig, "{id: p, op: particle, particle: flame, anchor: rig.tip, at: 0, count: 1}"))
    }

    @Test
    fun detachRulesRefuseWhatWouldHideTheSchedule() {
        // gameplay is the state's schedule
        val dmg = "{id: dmg, op: damage, target: {kind: enemies, around: caster, radius: 3}, damage: {fire: 1}, detach: true}"
        assertThat(failure(skill(1, 4, dmg))).contains("is gameplay").contains("only presentation rows may outlive their state")
        // a tail needs a detached row, and a detached row needs a tail
        assertThat(failure(skill(1, 4, "{id: d, op: display, kind: block, block: white_concrete, anchor: caster, at: 0, for: 1}"))).contains("machine.tail: 4").contains("no row is detach: true")
        assertThat(failure(skill(1, null, block))).contains("machine.tail is missing or 0")
        assertThat(failure(skill(1, 0, block))).contains("machine.tail is missing or 0")
        // a rider of a detached frame must outlive it too
        val frame = "{id: f, op: frame, anchor: caster, at: 0, for: 6, detach: true}"
        val rider = "{id: r, op: display, kind: block, block: white_concrete, anchor: f, at: 0, for: 6}"
        assertThat(failure(skill(1, 6, frame, rider))).contains("rider 's/r' of detached frame").contains("must be detach: true")
        ok(skill(1, 6, frame, "{id: r, op: display, kind: block, block: white_concrete, anchor: f, at: 0, for: 6, detach: true}"))
    }
}
