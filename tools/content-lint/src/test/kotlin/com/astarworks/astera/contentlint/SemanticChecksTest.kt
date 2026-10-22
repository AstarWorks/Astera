package com.astarworks.astera.contentlint

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.StringReader

class SemanticChecksTest {

    private val known = SemanticChecks.KnownIds(
        skills = setOf("example-blast"),
        statuses = setOf("example-burn"),
        particles = setOf("example-burst"),
    )

    private fun parse(yaml: String): JsonNode = YamlToJson.parse(StringReader(yaml.trimIndent()))

    @Test
    fun `supported mechanics and known references produce no errors`() {
        val node = parse(
            """
            id: s
            params: { type: summon }
            phases:
              archetype: buff
              cast:
                - type: play_sound
                  targets: { type: caster }
                  sound_id: entity.blaze.shoot
                - type: apply_status
                  targets: { type: target }
                  status_id: example-burn
                - type: spawn_particle
                  targets: { type: caster }
                  particle_id: example-burst
            """,
        )
        assertThat(SemanticChecks.check(node, known)).isEmpty()
    }

    @Test
    fun `no-op mechanic is an error`() {
        val node = parse(
            """
            phases:
              cast:
                - type: heal
                  targets: { type: caster }
                  amount: { type: literal, value: 5.0 }
            """,
        )
        assertThat(SemanticChecks.check(node, known)).singleElement().asString().contains("'heal'")
    }

    @Test
    fun `cel value is an error`() {
        val node = parse(
            """
            phases:
              cast:
                - type: play_sound
                  targets: { type: caster }
                  volume: { type: cel, script: "1.0" }
            """,
        )
        assertThat(SemanticChecks.check(node, known)).singleElement().asString().contains("CEL")
    }

    @Test
    fun `entity-state conditions team targeters and WP4 mechanics pass`() {
        val node = parse(
            """
            phases:
              cast:
                - type: if_then
                  targets: { type: enemies, center: { type: caster } }
                  predicate: { type: health_below, targets: { type: caster }, fraction: { type: literal, value: 0.5 } }
                  then:
                    - type: cleanse
                      targets: { type: caster }
                    - type: freeze
                      targets: { type: target }
                      ticks: { type: literal, value: 40 }
                    - type: leap
                      targets: { type: caster }
                      destination: { type: target }
                    - type: send_title
                      targets: { type: caster }
                      title: { en: "Hi" }
            """,
        )
        assertThat(SemanticChecks.check(node, known)).isEmpty()
    }

    @Test
    fun `unknown status particle and skill ids are errors`() {
        val node = parse(
            """
            params: { type: buff, status_effect_id: ghost-status }
            skills:
              - skill_id: ghost-skill
                phase_overrides:
                  cast_append:
                    - type: spawn_particle
                      targets: { type: caster }
                      particle_id: ghost-particle
            """,
        )
        val errors = SemanticChecks.check(node, known)
        assertThat(errors).hasSize(3)
        assertThat(errors).anyMatch { "ghost-status" in it }.anyMatch { "ghost-skill" in it }.anyMatch { "ghost-particle" in it }
    }

    @Test
    fun `params archetype type is not mistaken for a mechanic`() {
        val node = parse("params: { type: summon }")
        assertThat(SemanticChecks.check(node, known)).isEmpty()
    }
}
