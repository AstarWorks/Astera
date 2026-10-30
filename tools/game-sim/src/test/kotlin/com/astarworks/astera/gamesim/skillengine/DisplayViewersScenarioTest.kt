package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.Audience
import com.astarworks.astera.core.domain.effect.AudienceResolver
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The per-row `viewers: all | owner-hidden | others-only` filter of display rows: the caster is not shown an owner-hidden row but another
 * player is (others-only likewise), a frame's filter is inherited by the displays riding it, an unknown value is a strict decode error,
 * and the rows hidden from the caster are skipped by [CasterEyeClutter].
 */
class DisplayViewersScenarioTest : SkillEngineScenario() {

    private fun yaml(vararg ops: String): String = (
        listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: viewers-probe", "item: couture-rapier",
            "machine:", "  start: s", "  states:", "    s:", "      for: 10", "      enter:",
        ) + ops.map { "        - $it" }
        ).joinToString("\n") + "\n"

    private val ops = arrayOf(
        "{id: everyone, op: display, kind: block, block: \"minecraft:white_concrete\", anchor: caster, at: 0, for: 5}",
        "{id: hidden, op: display, kind: block, block: \"minecraft:white_concrete\", anchor: caster, viewers: owner-hidden, at: 0, for: 5}",
        "{id: others, op: display, kind: block, block: \"minecraft:white_concrete\", anchor: caster, viewers: others-only, at: 0, for: 5}",
        "{id: hf, op: frame, anchor: caster, viewers: owner-hidden, at: 0, for: 5}",
        "{id: child, op: display, kind: block, block: \"minecraft:white_concrete\", anchor: hf, at: 0, for: 5}",
        "{id: own, op: display, kind: block, block: \"minecraft:white_concrete\", anchor: hf, viewers: others-only, at: 0, for: 5}",
    )

    private fun spawns(): Map<String, String> {
        val skill = when (val r = loadInline(yaml(*ops))) {
            is Result.Success -> r.value
            is Result.Failure -> throw AssertionError("did not load: ${r.error}")
        }
        val rec = GatewayRecorder()
        val calls = play(skill, 1L, emptyList(), null, rec)
        // displays spawn in row order at tick 0 (the frame spawns no gateway display)
        val names = listOf("everyone", "hidden", "others", "child", "own")
        val spawned = calls.filter { it.verb == "spawn" }
        assertThat(spawned).hasSize(names.size)
        return names.zip(spawned.map { it.detail }).toMap()
    }

    private fun audienceOf(detail: String): Audience = Audience.valueOf(Regex("aud=(\\w+)").find(detail)!!.groupValues[1])

    @Test
    fun hiddenRowsAreShownToOthersNotTheCaster() {
        val caster = PlayerId(UUID(0L, 1L))
        val other = PlayerId(UUID(0L, 2L))
        val pool = listOf(caster, other)
        val s = spawns()
        fun shown(op: String): Set<PlayerId> = AudienceResolver.select(pool, audienceOf(s.getValue(op)), caster, null)

        assertThat(shown("everyone")).containsExactlyInAnyOrder(caster, other)
        assertThat(shown("hidden")).containsExactly(other)
        assertThat(shown("others")).containsExactly(other)
        // the child of a hidden frame inherits the filter; a child with its own non-all value keeps its own
        assertThat(shown("child")).containsExactly(other)
        assertThat(shown("own")).containsExactly(other)
        val child = s.getValue("child")
        assertThat(child).contains("viewers=owner-hidden")
        assertThat(s.getValue("own")).contains("viewers=others-only")
        assertThat(s.getValue("everyone")).doesNotContain("viewers=")
    }

    @Test
    fun unknownViewersValueIsAStrictDecodeError() {
        val bad = loadInline(yaml("{id: x, op: display, kind: block, block: \"minecraft:white_concrete\", anchor: caster, viewers: owner, at: 0, for: 5}"))
        assertThat(bad).isInstanceOf(Result.Failure::class.java)
        val badFrame = loadInline(yaml("{id: x, op: frame, anchor: caster, viewers: Owner-Hidden, at: 0, for: 5}"))
        assertThat(badFrame).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun casterEyeClutterSkipsRowsTheCasterCannotSee() {
        fun lines(viewers: String) = listOf("## yaw=0 pitch=0") + (0..6).flatMap { t ->
            listOfNotNull(
                if (t == 0) "@0 spawn #0 op=x kind=block pos=0.0,1.62,1.0 scale=1.5,1.5,0.1$viewers" else null,
                "@$t write #0 pos=0.0,1.62,1.0 scale=1.5,1.5,0.1",
            )
        }
        assertThat(CasterEyeClutter.checkDisplays("k", lines(""))).isNotEmpty()
        assertThat(CasterEyeClutter.checkDisplays("k", lines(" viewers=owner-hidden"))).isEmpty()
        assertThat(CasterEyeClutter.checkDisplays("k", lines(" viewers=others-only"))).isEmpty()
    }
}
