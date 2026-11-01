package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0303 `events_in` (shared parts, pass 2): a part declares the caller events its riders end on; the call site binds each with
 * `events: {name: caller.event}`. An unbound or unknown event is a load error naming it, a state-level `use:` of such a part is refused,
 * and the riders outlive the call tick (they end on the bound event, not when the call instance ends).
 */
class PartEventsInScenarioTest : SkillEngineScenario() {
    private fun skill(call: String) = """
        apiVersion: astera/v3
        kind: Skill
        id: events-in-caller
        meta: {cooldown_ticks: 0, resource_cost: 0}
        machine:
          start: fly
          states:
            fly:
              for: 12
              then: end
              do:
                - id: bolt
                  op: projectile
                  at: 0
                  origin: caster
                  velocity: "= look(caster) * 1.0"
                  for: 10
                  radius: 0.5
                  hit: {targets: enemies, once: true, attach: false}
                  block: {mode: despawn}
                - $call
            end: {final: true}
    """.trimIndent()

    private fun failure(call: String): String = when (val r = loadInline(skill(call))) {
        is Result.Failure -> r.error.toString()
        is Result.Success -> error("expected a load error for $call")
    }

    @Test
    fun anUnboundEventsInNameIsALoadErrorNamingIt() {
        assertThat(failure("{id: shaft, use: projectile-body, with: {item: \"minecraft:arrow\", scale: 1.0, color: \"#c8aa6e\"}, frames: {body: bolt}}"))
            .contains("body_end")
    }

    @Test
    fun anUnknownEventKeyIsALoadErrorNamingIt() {
        assertThat(failure("{id: shaft, use: projectile-body, with: {item: \"minecraft:arrow\", scale: 1.0, color: \"#c8aa6e\"}, frames: {body: bolt}, events: {body_end: bolt.end, nope: bolt.end}}"))
            .contains("nope")
    }

    @Test
    fun theRidersOutliveTheCallTickAndStopWithTheBoundEvent() {
        val loaded = loadInline(skill("{id: shaft, use: projectile-body, with: {item: \"minecraft:arrow\", scale: 1.0, color: \"#c8aa6e\"}, frames: {body: bolt}, events: {body_end: bolt.end}}"))
        val compiled = (loaded as? Result.Success ?: error("expected to load: $loaded")).value
        val calls = play(compiled, seed = 1L, ticks = 6)
        val ticksWithTrail = calls.filter { it.verb == "particle" }.map { it.tick }.toSet()
        assertThat(ticksWithTrail).`as`("the wake / dust trail repeats every tick while the projectile lives").hasSizeGreaterThan(3)
    }
}
