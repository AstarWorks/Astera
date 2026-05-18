package com.astarworks.astera.stats.application.contents

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.norma.Norma
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.norma.NormaReward
import com.astarworks.astera.core.domain.norma.NormaTarget
import com.astarworks.astera.core.domain.norma.PeriodKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Migrated from `NormaLoaderServiceTest` in Wave _infra-2b (ADR-0065).
 *
 * The legacy `NormaLoaderService` (callback-driven) + the definition-storage
 * half of `InMemoryNormaService` are replaced by [NormaContents], a subclass
 * of [com.astarworks.astera.core.application.contents.YamlDynamicContents]
 * that gains atomic-swap reload semantics for free. The test surface adds
 * reload-semantics checks (Wave _infra-2 goal); the rest of the cases mirror
 * the legacy file 1:1 to keep coverage.
 */
class NormaContentsTest {

    private val killsYaml = """
        id: daily-killer-1
        display_name_key: astera.norma.daily-killer-1.name
        period: daily
        target:
          type: kills
          count: 5
        reward:
          currency_id: STAR
          currency_amount: 50
          xp: 100
    """.trimIndent()

    private val damageYaml = """
        id: weekly-bruiser
        display_name_key: astera.norma.weekly-bruiser.name
        period: weekly
        target:
          type: damage
          amount: 2500
        reward:
          currency_id: GOLD
          currency_amount: 500
          xp: 1000
    """.trimIndent()

    @Test
    fun `bootstrap loads kills norma`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("daily-killer-1.yaml"), killsYaml)

        val contents = NormaContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        val norma = contents.find(NormaId("daily-killer-1"))
        assertThat(norma).isNotNull
        requireNotNull(norma)
        assertThat(norma.id).isEqualTo(NormaId("daily-killer-1"))
        assertThat(norma.period).isEqualTo(PeriodKind.Daily)
        assertThat(norma.target).isInstanceOf(NormaTarget.Kills::class.java)
        assertThat((norma.target as NormaTarget.Kills).count).isEqualTo(5L)
        assertThat(norma.reward.currencyId).isEqualTo("STAR")
        assertThat(norma.reward.currencyAmount).isEqualTo(50L)
        assertThat(norma.reward.xp).isEqualTo(100L)
        assertThat(norma.displayKey.value).isEqualTo("astera.norma.daily-killer-1.name")
    }

    @Test
    fun `bootstrap loads damage norma with weekly period`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("weekly-bruiser.yaml"), damageYaml)

        val contents = NormaContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        val norma = contents.find(NormaId("weekly-bruiser"))
        assertThat(norma).isNotNull
        requireNotNull(norma)
        assertThat(norma.period).isEqualTo(PeriodKind.Weekly)
        assertThat(norma.target).isInstanceOf(NormaTarget.Damage::class.java)
        assertThat((norma.target as NormaTarget.Damage).amount).isEqualTo(2500L)
    }

    @Test
    fun `malformed yaml is skipped and other files still load`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), killsYaml)
        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val contents = NormaContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.all().map { it.id }).containsExactly(NormaId("daily-killer-1"))
    }

    @Test
    fun `unknown period fails the file but not the batch`(@TempDir dir: Path) {
        val bogusPeriod = """
            id: bad-period
            display_name_key: astera.norma.bad.name
            period: forever
            target:
              type: kills
              count: 1
            reward:
              xp: 10
        """.trimIndent()
        Files.writeString(dir.resolve("bad.yaml"), bogusPeriod)
        Files.writeString(dir.resolve("good.yaml"), killsYaml)

        val contents = NormaContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.all().map { it.id }).containsExactly(NormaId("daily-killer-1"))
    }

    @Test
    fun `missing directory returns zero counts`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val contents = NormaContents()

        val report = contents.bootstrap(missing)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.all()).isEmpty()
    }

    // ----- Reload semantics (Wave _infra-2b new) ---------------------------

    @Test
    fun `reload swaps to new map when all files parse`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("daily-killer-1.yaml"), killsYaml)

        val contents = NormaContents()
        contents.bootstrap(dir)
        assertThat(contents.all()).hasSize(1)

        // Replace the single norma with a different one.
        Files.delete(dir.resolve("daily-killer-1.yaml"))
        Files.writeString(dir.resolve("replacement.yaml"), killsYaml.replace("daily-killer-1", "replacement"))

        val report = contents.reload(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.find(NormaId("daily-killer-1"))).isNull()
        assertThat(contents.find(NormaId("replacement"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `reload is all-or-nothing - one bad file keeps old state intact`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("daily-killer-1.yaml"), killsYaml)

        val contents = NormaContents()
        contents.bootstrap(dir)
        assertThat(contents.find(NormaId("daily-killer-1"))).isNotNull

        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val report = contents.reload(dir)

        // failed > 0 means reload aborts; old state preserved.
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(NormaId("daily-killer-1"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `register norma adds an entry without going through YAML`() {
        val contents = NormaContents()
        val norma = Norma(
            id = NormaId("programmatic"),
            target = NormaTarget.Kills(3),
            reward = NormaReward(currencyId = "STAR", currencyAmount = 50, xp = 100),
            period = PeriodKind.Daily,
            displayKey = MessageKey("astera.norma.programmatic.name"),
        )

        contents.register(norma)

        assertThat(contents.find(NormaId("programmatic"))).isEqualTo(norma)
        assertThat(contents.all()).hasSize(1)
    }
}
