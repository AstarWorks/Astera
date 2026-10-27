package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.config.SkillYamlConfig
import com.astarworks.astera.combat.application.config.WindupSoundYaml
import com.astarworks.astera.combat.application.config.WindupTelegraphYaml
import com.astarworks.astera.combat.application.config.WindupYaml
import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.application.config.skill.DamageComponentYaml
import com.astarworks.astera.core.application.config.skill.MechanicYaml
import com.astarworks.astera.core.application.config.skill.NumericValueYaml
import com.astarworks.astera.core.application.config.skill.PhaseMechanicsYaml
import com.astarworks.astera.core.application.config.skill.TargeterYaml
import com.astarworks.astera.core.domain.effect.Audience
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.sprite.SpriteId
import com.astarworks.astera.core.domain.effect.vfx.telegraph.TelegraphPlanner
import com.astarworks.astera.core.domain.effect.vfx.telegraph.TelegraphShape
import com.astarworks.astera.core.domain.effect.vfx.veil.ScreenVeilMath
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import kotlin.math.hypot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0290 telegraph + windup desugar and ADR-0291 screen veil, end to end through the real dispatcher and sim ports. */
class TelegraphWindupVeilScenarioTest {

    private fun ScenarioScope.dispatch(ctx: MechanicContext, vararg mechanics: Mechanic) {
        checkNotNull(sim.combat.mechanicDispatcher) { "dispatcher not wired" }.dispatch(mechanics.toList(), ctx)
    }

    private fun bgAlpha(spec: com.astarworks.astera.core.domain.effect.DisplaySpec): Int =
        ((spec.kind as DisplayKind.Text).backgroundArgb ushr 24) and 0xFF

    @Test
    fun `telegraph rim is full size on tick 0 and the fill reaches the rim at the impact tick`() {
        scenario("telegraph-circle") {
            val caster = player("caster", team = "red")
            val radius = 4.0
            val impact = 20
            dispatch(
                MechanicContext(actorId = caster.id, origin = caster.position, worldId = caster.world),
                Mechanic.Telegraph(Targeter.Caster, TelegraphShape.CIRCLE, NumericValue.Literal(radius), ticks = IntValue.Literal(impact)),
            )
            advance(impact + 2)

            val rimAlpha = (TelegraphPlanner.RIM_ALPHA * 255).toInt() + 1
            val fillAlpha = (TelegraphPlanner.FILL_ALPHA * 255).toInt()
            val all = sim.displays.spawned
            val rim = all.filter { bgAlpha(it.spec) in rimAlpha - 1..rimAlpha }
            val fill = all.filter { bgAlpha(it.spec) in fillAlpha - 1..fillAlpha }
            assertThat(rim).isNotEmpty()
            assertThat(fill).isNotEmpty()
            val t0 = all.minOf { it.tick }

            // Rim: spawned on the first tick, ring of the full radius around the caster, outside colour = the enemy warning colour.
            assertThat(rim.map { it.tick }.toSet()).containsExactly(t0)
            val ringRadii = rim.map { hypot(it.at.x - caster.position.x, it.at.z - caster.position.z) }
            assertThat(ringRadii.max()).isBetween(radius - 0.1, radius + 0.1)
            assertThat((rim.first().spec.kind as DisplayKind.Text).backgroundArgb and 0xFFFFFF).isEqualTo(TelegraphPlanner.ENEMY_WARNING_RGB)

            // Fill: starts tiny, is full-width by the impact tick (widest chord strip ~ 2 * radius; quad width = scale.x / 8).
            val startWidth = fill.maxOf { it.spec.transform.scale.x } / 8.0
            assertThat(startWidth).isLessThan(0.2)
            val widthAt = { limit: Long ->
                fill.maxOf { f ->
                    (sim.displays.updates.filter { it.handle == f.handle && it.tick <= limit }.maxByOrNull { it.tick }?.transform?.scale?.x
                        ?: f.spec.transform.scale.x) / 8.0
                }
            }
            assertThat(widthAt(t0 + impact + 1)).isGreaterThan(2 * radius * 0.95)
            assertThat(widthAt(t0 + impact / 2)).isBetween(radius * 0.5, radius * 1.6)

            // Everything is gone again after the effect.
            advance(10)
            assertThat(sim.displays.active).isEmpty()
        }
    }

    @Test
    fun `ally telegraph takes the team colour and enemy telegraph viewers reach the display gateway`() {
        scenario("telegraph-viewers") {
            val caster = player("caster", team = "red")
            dispatch(
                MechanicContext(actorId = caster.id, origin = caster.position, worldId = caster.world),
                Mechanic.Telegraph(
                    Targeter.Caster, TelegraphShape.LINE, NumericValue.Literal(6.0), ticks = IntValue.Literal(10),
                    viewers = Audience.OTHERS,
                ),
            )
            advance(1)
            val views = sim.displays.viewing.values
            assertThat(views).isNotEmpty()
            assertThat(views.map { it.audience }.toSet()).containsExactly(Audience.OTHERS)
            assertThat(views.map { it.caster }.toSet()).containsExactly(caster.id)
        }
    }

    private fun windupConfig(withSound: Boolean) = SkillYamlConfig(
        id = "windup-test",
        archetype = "sequence",
        cooldownTicks = 40,
        params = com.astarworks.astera.combat.application.config.SkillParamsYaml.SequenceParamsYaml,
        phases = PhaseMechanicsYaml.Sequence(
            cast = listOf(
                MechanicYaml.Damage(
                    targets = TargeterYaml.Target,
                    components = listOf(DamageComponentYaml(base = NumericValueYaml.Literal(5.0), attribute = "physical")),
                ),
            ),
        ),
        windup = WindupYaml(
            ticks = 12,
            sound = if (withSound) WindupSoundYaml(soundId = "windup-charge", stopAtImpact = true) else null,
            telegraph = WindupTelegraphYaml(shape = "circle", radius = 3.0),
        ),
    )

    @Test
    fun `windup desugars into cast mechanics at tick 0 and shifts the impact by the windup ticks`() {
        val spec = windupConfig(withSound = true).toSpec()
        val cast = (spec.phases as PhaseMechanics.Sequence).cast
        assertThat(cast.filterIsInstance<Mechanic.PlaySound>()).hasSize(1)
        assertThat(cast.filterIsInstance<Mechanic.StopSound>().single().delayTicks).isEqualTo(IntValue.Literal(12))
        val telegraph = cast.filterIsInstance<Mechanic.Telegraph>().single()
        assertThat(telegraph.ticks).isEqualTo(IntValue.Literal(12))
        assertThat(telegraph.delayTicks).isEqualTo(IntValue.Literal(0))
        val shifted = cast.filterIsInstance<Mechanic.Loop>().single()
        assertThat(shifted.delayTicks).isEqualTo(IntValue.Literal(12))
        assertThat(shifted.mechanics.single()).isInstanceOf(Mechanic.Damage::class.java)
        assertThat(spec.params).isEqualTo(SkillParams.SequenceParams)
    }

    @Test
    fun `windup damage lands exactly windup ticks after the cast while the telegraph shows from tick 0`() {
        scenario("windup-impact-shift") {
            val caster = player("caster", team = "red")
            val enemy = player("enemy", at = Vec3(0.0, groundY, 3.0), team = "blue")
            val full = hp(enemy)
            val cast = (windupConfig(withSound = false).toSpec().phases as PhaseMechanics.Sequence).cast
            dispatch(MechanicContext(actorId = caster.id, targetId = enemy.id, origin = caster.position, worldId = caster.world), *cast.toTypedArray())

            advance(1)
            assertThat(sim.displays.spawned).isNotEmpty()
            advance(9)
            assertThat(hp(enemy)).isEqualTo(full)
            advance(4)
            assertThat(hp(enemy)).isLessThan(full)
        }
    }

    @Test
    fun `screen veil is spawned for the caster only, clamped to the screen fraction and removed after its ticks`() {
        scenario("screen-veil") {
            val caster = player("caster", team = "red")
            val bystander = player("bystander", at = Vec3(2.0, groundY, 0.0), team = "blue")
            val distance = 0.6
            dispatch(
                MechanicContext(actorId = caster.id, origin = caster.position, worldId = caster.world),
                Mechanic.ScreenVeil(
                    Targeter.Caster, SpriteId("slash-smear"),
                    distance = NumericValue.Literal(distance),
                    scale = NumericValue.Literal(40.0), // far above the cap
                    ticks = IntValue.Literal(6),
                    maxScreenFraction = NumericValue.Literal(0.5), // above the hard maximum
                ),
            )
            val veil = sim.displays.spawned.single()
            val view = sim.displays.viewing.getValue(veil.handle)
            assertThat(view.audience).isEqualTo(Audience.CASTER)
            assertThat(view.caster).isEqualTo(caster.id)
            assertThat(sim.displays.viewing).hasSize(1) // nobody else gets a veil (bystander: ${bystander.name})

            // In front of the eye along the look direction, `distance` blocks away.
            val eye = caster.eyePosition
            assertThat(veil.at.z - eye.z).isBetween(distance - 0.01, distance + 0.01)

            // Clamped: the hard maximum (18 percent), not the requested 40x / 50 percent.
            val side = veil.spec.transform.scale.x / 5.0
            assertThat(side).isEqualTo(ScreenVeilMath.maxSide(distance, ScreenVeilMath.HARD_MAX_FRACTION))
            assertThat(ScreenVeilMath.screenFraction(side, distance)).isLessThanOrEqualTo(ScreenVeilMath.HARD_MAX_FRACTION + 1e-9)

            // Follows the camera every tick, fades, then goes.
            advance(3)
            assertThat(sim.displays.teleports.count { it.handle == veil.handle }).isGreaterThanOrEqualTo(2)
            assertThat(sim.displays.styles.filter { it.handle == veil.handle }.map { it.textOpacity }).isSortedAccordingTo(Comparator.reverseOrder())
            advance(6)
            assertThat(sim.displays.active).isEmpty()
        }
    }
}
