package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.LockMove
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * W2-02 acceptance (wave2-resolutions section 3: R5, R6, R9, R24, R25, R26, R32, R39, R41): the caster / target effect ops
 * lower to the rows the player and gateway read. The table-level cases need only the loader; driving the Effect rows into the
 * gateways needs the TablePlayer arms requested from w1b-02d / 02i.
 */
class EffectOpScenarioTest : SkillEngineScenario() {

    private fun skill(vararg ops: String, anchors: String = ""): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: effect-probe", "item: any",
        ) + (if (anchors.isEmpty()) emptyList() else listOf("anchors:", "  - $anchors")) +
            listOf("machine:", "  start: s", "  states:", "    s:", "      for: 40", "      do:") + ops.map { "        - $it" }
        ).joinToString("\n") + "\n"

    private fun compile(yaml: String): CompiledSkill = when (val r = loadInline(yaml)) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun fails(yaml: String): Boolean = loadInline(yaml) is Result.Failure

    private fun rows(s: CompiledSkill): List<Row> = s.table.states.flatMap { it.rows }

    private fun effect(s: CompiledSkill, op: String): Action.Effect =
        rows(s).map { it.action }.filterIsInstance<Action.Effect>().first { it.op.value == op }

    @Test
    fun impulseFrameVecResolvesCasterAxes() {
        val s = compile(skill("{op: impulse, at: 2, target: caster, vector: {of: me, forward: 1.5, up: 0.5}}", anchors = "{id: me, anchor: caster.hand_r}"))
        val v = effect(s, "impulse").payload.getValue("vector") as Val.Live<*>
        assertThat(v.name).startsWith("= ").contains("forward(").contains("up(").doesNotContain("left(")
        assertThat(fails(skill("{op: impulse, target: caster, vector: {of: nowhere, forward: 1}}"))).isTrue()
    }

    @Test
    fun impulseWorldVec() {
        val s = compile(skill("{op: impulse, target: caster, vector: {x: 0, y: 1.2, z: 0}}"))
        assertThat(effect(s, "impulse").payload.getValue("vector")).isInstanceOf(Val.Known::class.java)
        assertThat(fails(skill("{op: impulse, target: caster, vector: [0, 1, 0]}"))).`as`("positional vectors are rejected").isTrue()
    }

    @Test
    fun cleanseNegativeOnly() {
        val s = compile(skill("{op: cleanse, at: 1, target: caster, negative_only: true}"))
        assertThat(effect(s, "cleanse").payload.getValue("negative_only")).isEqualTo(Val.Known(true))
        assertThat(fails(skill("{op: cleanse, target: caster}"))).`as`("negative_only is required").isTrue()
    }

    @Test
    fun consumeItemCount() {
        val s = compile(skill("{op: consume_item, target: caster, item: arrow, count: 3}"))
        val p = effect(s, "consume_item").payload
        assertThat(p.getValue("item")).isEqualTo(Val.Known("arrow"))
        assertThat(p.getValue("count")).isEqualTo(Val.Known(3.0))
        assertThat(fails(skill("{op: consume_item, target: caster, item: arrow, count: 0}"))).isTrue()
        val st = compile(skill("{op: consume_status, target: caster, status: focus}"))
        assertThat(effect(st, "consume_status").payload.getValue("status")).isEqualTo(Val.Known("focus"))
    }

    @Test
    fun dashRequiredFlagsMissingIsLoadError() {
        val flags = "follow_pitch: false, stop_on_block: true, invulnerable: false, face_movement: false"
        assertThat(fails(skill("{id: d, op: dash, speed: 1.2, for: 8, $flags}"))).isFalse()
        for (missing in listOf("follow_pitch: false, ", "stop_on_block: true, ", "invulnerable: false, ", "face_movement: false")) {
            assertThat(fails(skill("{id: d, op: dash, speed: 1.2, for: 8, ${flags.replace(missing, "")}}"))).`as`("without $missing").isTrue()
        }
        assertThat(fails(skill("{id: d, op: dash, speed: 1.2, for: 8, radius: 2, $flags}"))).`as`("radius was removed").isTrue()
    }

    @Test
    fun dashUntilHitEndsEarly() {
        val flags = "follow_pitch: false, stop_on_block: true, invulnerable: false, face_movement: false"
        val s = compile(skill("{id: d, op: dash, speed: 1.2, for: 8, until: hit, $flags}"))
        // the static window stays the upper bound; ending at the event is the player's job (request to w1b-02d)
        val ends = rows(s).filter { it.action is Action.Effect && (it.action as Action.Effect).payload["kind"] == Val.Known("dash.end") }
        assertThat(ends.map { it.tick }).containsExactly(8)
    }

    @Test
    fun freezeHoldsEntitiesNotClock() {
        val s = compile(skill("{op: freeze, at: 4, ticks: 10, scope: [caster, event.victim]}"))
        val e = effect(s, "freeze")
        assertThat(e.payload.getValue("ticks")).isEqualTo(Val.Known(10.0))
        assertThat(e.payload.getValue("scope")).isEqualTo(Val.Known(listOf("caster", "event.victim")))
        assertThat(rows(s).none { it.action is Action.Lock }).`as`("no clock lock").isTrue()
        assertThat(fails(skill("{op: freeze, ticks: 10, scope: []}"))).isTrue()
    }

    @Test
    fun lockWalkLeavesJump() {
        val walk = compile(skill("{id: l, op: lock, move: walk, at: 0, for: 6}"))
        assertThat(rows(walk).map { it.action }.filterIsInstance<Action.Lock>().single().move).isEqualTo(LockMove.WALK)
        assertThat(fails(skill("{id: l, op: lock, move: true, at: 0, for: 6}"))).`as`("the old boolean spelling is gone").isTrue()
    }

    @Test
    fun cameraShakeFromHit() {
        val s = compile(skill("{op: camera_shake, at: 1, target: caster, intensity: 0.4, for: 6, direction: from_hit}"))
        val p = effect(s, "camera_shake").payload
        assertThat(p.getValue("ticks")).isEqualTo(Val.Known(6.0))
        assertThat(p.getValue("direction")).isEqualTo(Val.Known("from_hit"))
        assertThat(fails(skill("{op: camera_shake, target: caster, intensity: 0.4}"))).`as`("needs a span").isTrue()
        val stop = compile(skill("{op: stop_sound, target: caster, sound: entity.player.attack.sweep}"))
        assertThat(effect(stop, "stop_sound").payload.getValue("sound")).isEqualTo(Val.Known("entity.player.attack.sweep"))
    }

    @Test
    fun hurtFacesAwayFromContact() {
        val s = compile(skill("{op: hurt, at: 0, target: event.victim, from: contact}", anchors = "{id: contact, anchor: caster.hand_r}"))
        val p = effect(s, "hurt").payload
        assertThat(p.getValue("from")).isInstanceOf(Val.Known::class.java)
        assertThat(p.keys).containsExactlyInAnyOrder("target", "from")
        assertThat(fails(skill("{op: hurt, target: event.victim, from: nowhere}"))).isTrue()
    }

    @Test
    fun bodyClipEndsAtFor() {
        val s = compile(skill("{id: b, op: body, at: 2, anim: slash_a, speed: 1.5, for: 9}"))
        val start = rows(s).single { it.action is Action.Effect && (it.action as Action.Effect).op.value == "body" }
        val end = rows(s).single { it.action is Action.End && it.instance == start.instance }
        assertThat(end.tick - start.tick).isEqualTo(9)
        assertThat(fails(skill("{id: b, op: body, anim: slash_a}"))).`as`("needs for or until").isTrue()
    }

    private fun effectCalls(s: CompiledSkill, rec: GatewayRecorder = GatewayRecorder(), events: List<com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent> = emptyList()) =
        play(s, 1L, events, null, rec).filter { it.verb == "effect" || it.verb == "item" }

    @Test
    fun targetKeyReachesGatewaysCleanseConsumeStopSoundCameraShake() {
        val rec = GatewayRecorder().also { it.stock["arrow"] = 5 }
        val s = compile(
            skill(
                "{op: cleanse, at: 1, target: caster, negative_only: true}",
                "{op: consume_status, at: 2, target: caster, status: focus}",
                "{op: consume_item, at: 3, target: caster, item: arrow, count: 3}",
                "{op: camera_shake, at: 4, target: caster, intensity: 0.4, for: 6, direction: none}",
                "{op: stop_sound, at: 5, target: caster, sound: entity.player.attack.sweep}",
                "{op: freeze, at: 6, ticks: 10, scope: [caster]}",
            ),
        )
        val calls = effectCalls(s, rec).map { it.tick to it.payload }
        assertThat(calls).contains(
            1 to "cleanse", 2 to "consume_status=focus", 3 to "consume=3", 4 to "camera_shake=0.4 ticks=6 from=none",
            5 to "stop_sound=entity.player.attack.sweep", 6 to "freeze=10",
        )
        assertThat(rec.stock["arrow"]).isEqualTo(2)
    }

    @Test
    fun hurtResolvesItsFromInstanceAndCalls() {
        val s = compile(skill("{op: hurt, at: 2, target: caster, from: contact}", anchors = "{id: contact, anchor: caster.hand_r}"))
        assertThat(effectCalls(s).map { it.tick to it.payload }).contains(2 to "hurt")
    }

    @Test
    fun bodyClipPlaysAndStopsAtForAndAtUntil() {
        val forOnly = compile(skill("{id: b, op: body, at: 2, anim: slash_a, speed: 1.5, for: 9}"))
        assertThat(effectCalls(forOnly).map { it.tick to it.payload }).contains(2 to "body=slash_a speed=1.5", 11 to "body_stop=slash_a")
        val until = compile(skill("{id: b, op: body, at: 2, anim: slash_a, speed: 1.0, for: 20, until: hit}"))
        val hit = com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent(
            com.astarworks.astera.core.domain.skillengine.table.EventPath("hit"), 6, null, null, null,
        )
        val calls = effectCalls(until, events = listOf(hit)).map { it.tick to it.payload }
        assertThat(calls).contains(6 to "body_stop=slash_a")
        assertThat(calls.filter { it.second == "body_stop=slash_a" }).hasSize(1)
    }

    @Test
    fun dashUntilEventEndsTheInstanceAtThatTick() {
        val flags = "follow_pitch: false, stop_on_block: true, invulnerable: false, face_movement: false"
        val s = compile(skill("{id: d, op: dash, speed: 1.2, for: 8, until: hit, $flags}"))
        val sub = s.table.subTables.values.single { it.event.value == "hit" }
        assertThat(sub.rows.map { it.action }.filterIsInstance<Action.End>()).hasSize(1)
        assertThat(sub.rows.any { (it.action as? Action.Effect)?.payload?.get("kind") == Val.Known("dash.end") }).isTrue()
    }

    @Test
    fun soundRowsCarryRelativeTicksNotTheClockOffset() {
        // the unroller already shifts each row by its occurrence offset; a lowered tick of clockOffset counted it twice
        val s = compile(skill("{id: z, op: sound, sound: entity.player.attack.sweep, anchor: me, every: 3, times: 3}", anchors = "{id: me, anchor: caster}"))
        val sounds = play(s, 1L).filter { it.verb == "sound" }.map { it.tick }
        assertThat(sounds).containsExactly(0, 3, 6)
    }

    /**
     * `freeze` with an instance in its scope (v2 `FreezeOpExecutor` split entities / frames, ControlOpExecutors.kt:14-33; frames held in
     * `CastInstance.frozenUntil`, :539-541): the volley holds its place while frozen, so a text display riding it six ticks apart
     * lands three flight-steps short of the unfrozen cast. Never a fault, and the entity half (`caster`) still reaches the gateway.
     */
    @Test
    fun freezeHoldsAProjectileBodyInstance() {
        fun yaml(freeze: Boolean): String = (
            listOf(
                "apiVersion: astera/v3", "kind: Skill", "id: freeze-body", "item: any",
                "anchors:", "  - {id: muzzle, anchor: caster.eye, offset: {forward: 0.5}}",
                "machine:", "  start: s", "  states:", "    s:", "      for: 30", "      do:",
                "        - {id: bolt, op: projectile, origin: muzzle, velocity: \"= forward * 1.0\", speed: 1.0, radius: 0.4, for: 25, hit: {targets: enemies, once: true}, block: {mode: despawn}}",
                "        - {id: da, op: display, kind: text, text: a, anchor: bolt, at: 3, for: 1}",
                "        - {id: db, op: display, kind: text, text: b, anchor: bolt, at: 9, for: 1}",
            ) + (if (freeze) listOf("        - {id: fz, op: freeze, at: 4, ticks: 3, scope: [caster, bolt]}") else emptyList())
            ).joinToString("\n") + "\n"

        fun gap(freeze: Boolean): Pair<Double, List<String>> {
            val rec = GatewayRecorder()
            val at = HashMap<String, com.astarworks.astera.core.domain.geometry.Vec3>()
            val visual = object : com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway by rec.visual {
                override fun spawnText(
                    cast: com.astarworks.astera.core.application.skillengine.runtime.port.CastRef, text: String, backgroundHex: String, backgroundAlpha: Double,
                    shadow: Boolean, seeThrough: Boolean, pose: com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose, brightness: Int?,
                    view: com.astarworks.astera.core.application.skillengine.runtime.port.DisplayView,
                ): com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle {
                    at[text] = pose.frame.origin
                    return rec.visual.spawnText(cast, text, backgroundHex, backgroundAlpha, shadow, seeThrough, pose, brightness, view)
                }
            }
            val player = playProbe(compile(yaml(freeze)), ticks = 12, rec = rec, visual = visual)
            val a = at.getValue("a")
            val b = at.getValue("b")
            val d = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y) + (b.z - a.z) * (b.z - a.z))
            assertThat(player.faults()).isEmpty()
            return d to rec.calls.filter { it.verb == "effect" }.map { it.payload }
        }

        val (free, freeCalls) = gap(false)
        val (held, heldCalls) = gap(true)
        assertThat(freeCalls).isEmpty()
        assertThat(heldCalls).containsExactly("freeze=3")
        // six unfrozen flight ticks between the two samples; three of them are frozen
        assertThat(held).isCloseTo(free / 2.0, org.assertj.core.data.Offset.offset(1e-6))
    }
}
