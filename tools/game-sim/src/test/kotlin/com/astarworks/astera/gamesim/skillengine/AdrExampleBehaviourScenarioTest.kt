package com.astarworks.astera.gamesim.skillengine

import org.junit.jupiter.api.Test

/**
 * A2 (ADR-0303 section 12): each example plays through the real TablePlayer and its gateway calls equal the table's promise
 * ([expectedCalls]). Examples with events (b, f, g, j) get the bot-scripted events of [scriptedEvents]; the rest play with none.
 */
class AdrExampleBehaviourScenarioTest : SkillEngineScenario() {
    private fun check(example: String) = assertPlays(loadExample(example))

    @Test fun aCoutureRapierS2Coin1PlaysItsTable() = check("a-couture-rapier-s2-coin1")

    @Test fun bFrostShardProjectilePlaysItsTable() = check("b-frost-shard-projectile")

    @Test fun cBastionGreatswordS1SwingPlaysItsTable() = check("c-bastion-greatsword-s1-swing")

    @Test fun dSharedTimingAndLawsPlaysItsTable() = check("d-shared-timing-and-laws")

    @Test fun eCoutureRapierDefParryPartPlaysItsTable() = check("e-couture-rapier-def-parry-part")

    @Test fun fShootWithBounceAndExplosionPartsPlaysItsTable() = check("f-shoot-with-bounce-and-explosion-parts")

    @Test fun gCoutureRapierS2StatesPlaysItsTable() = check("g-couture-rapier-s2-states")

    @Test fun hSameEffectTwoNotationsPlaysItsTable() = check("h-same-effect-two-notations")

    @Test fun iEasingDisplayParticleSoundPlaysItsTable() = check("i-easing-display-particle-sound")

    @Test fun jMythicCompositionPlaysItsTable() = check("j-mythic-composition")
}
