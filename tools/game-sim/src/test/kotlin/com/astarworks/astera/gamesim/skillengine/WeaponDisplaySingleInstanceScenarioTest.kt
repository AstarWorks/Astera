package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.PatchMeta
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * One weapon per cast (founder 10/31: the bastion greatsword showed twice). The weapon of a cast is the skill's main-hand rig mesh; the
 * caster's body puppet (`body` op -> skin rig) draws no main-hand stack of its own (`playBodyClip` -> `ISkinRigAnimator.play(heldItem = false)`
 * in `PaperSkillGameplayGateway`, the Paper-side half of the contract). This pins the engine half: at every tick of the cast the number
 * of live main-hand mesh displays is at most 1, and the skill draws one at all.
 */
class WeaponDisplaySingleInstanceScenarioTest : SkillEngineScenario() {

    private val skills = listOf("bastion-greatsword-s1", "bastion-greatsword-s2", "bastion-greatsword-def", "bastion-greatsword-ego", "greatsword-cleave")

    /** The skills whose weapon is the main-hand rig (+ a body puppet clip); def draws no weapon, ego builds its blade from blocks under a pose clip that hides the hand. */
    private val rigged = setOf("bastion-greatsword-s1", "bastion-greatsword-s2", "greatsword-cleave")

    @Test
    fun atMostOneLiveWeaponDisplayAtEveryTick() {
        val composed = (SkillComposer(contentDir.resolve("skills")).composeAll() as? Result.Success)?.value ?: throw AssertionError("compose failed")
        val ids = composed.keys.toSet()
        val defs = LinkedHashMap<String, SkillDef>()
        for ((id, c) in composed) if (c.patch == PatchMeta.BASE) defs[id] = (decodeTree(id, c.tree, ids) as? Result.Success)?.value ?: throw AssertionError("decode $id")
        for (id in skills) {
            val skill = (compileAgainst(defs.getValue(id), defs) as? Result.Success)?.value ?: throw AssertionError("$id did not compile")
            val calls = play(skill, 7L, scriptedEvents(skill), null, GatewayRecorder())
            val weapons = HashSet<String>()
            var drawn = false
            for (tick in 0..(calls.maxOfOrNull { it.tick } ?: 0)) {
                for (c in calls.filter { it.tick == tick }) {
                    if (c.verb == "spawn" && c.payload.contains("kind=item") && c.detail.contains("item=main_hand")) weapons += c.instance
                    if (c.verb == "remove") weapons -= c.instance
                }
                if (weapons.isNotEmpty()) drawn = true
                assertThat(weapons.size).describedAs("$id tick $tick live main-hand weapon displays: $weapons").isLessThanOrEqualTo(1)
            }
            if (id in rigged) assertThat(drawn).describedAs("$id draws its weapon rig").isTrue()
        }
    }
}
