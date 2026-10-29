package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.ContactInfo
import com.astarworks.astera.core.application.skillengine.runtime.player.RuntimeEvent
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import org.assertj.core.api.Assertions.assertThat
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import org.junit.jupiter.api.Test

/**
 * A state entered by a contact-carrying event (couture-rapier-s2: `volley.hit -> stuck`) keeps that event's binding for its own `do`
 * rows, so an `anchor: contact` row there resolves on Paper exactly as the sim does (e2e: `live cell 'contact' is not resolvable as a
 * rotation (no contact bound)`, 104 faults). Without any contact the sim fails like Paper does instead of answering an identity rotation.
 */
class StateEntryContactScenarioTest : SkillEngineScenario() {
    private fun load(): CompiledSkill = when (
        val r = loadInline(
            """
            apiVersion: astera/v3
            kind: Skill
            id: state-entry-contact
            item: any
            machine:
              start: throw
              states:
                throw:
                  for: 30
                  do:
                    - {id: volley, op: projectile, origin: caster, velocity: "= vec(0, 0, 1)", speed: 1.0, range: 20.0, radius: 0.2, hit: {targets: enemies, once: true, attach: false}, block: {mode: stick, for: 10}}
                  on:
                    - {event: volley.hit, to: stuck}
                stuck:
                  for: 10
                  do:
                    - {id: mark, op: frame, anchor: contact, mode: snapshot, at: 2, for: 5}
                    - {id: tag, op: display, kind: sprite, sprite: couture-s2-burst-soft, size: [1.0, 1.0], anchor: mark, at: 2, for: 5}
            """.trimIndent(),
        )
    ) {
        is Result.Success -> r.value
        is Result.Failure -> error("expected to load: ${r.error.message}")
    }

    private fun run(withContact: Boolean): Pair<GatewayRecorder, List<String>> {
        val skill = load()
        val rec = GatewayRecorder()
        rec.faithfulPose = true
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, rec.live, rec, rec.movement, rec.items, rec.world)
        player.hooks = rec.hooks
        rec.castItem = skill.def.item
        val cast = (player.start(CastRequest(skill, rec.caster, WorldId("world"), Vec3(0.0, 0.0, 0.0), 0.0, 0.0, 1L, startTick = 0), skill) as Result.Success).value
        val contact = if (withContact) ContactInfo(Vector3d(0.0, 0.0, 4.0), Vector3d(0.0, 1.0, 0.0)) else null
        for (now in 0..20) {
            rec.tick = now
            if (now == 5) player.deliver(cast, RuntimeEvent(skill.table.states.first().branches.first { it.target != null }.event, now, null, contact, null))
            player.tick(now)
        }
        return rec to player.faults().map { it.message }
    }

    @Test
    fun stateRowsSeeTheContactOfTheEventThatEnteredThem() {
        val (rec, faults) = run(withContact = true)
        assertThat(faults).isEmpty()
        assertThat(rec.calls.filter { it.verb == "spawn" }).describedAs("calls ${rec.calls}").isNotEmpty
    }

    @Test
    fun withoutAContactTheSimFaultsLikePaperInsteadOfAnIdentityRotation() {
        val (_, faults) = run(withContact = false)
        assertThat(faults).anyMatch { it.contains("no contact bound") }
    }
}
