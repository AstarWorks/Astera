package com.astarworks.astera.schemagen

import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Scenario: the generated skill schemas cover every SkillOp leaf; a new op without a schema fails here. */
class SkillSchemaCoverageTest {

    @Test
    fun everyOpHasADefinitionAndADispatchEntry() {
        val entries = SkillSchemaGenerator.opEntries()
        assertThat(entries).isNotEmpty()
        for ((name, schema) in SkillSchemaGenerator.schemas().filterKeys { it != "item-frames" }) {
            val defs = schema["definitions"]!!.jsonObject
            for (e in entries) {
                assertThat(defs.keys).describedAs("$name: op_${e.yamlName} (${e.kclass.simpleName})").contains("op_${e.yamlName}")
            }
            for (dispatch in listOf("GameplayOp", "PresentationOp")) {
                val named = defs[dispatch]!!.toString()
                for (e in entries.filter { (it.section == "gameplay") == (dispatch == "GameplayOp") }) {
                    assertThat(named).describedAs("$name: $dispatch lists ${e.yamlName}").contains("\"${e.yamlName}\"")
                }
            }
        }
    }
}
