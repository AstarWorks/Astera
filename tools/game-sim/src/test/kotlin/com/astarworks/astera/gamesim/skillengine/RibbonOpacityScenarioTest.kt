package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * ADR-0303 rev4.4 / SKL-22: a ribbon's opacity is the alpha byte of its `color` (`#AARRGGBB`), the v1 trail `alpha: 0.35` is `#59......`.
 * The cast runs through the real player; the gateway must receive the rgb, the opacity, the history length, the width and the style.
 */
class RibbonOpacityScenarioTest : SkillEngineScenario() {

    @Test
    fun colourAlphaIsTheTrailOpacityLikeV1() {
        val src = listOf(
            "apiVersion: astera/v3", "kind: Skill", "id: ribbon-probe", "item: couture-rapier",
            "machine:", "  start: s", "  states:", "    s:", "      for: 20", "      do:",
            "        - {id: tip, op: projectile, origin: caster, velocity: \"= look(caster) * 1.0\", speed: 0.5, for: 10, radius: 0.4, hit: {targets: enemies, once: true}, block: {mode: despawn}}",
            "        - {id: r, op: ribbon, of: tip, history: 6, style: crisp, width: 0.3, fade: 2.0, color: \"#59ff5fa2\", for: 10}",
        ).joinToString("\n") + "\n"
        val loaded = loadInline(src)
        assertThat(loaded).`as`(loaded.toString()).isInstanceOf(Result.Success::class.java)
        val compiled = (loaded as Result.Success).value
        val rec = GatewayRecorder()
        var seen: List<Any>? = null
        var look: List<Any>? = null
        val spy = object : ISkillVisualGateway by rec.visual {
            override fun spawnTrail(cast: CastRef, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double): VisualHandle {
                seen = listOf(ticks, widthFrac, colorHex, alpha)
                return rec.visual.spawnTrail(cast, ticks, widthFrac, colorHex, alpha)
            }

            override fun setTrailLook(handle: VisualHandle, fadePower: Double, style: String) {
                look = listOf(fadePower, style)
            }
        }
        val player = playProbe(compiled, ticks = 4, rec = rec, visual = spy)
        assertThat(player.faults()).isEmpty()
        val s = seen!!
        assertThat(s[0]).isEqualTo(6)
        assertThat(s[1] as Double).isCloseTo(0.3, Offset.offset(1e-9))
        assertThat(s[2]).isEqualTo("#ff5fa2")
        assertThat(s[3] as Double).isCloseTo(0.35, Offset.offset(0.005))
        assertThat(look).containsExactly(2.0, "crisp")
    }
}
