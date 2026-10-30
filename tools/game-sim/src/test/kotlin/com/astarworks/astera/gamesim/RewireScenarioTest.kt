package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.wiring.IRewireService
import com.astarworks.astera.core.application.wiring.RewireError
import com.astarworks.astera.core.application.wiring.RewireReport
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0311 P1: `astera admin rewire <plugin>` rebuilds a leaf plugin's generation in place, repeatedly, without leaking or losing state. */
class RewireScenarioTest {

    private fun IRewireService.ok(name: String): RewireReport {
        val result = rewire(name)
        check(result is Result.Success) { "rewire $name failed: ${(result as Result.Failure).error}" }
        return result.value
    }

    @Test
    fun `economy and stats rewire twice with equal counts, carried state and a single live handler set`() {
        scenario("rewire-leaf-twice") {
            val rewire = sim.services.getOrThrow<IRewireService>()
            val alice = player("alice")
            // Let the join's one-shot follow-ups run: a pending one-shot is a legitimate transient task of the old generation.
            advance(400)
            val starter = sim.services.getOrThrow<ICurrencyLedger>().balance(alice.id, Currency.GOLD)
            sim.services.getOrThrow<ICurrencyLedger>().credit(alice.id, Currency.GOLD, 77L)
            val balance = starter + 77L
            // A reference newcomer on generation 0: what one join grants with a single live handler set.
            val carol = player("carol")
            advance(400)
            val newcomerGrant = sim.services.getOrThrow<ICurrencyLedger>().balance(carol.id, Currency.GOLD)

            repeat(2) { round ->
                val report = rewire.ok("economy")
                assertThat(report.warnings).`as`("warnings round $round").isEmpty()
                assertThat(report.changes).`as`("economy only (nothing depends on it)").hasSize(1)
                for (change in report.changes) {
                    assertThat(change.after).`as`("${change.owner} counts round $round").isEqualTo(change.before)
                    assertThat(change.toGen).isEqualTo(change.fromGen + 1)
                }
                // The new generation serves the carried wallet.
                assertThat(sim.services.getOrThrow<ICurrencyLedger>().balance(alice.id, Currency.GOLD)).isEqualTo(balance)
            }

            // Stats has Economy as a dependent: both rebuild, in order, with equal counts.
            val statsReport = rewire.ok("stats")
            assertThat(statsReport.changes.map { it.owner.id }).containsExactly("Astera-Stats", "Astera-Economy")
            statsReport.changes.forEach { assertThat(it.after).`as`("${it.owner} counts").isEqualTo(it.before) }
            assertThat(sim.services.getOrThrow<ICurrencyLedger>().balance(alice.id, Currency.GOLD)).isEqualTo(balance)

            // Old generations' join handlers are gone: a newcomer gets exactly what the generation-0 newcomer got.
            val bob = player("bob")
            advance(400)
            assertThat(sim.services.getOrThrow<ICurrencyLedger>().balance(bob.id, Currency.GOLD)).isEqualTo(newcomerGrant)

            // Nothing leaked: the registry holds no late overrides and the status lines show generations.
            assertThat(rewire.status().joinToString("\n")).contains("Astera-Economy gen 3").contains("Astera-Stats gen 1")
        }
    }

    @Test
    fun `a plugin without a lifecycle or an unknown name is refused`() {
        scenario("rewire-refused") {
            val rewire = sim.services.getOrThrow<IRewireService>()
            val combat = rewire.rewire("combat")
            assertThat((combat as Result.Failure).error).isInstanceOf(RewireError.RestartRequired::class.java)
            assertThat(combat.error.code).isEqualTo("RESTART_REQUIRED")
            val nope = rewire.rewire("nope")
            assertThat((nope as Result.Failure).error).isInstanceOf(RewireError.UnknownPlugin::class.java)
        }
    }
}
