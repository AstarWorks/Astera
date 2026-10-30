package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.EventBinding
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.expr.ExprEvalException
import com.astarworks.astera.core.domain.skillengine.table.EntityRef
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.gamesim.skillengine.SkillEngineScenario
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * `velocity(victim)` / `velocity(target)` read the hit entity's real velocity through the sim's live-value source (the twin of Paper's), never a
 * silent zero: a moving victim answers non-zero, a standing one zero, an unbound victim and a frame that has no live velocity fail loudly.
 * Read through the real wiring (a sim with a caster and a victim) with the shipped couture-rapier-s2 (the one skill that reads `velocity(victim)`).
 */
class VictimVelocityScenarioTest : SkillEngineScenario() {
    @Test
    fun `a moving victim reads non-zero velocity, a standing one zero, anything unresolvable fails loudly`() {
        val text = Files.readString(contentDir.resolve("skills/couture-rapier-s2/skill.yaml"))
        val skill = when (val r = compileText("couture-rapier-s2", text)) {
            is Result.Success -> r.value
            is Result.Failure -> error("couture-rapier-s2 must load: ${r.error.message}")
        }
        scenario("victim-velocity", AnnihilationConfig(buildPhaseTicks = 200, engagePhaseTicks = 100_000, tier3PhaseTicks = 100_000)) {
            val red = player("red-1", team = "red")
            val victim = mob("dummy", Vec3(0.0, groundY, 4.0), team = "blue")
            val cast = CastHandle(9_000_001L)
            val live = sim.skillEngine.live
            live.register(cast, CastRequest(skill, red.id.uuid, red.world, red.position, 0.0, 0.0, 1L, startTick = 0))
            val bound = EventBinding(EventPath("hit"), null, null, EntityRef(victim.id.uuid))

            victim.velocity = Vec3(0.2, 0.0, 0.5)
            val frames = live.frames(cast, bound)
            assertThat(frames.velocity("victim", null)).describedAs("moving victim").isEqualTo(Vec3(0.2, 0.0, 0.5))
            assertThat(frames.velocity("target", null)).describedAs("target is the victim").isEqualTo(Vec3(0.2, 0.0, 0.5))

            victim.velocity = Vec3.ZERO
            assertThat(live.frames(cast, bound).velocity("victim", null)).describedAs("standing victim").isEqualTo(Vec3.ZERO)

            assertThatThrownBy { live.frames(cast, null).velocity("victim", null) }.isInstanceOf(ExprEvalException::class.java)
            assertThatThrownBy { frames.velocity("nonsense", null) }.isInstanceOf(ExprEvalException::class.java)
            assertThatThrownBy { frames.velocity("caster", "hip") }.isInstanceOf(ExprEvalException::class.java)
        }
    }
}
