package com.astarworks.astera.gamesim

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * SKL-14 R5: the displays a converted skill spawns match what v1 spawned for the same cast (text displays and block displays, counted
 * from the real converter output in `content/skills`). The numbers are v1 (`d41b82f1e`) dump counts; the ribbon trails (v1
 * VfxRigPlanner.trailPlans, VfxRigPlanner.kt:313-362) and the `text` elements account for most of them. Each row is a skill whose
 * v1 displays the converter now maps; a drift here means a trail, band or element knob stopped mapping 1:1.
 */
class ConvertedVfxCountsVsV1ScenarioTest {

    /** skill -> (text displays, block displays or null when only text is pinned) spawned by v1 over the vanilla-parity cast. */
    private val v1 = mapOf(
        "sigil-rifle-s1" to (32 to 41),
        "vendetta-rifle-ego" to (32 to 174),
        "orrery-s1" to (40 to 40),
        "orrery-s3" to (29 to null),
        "gossamer-katana-s1" to (59 to 12),
        "couture-rapier-s1" to (20 to 28),
        "ember-gauntlet-def" to (19 to 80),
        "verdict-hammer-s1" to (73 to 12),
    )

    @Test
    fun `converted skills spawn the text and block displays v1 spawned`() {
        val problems = ArrayList<String>()
        for ((id, want) in v1) {
            val spawns = VanillaDump.dump(id).filter { it.contains("\"e\":\"spawn\"") }
            val text = spawns.count { it.contains("\"kind\":\"text\"") }
            val block = spawns.count { it.contains("\"kind\":\"block\"") }
            if (text != want.first) problems += "$id: text displays $text, v1 ${want.first}"
            if (want.second != null && block != want.second) problems += "$id: block displays $block, v1 ${want.second}"
        }
        assertThat(problems).describedAs("display counts differ from v1").isEmpty()
    }
}
