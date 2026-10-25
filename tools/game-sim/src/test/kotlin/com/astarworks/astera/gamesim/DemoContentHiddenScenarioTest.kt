package com.astarworks.astera.gamesim

import com.astarworks.astera.items.application.admin.AdminItemCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** MODE-08 (ADR-0270): dev / demo items stay usable by id but are not listed, kitted or racked. */
class DemoContentHiddenScenarioTest {

    @Test
    fun `demo items are in the registry but in no admin catalog or kit`() {
        scenario("demo-hidden") {
            val all = sim.items.itemRegistry.all()
            assertThat(all.map { it.id.value }).contains("rtm-blaster", "skill-tester")
            val listed = AdminItemCatalog.entries(all, null).map { it.id.value }
            assertThat(listed).isNotEmpty.doesNotContain("rtm-blaster", "skill-tester")
            assertThat(AdminItemCatalog.kit(all).map { it.id.value }).doesNotContain("rtm-blaster")
        }
    }
}
