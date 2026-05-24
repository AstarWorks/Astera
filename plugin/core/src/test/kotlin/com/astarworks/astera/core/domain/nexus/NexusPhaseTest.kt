package com.astarworks.astera.core.domain.nexus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0108 Wave 5annihilation — sanity for the [NexusPhase] sealed hierarchy. */
class NexusPhaseTest {

    @Test
    fun `ordered returns canonical 1 to 4 progression`() {
        assertThat(NexusPhase.ordered()).containsExactly(
            NexusPhase.Phase1Build,
            NexusPhase.Phase2Engage,
            NexusPhase.Phase3Tier3,
            NexusPhase.Phase4SuddenDeath,
        )
    }

    @Test
    fun `byId round-trips every shipped phase`() {
        NexusPhase.ordered().forEach { phase ->
            assertThat(NexusPhase.byId(phase.id)).isEqualTo(phase)
        }
    }

    @Test
    fun `byId returns null for unknown id`() {
        assertThat(NexusPhase.byId("unknown")).isNull()
    }

    @Test
    fun `next is linear forward and terminal at SuddenDeath`() {
        assertThat(NexusPhase.next(NexusPhase.Phase1Build)).isEqualTo(NexusPhase.Phase2Engage)
        assertThat(NexusPhase.next(NexusPhase.Phase2Engage)).isEqualTo(NexusPhase.Phase3Tier3)
        assertThat(NexusPhase.next(NexusPhase.Phase3Tier3)).isEqualTo(NexusPhase.Phase4SuddenDeath)
        assertThat(NexusPhase.next(NexusPhase.Phase4SuddenDeath)).isNull()
    }

    @Test
    fun `phase ids are stable strings`() {
        assertThat(NexusPhase.Phase1Build.id).isEqualTo("build")
        assertThat(NexusPhase.Phase2Engage.id).isEqualTo("engage")
        assertThat(NexusPhase.Phase3Tier3.id).isEqualTo("tier3")
        assertThat(NexusPhase.Phase4SuddenDeath.id).isEqualTo("sudden-death")
    }
}
