package com.astarworks.astera.resourcepack

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Integration: the real pack carries the skill line item model, its 15 alpha steps and the skill-line shader marker. */
class ShippedSkillLinePackTest {
    private val content = Path.of(System.getProperty("astera.contentDir"))
    private val json = ObjectMapper()
    private val pack by lazy { ResourcePackBuilder(content).collect() }

    @Test
    fun `skill line definition, fifteen models and textures, shader marker`() {
        val definition = json.readTree(pack.getValue("assets/astera/items/skill_line.json"))
        val cases = definition.path("model").path("cases")
        assertThat(cases.size()).isEqualTo(ResourcePackBuilder.LINE_ALPHA_STEPS)
        for (n in 1..ResourcePackBuilder.LINE_ALPHA_STEPS) {
            val step = "a%02d".format(n)
            assertThat(pack).containsKey("assets/astera/models/item/skill_line/$step.json")
            assertThat(pack).containsKey("assets/astera/textures/items/skill_line/$step.png")
            assertThat(cases.any { it.path("when").asText() == step }).isTrue()
        }
        val ledger = json.readTree(pack.getValue("assets/astera/shader-effects/markers.json"))
        assertThat(ledger.any { it.path("effect").asText() == "skill-line" && it.path("value").asText() == "192:240" }).isTrue()
    }
}
