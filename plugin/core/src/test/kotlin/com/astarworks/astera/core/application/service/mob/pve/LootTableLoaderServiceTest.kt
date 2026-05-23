package com.astarworks.astera.core.application.service.mob.pve

import com.astarworks.astera.core.application.service.mob.DeterministicLootRollService
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LootTableLoaderServiceTest {

    @Test
    fun `bootstrap registers each loaded table with the roll service`(@TempDir tmp: Path) {
        Files.writeString(
            tmp.resolve("scorched-titan-loot.yaml"),
            """
            id: astera:scorched-titan-loot
            drops:
              - item_id: dust-of-ember
                chance: 1.0
                min_count: 5
                max_count: 10
              - item_id: scorched-heart
                chance: 0.0005
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("ember-imp-loot.yaml"),
            """
            id: astera:ember-imp-loot
            drops:
              - item_id: dust-of-ember
                chance: 1.0
            """.trimIndent(),
        )
        val eventBus = FakeDomainEventBus()
        val rollService = DeterministicLootRollService(eventBus)
        val loader = LootTableLoaderService()

        val report = loader.bootstrap(tmp, rollService)

        assertThat(report.loaded).isEqualTo(2)
        assertThat(report.failed).isZero
        assertThat(rollService.tableOf(LootTableId("astera:scorched-titan-loot"))).hasSize(2)
        assertThat(rollService.tableOf(LootTableId("astera:ember-imp-loot"))).hasSize(1)
    }

    @Test
    fun `missing directory returns zero report and no registrations`(@TempDir tmp: Path) {
        val missing = tmp.resolve("does-not-exist")
        val eventBus = FakeDomainEventBus()
        val rollService = DeterministicLootRollService(eventBus)
        val loader = LootTableLoaderService()

        val report = loader.bootstrap(missing, rollService)

        assertThat(report.loaded).isZero
        assertThat(report.failed).isZero
    }

    @Test
    fun `failed YAML counted but others register`(@TempDir tmp: Path) {
        Files.writeString(
            tmp.resolve("good.yaml"),
            """
            id: astera:good-loot
            drops:
              - item_id: gold
                chance: 1.0
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("bad.yaml"),
            """
            id: astera:bad-loot
            drops:
              - item_id: gold
                chance: 1.0
              - item_id: gold
                chance: 0.5
            """.trimIndent(),
        )
        val eventBus = FakeDomainEventBus()
        val rollService = DeterministicLootRollService(eventBus)
        val loader = LootTableLoaderService()

        val report = loader.bootstrap(tmp, rollService)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(rollService.tableOf(LootTableId("astera:good-loot"))).hasSize(1)
        assertThat(rollService.tableOf(LootTableId("astera:bad-loot"))).isEmpty()
    }
}
