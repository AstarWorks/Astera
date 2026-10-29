package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.spec.OpSpecRegistry
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.SkillEngineSpecs
import com.astarworks.astera.core.domain.Result
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** A8 (ADR-0303 section 12): each rejected example fails with its named SkillDecodeError; the op schema hides no non-identity default. */
class StrictDecodeScenarioTest : SkillEngineScenario() {
    private val rejectedDir = examplesDir.resolve("rejected")

    private fun expectError(prefix: String) {
        val name = fixtureNames(rejectedDir).single { it.startsWith("$prefix-") }
        val text = Files.readString(rejectedDir.resolve("$name.yaml"))
        val expected = Regex("""^# error: SkillDecodeError\.(\w+)""").find(text.lineSequence().first())?.groupValues?.get(1)
            ?: throw AssertionError("$name: first line must be '# error: SkillDecodeError.<Name>'")
        when (val r = compileText("$name.yaml", text)) {
            is Result.Success -> throw AssertionError("$name compiled but must fail with $expected")
            is Result.Failure -> assertThat(r.error::class.simpleName).`as`("$name: ${r.error}").isEqualTo(expected)
        }
    }

    @Test fun r1() = expectError("r1")

    @Test fun r2() = expectError("r2")

    @Test fun r3() = expectError("r3")

    @Test fun r4() = expectError("r4")

    @Test fun r5() = expectError("r5")

    @Test fun r6() = expectError("r6")

    @Test fun r7() = expectError("r7")

    @Test fun r8() = expectError("r8")

    @Test fun r9() = expectError("r9")

    @Test fun r10() = expectError("r10")

    @Test fun r11() = expectError("r11")

    @Test fun r12() = expectError("r12")

    @Test fun r13() = expectError("r13")

    @Test fun r14() = expectError("r14")

    @Test
    fun rejectedExamplesFailWithNamedError() {
        val names = fixtureNames(rejectedDir)
        assertThat(names).hasSize(14)
        names.forEach { expectError(it.substringBefore('-')) }
    }

    /** ADR 2.0b: a schema default is only ever an identity (0, 1, empty, false (never true), null, [0,0,0], [1,1,1], a vanilla-value word). */
    @Test
    fun noHiddenDefaultsInSchema() {
        val schema = OpSpecRegistry(SkillEngineSpecs.all()).jsonSchema()
        val offenders = ArrayList<String>()
        fun walk(path: String, node: JsonElement) {
            when (node) {
                is JsonObject -> node.forEach { (k, v) ->
                    if (k == "default" && !identity(v)) offenders += "$path = $v"
                    walk("$path/$k", v)
                }
                is JsonArray -> node.forEachIndexed { i, v -> walk("$path[$i]", v) }
                else -> Unit
            }
        }
        walk("", schema)
        assertThat(offenders).`as`("non-identity defaults in the op schema").isEmpty()
    }

    private val vanillaWords = setOf("fixed", "linear", "none", "all", "center", "caster", "world", "snapshot", "normal", "full", "crisp", "soft")

    private fun identity(v: JsonElement): Boolean = when (v) {
        is JsonNull -> true
        is JsonArray -> v.isEmpty() || v.map { (it as? JsonPrimitive)?.doubleOrNull }.let { n -> n.all { it == 0.0 } || n.all { it == 1.0 } }
        is JsonObject -> v.isEmpty()
        is JsonPrimitive -> v.booleanOrNull == false || v.doubleOrNull?.let { it == 0.0 || it == 1.0 } == true || (v.isString && (v.content.isEmpty() || v.content in vanillaWords))
    }

    // ---- E2: document anchors -------------------------------------------------------------------------------------------

    private fun anchorSkill(entry: String) = """
        apiVersion: astera/v3
        kind: Skill
        id: doc-anchor
        anchors:
          - $entry
        machine:
          start: s
          states:
            s:
              for: 4
              do:
                - {id: snd, op: sound, sound: entity.player.attack.sweep, anchor: tip, at: 0}
    """.trimIndent()

    private fun failureOf(yaml: String): SkillDecodeError = when (val r = loadInline(yaml)) {
        is Result.Success -> throw AssertionError("expected a load error")
        is Result.Failure -> r.error
    }

    @Test
    fun documentAnchorMountIsUnknownKey() {
        val e = failureOf(anchorSkill("{id: tip, anchor: caster.eye, mode: follow, mount: caster}"))
        assertThat(e).isInstanceOf(SkillDecodeError.UnknownKey::class.java)
        assertThat(e.toString()).contains("mount")
    }

    @Test
    fun documentAnchorModeRequired() {
        val e = failureOf(anchorSkill("{id: tip, anchor: caster.eye}"))
        assertThat(e.toString()).contains("anchors[0] 'tip': mode is required: follow (re-read every tick) or snapshot (sampled once when the cast starts)")
    }

    @Test
    fun documentAnchorOffsetAcceptsCel() {
        val r = loadInline(anchorSkill("{id: tip, anchor: caster.eye, mode: follow, offset: {forward: \"= 0.25 * 2\", up: 0.1}}"))
        assertThat(r).isInstanceOf(Result.Success::class.java)
    }
}
