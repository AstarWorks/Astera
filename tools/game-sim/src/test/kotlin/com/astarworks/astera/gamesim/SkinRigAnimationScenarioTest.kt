package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.animation.ISkinRigAnimator
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigError
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0250: a `content/animations/` clip runs the skin-rig timeline end to end (headless animator). The v2 `caster_anim` op that starts it from a skill is covered by CasterAnimScenarioTest. */
class SkinRigAnimationScenarioTest {

    @Test
    fun `the sword swing clip plays and the rig ends on its own timeline`() {
        scenario("anim-demo-sword-swing") {
            val caster = player("caster", team = "red")
            val animator = sim.services.getOrThrow<ISkinRigAnimator>()

            assertThat(animator.play(caster.id, CustomAnimationId("sword-swing"))).isEqualTo(Result.success(Unit))
            assertThat(animator.active(caster.id)?.value).isEqualTo("sword-swing")

            // 12 ticks of motion + 3 ticks of blend-out, then the real player is back.
            advance(5)
            assertThat(animator.active(caster.id)).isNotNull()
            assertThat(advanceUntil(30) { animator.active(caster.id) == null }).isTrue()
        }
    }

    @Test
    fun `a rig animation can be stopped early and an unknown id is refused`() {
        scenario("anim-stop") {
            val caster = player("caster", team = "red")
            val animator = sim.services.getOrThrow<ISkinRigAnimator>()

            assertThat(animator.play(caster.id, CustomAnimationId("wave"))).isEqualTo(Result.success(Unit))
            assertThat(animator.stop(caster.id)).isEqualTo(Result.success(Unit))
            // blend_out_ticks = 3: the stop takes effect over the next three ticks.
            assertThat(advanceUntil(10) { animator.active(caster.id) == null }).isTrue()

            val unknown = CustomAnimationId("does-not-exist")
            assertThat(animator.play(caster.id, unknown)).isEqualTo(Result.failure(SkinRigError.UnknownAnimation(unknown)))
            assertThat(animator.stop(caster.id)).isEqualTo(Result.failure(SkinRigError.NotPlaying(caster.id)))
        }
    }
}
