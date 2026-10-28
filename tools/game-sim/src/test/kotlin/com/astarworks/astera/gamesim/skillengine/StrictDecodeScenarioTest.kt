package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.spec.OpSpecRegistry
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

    @Test
    fun rejectedExamplesFailWithNamedError() {
        val names = fixtureNames(rejectedDir)
        assertThat(names).hasSize(8)
        names.forEach { expectError(it.substringBefore('-')) }
    }

    /** ADR 2.0b: a schema default is only ever an identity (0, 1, empty, false, null, [0,0,0], [1,1,1], a vanilla-value word). */
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
        is JsonPrimitive -> v.booleanOrNull != null || v.doubleOrNull?.let { it == 0.0 || it == 1.0 } == true || (v.isString && (v.content.isEmpty() || v.content in vanillaWords))
    }
}
