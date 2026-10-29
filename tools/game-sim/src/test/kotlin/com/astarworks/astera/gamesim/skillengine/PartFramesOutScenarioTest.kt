package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0303 section 9: a caller binds `frames: {spot: <sibling call>.<frames_out name>}` to a frame the sibling part publishes. */
class PartFramesOutScenarioTest : SkillEngineScenario() {

    private fun text(bind: String) = """
apiVersion: astera/v3
kind: Part
id: shooter
frames_out: {bolt: bolt}
emits: [end]
machine:
  start: flying
  states:
    flying:
      for: 10
      then: end
      enter:
        - {id: bolt, op: projectile, origin: caster, velocity: "= look(caster) * 1", for: 10, radius: 0.5, hit: {targets: enemies, once: true, attach: false}, block: {mode: despawn}}
    end: {final: true}
---
apiVersion: astera/v3
kind: Part
id: boom
frames_in: {spot: anchor}
emits: [end]
machine:
  start: blast
  states:
    blast:
      for: 4
      then: end
      enter:
        - {id: bang, op: sound, sound: entity.generic.explode, anchor: spot}
    end: {final: true}
---
apiVersion: astera/v3
kind: Skill
id: frames-out-probe
item: couture-rapier
machine:
  start: fly
  states:
    fly:
      use: shooter
      on: [{event: fly.end, to: boom}]
    boom:
      use: boom
      frames: {spot: $bind}
      on: [{event: boom.end, to: done}]
    done: {final: true}
"""

    @Test
    fun aFrameTheSiblingDoesNotPublishStaysUnresolved() {
        assertThat(compileText("frames-out-probe.yaml", text("fly.nothing"))).isInstanceOf(Result.Failure::class.java)
    }

}
