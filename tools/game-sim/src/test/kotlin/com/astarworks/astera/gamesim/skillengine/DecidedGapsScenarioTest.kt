package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.ExpansionDump
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Batch2 A / B / D3 / D4 / F1 / F4 (design-gap-batch2): one case per decided gap, through real loading and the real player. */
class DecidedGapsScenarioTest : SkillEngineScenario() {
    private fun skill(vararg ops: String, extraTop: String = ""): String =
        "apiVersion: astera/v3\nkind: Skill\nid: gaps\nitem: couture-rapier\n$extraTop" +
            "machine:\n  start: s\n  states:\n    s:\n      for: 20\n      enter:\n" + ops.joinToString("\n") { "        - $it" } + "\n"

    private fun failure(yaml: String): String = when (val r = loadInline(yaml)) {
        is Result.Success -> throw AssertionError("expected a load error")
        is Result.Failure -> r.error.toString()
    }

    private fun ok(yaml: String) = (loadInline(yaml) as? Result.Success)?.value ?: throw AssertionError("load failed: " + loadInline(yaml))

    private fun spawns(yaml: String): List<String> {
        val rec = GatewayRecorder()
        play(ok(yaml), 7L, emptyList(), 6, rec)
        return rec.oracleRows.filter { it.verb == "spawn" && it.tick == 0 }.map { it.payload + " " + it.detail }
    }

    private val family =
        "{id: blade, op: frame, times: 2, anchor: caster, offset: {up: 1, forward: \"= i * 2\"}, turn: [{axis: up, deg: \"= i * 90\"}], for: 10, motion: [{law: spin, axis: up, deg_per_tick: 10, for: 10}]}"

    // A1 / A2 + the per-index anchor rebind: a rider of a moving family keeps its own base offset and sits on its own member
    @Test
    fun familyRidersSitAtPerIndexOffset() {
        val rows = spawns(skill(family, "{id: d, op: display, kind: block, times: 2, block: stone, anchor: \"blade[i]\", for: 10, offset: {up: 0.5}, motion: [{law: grow, to: 2, at: 0, for: 4}]}"))
        assertThat(rows).hasSize(2)
        assertThat(rows[0]).contains("pos=0.000000,2.500000,0.000000")
        assertThat(rows[1]).contains("pos=0.000000,2.500000,2.000000")
        assertThat(rows[1]).contains("rot=0.000000,0.707107,0.000000,0.707107")
    }

    @Test
    fun movingDisplayKeepsBaseOffsetAndRotate() {
        val yaml = skill("{id: d, op: display, kind: block, block: stone, anchor: caster, for: 8, offset: {up: 2}, rotate: [{axis: up, deg: 90}], motion: [{law: move, velocity: {forward: 0.5}, for: 4}]}")
        val rows = spawns(yaml)
        assertThat(rows.single()).contains("rot=0.000000,0.707107,0.000000,0.707107")
        assertThat(rows.single()).doesNotContain("pos=0.000000,0.000000,0.000000")
    }

    @Test
    fun bareFamilyAnchorBindsSameIndex() {
        val rows = spawns(skill(family, "{id: d, op: display, kind: block, times: 2, block: stone, anchor: blade, for: 10}"))
        assertThat(rows).hasSize(2)
        assertThat(rows[0]).isNotEqualTo(rows[1])
        assertThat(rows[1]).contains("pos=0.000000,2.000000,2.000000")
    }

    @Test
    fun bareFamilyAnchorOutsideFamilyIsLoadError() {
        val msg = failure(skill(family, "{id: d, op: display, kind: block, block: stone, anchor: blade, for: 10}"))
        assertThat(msg).contains("family of 2").contains("blade[k]")
    }

    @Test
    fun staticFrameAppearsInDump() {
        val s = ok(skill("{id: hub, op: frame, anchor: caster, offset: {up: 1}, for: 6}"))
        assertThat(ExpansionDump.render(s.table)).contains("hub").contains("FRAME")
        val rec = GatewayRecorder()
        play(s, 7L, emptyList(), 4, rec)
        assertThat(rec.oracleRows.any { it.verb == "frame" && it.instance.endsWith("hub") }).isTrue()
    }

    // B1
    @Test
    fun scaleTripleStaysOneVectorAndMapFormLoads() {
        ok(skill("{id: d, op: display, kind: block, block: stone, times: 3, anchor: caster, for: 4, scale: [1, 2, 3]}"))
        ok(skill("{id: d, op: display, kind: block, block: stone, times: 3, anchor: caster, for: 4, scale: {left: 1, up: 2, forward: 3}}"))
    }

    @Test
    fun perIndexBlockForcesCopyValues() {
        val s = ok(skill("{id: d, op: frame, times: 2, anchor: caster, for: 4, per_index: {offset: [{up: 1}, {up: 3}]}}"))
        val rec = GatewayRecorder()
        play(s, 7L, emptyList(), 2, rec)
        val frames = rec.oracleRows.filter { it.verb == "frame" && it.tick == 0 }.map { it.detail }
        assertThat(frames).hasSize(2)
        assertThat(frames[0]).isNotEqualTo(frames[1])
    }

    // B5
    @Test
    fun everyTimesForNamesLasts() {
        val msg = failure(skill("{id: d, op: display, kind: block, block: stone, anchor: caster, every: 4, times: 3, for: 10}"))
        assertThat(msg).contains("lasts")
    }

    // B2
    @Test
    fun particleDefCountOverridesDef() {
        val s = ok(skill("{id: p, op: particle_def, def: spark, anchor: caster, count: 7}"))
        assertThat(ExpansionDump.render(s.table)).contains("count")
    }

    // B3
    @Test
    fun dustSizeFromParam() {
        val s = ok(
            skill(
                "{id: p, op: particle, particle: dust, anchor: caster, color: \"#ff5fa2\", size: \"\${sz}\", count: 1}",
                extraTop = "params:\n  sz: {type: number, default: 1.5}\n",
            ),
        )
        assertThat(ExpansionDump.render(s.table)).contains("1.5")
    }

    // B4
    @Test
    fun growOffsetToPartialAxes() {
        val rows = spawns(skill("{id: d, op: display, kind: block, block: stone, anchor: caster, for: 8, motion: [{law: grow, prop: offset, to: {up: 0.8}, for: 4}]}"))
        assertThat(rows).hasSize(1)
    }

    @Test
    fun growOffsetToBareNumberIsLoadError() {
        val msg = failure(skill("{id: d, op: display, kind: block, block: stone, anchor: caster, for: 8, motion: [{law: grow, prop: offset, to: 0.8, for: 4}]}"))
        assertThat(msg).contains("has no axis")
    }

    // D3
    @Test
    fun keyPoseFoldsParamAndIndex() {
        val yaml = skill(
            "{id: d, op: frame, times: 2, anchor: caster, for: 8, motion: [{law: keys, keys: [{t: 0, pose: [{axis: up, deg: \"= i * 45\"}]}, {t: 4, pose: [{axis: up, deg: \"\${turn}\"}]}]}]}",
            extraTop = "params:\n  turn: {type: number, default: 90}\n",
        )
        ok(yaml)
    }

    // D4
    @Test
    fun ribbonWidthLawWithoutWindowNamesFor() {
        val msg = failure(skill("{id: r, op: ribbon, of: caster, history: 10, width: 0.3, color: \"#ffffff\", style: soft, for: 10, motion: [{law: grow, prop: width, to: 0.1}]}"))
        assertThat(msg).contains("window").contains("for")
    }

    // F1
    @Test
    fun reservedNameAsOpIdIsLoadError() {
        val msg = failure(skill("{id: contact, op: frame, anchor: caster, for: 6}"))
        assertThat(msg).contains("'contact' is a builtin name")
    }

    @Test
    fun reservedNameAsAnchorIdIsLoadError() {
        val msg = failure(skill("{id: hub, op: frame, anchor: caster, for: 6}", extraTop = "anchors:\n  - {id: contact, anchor: caster.eye}\n"))
        assertThat(msg).contains("anchors[0]: 'contact' is a builtin name")
    }
}
