package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.domain.norma.Norma
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.norma.NormaTarget
import com.astarworks.astera.core.domain.norma.PeriodKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end test for [NormaLoaderService] (Wave 2 R1.a).
 *
 * Covers: happy-path kills + damage target variants, malformed-file isolation,
 * unknown-period rejection, and missing-directory soft fallback.
 */
class NormaLoaderServiceTest {

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

    private fun collector(): Pair<MutableList<Norma>, (Norma) -> Unit> {
        val sink = mutableListOf<Norma>()
        return sink to { norma: Norma -> sink += norma }
    }

    @Test
    fun `happy path loads kills norma`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("daily-killer-1.yaml"), killsYaml)
        val (sink, register) = collector()

        val report = NormaLoaderService(register).loadFrom(dir)

        assertThat(report).isEqualTo(NormaLoaderService.LoadReport(loaded = 1, failed = 0))
        assertThat(sink).hasSize(1)
        val norma = sink.single()
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
    fun `happy path loads damage norma with weekly period`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("weekly-bruiser.yaml"), damageYaml)
        val (sink, register) = collector()

        val report = NormaLoaderService(register).loadFrom(dir)

        assertThat(report).isEqualTo(NormaLoaderService.LoadReport(loaded = 1, failed = 0))
        val norma = sink.single()
        assertThat(norma.period).isEqualTo(PeriodKind.Weekly)
        assertThat(norma.target).isInstanceOf(NormaTarget.Damage::class.java)
        assertThat((norma.target as NormaTarget.Damage).amount).isEqualTo(2500L)
    }

    @Test
    fun `malformed yaml is skipped and other files still load`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), killsYaml)
        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")
        val (sink, register) = collector()

        val report = NormaLoaderService(register).loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(sink.map { it.id }).containsExactly(NormaId("daily-killer-1"))
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
        val (sink, register) = collector()

        val report = NormaLoaderService(register).loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(sink.map { it.id }).containsExactly(NormaId("daily-killer-1"))
    }

    @Test
    fun `missing directory returns zero counts`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val (sink, register) = collector()

        val report = NormaLoaderService(register).loadFrom(missing)

        assertThat(report).isEqualTo(NormaLoaderService.LoadReport(loaded = 0, failed = 0))
        assertThat(sink).isEmpty()
    }
}
