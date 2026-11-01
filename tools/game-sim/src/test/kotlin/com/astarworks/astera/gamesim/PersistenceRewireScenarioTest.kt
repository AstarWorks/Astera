package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.application.wiring.IRewireService
import com.astarworks.astera.core.application.wiring.RewireError
import com.astarworks.astera.core.application.wiring.RewireReport
import com.astarworks.astera.core.application.wiring.WiringTag
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0311 P3: Persistence is a rewirable lifecycle. Its overlays are a generation of their own, a feature plugin
 * rewired next to it never sees an in-memory default, `--reconnect` reaches the lifecycle, and the registry's guard is
 * generation-aware. The stand-in overlay (`SimPersistenceLifecycle`) replaces Postgres, which game-sim does not have.
 */
class PersistenceRewireScenarioTest {

    private fun IRewireService.ok(name: String, reconnect: Boolean = false): RewireReport {
        val result = rewire(name, reconnect)
        check(result is Result.Success) { "rewire $name failed: ${(result as Result.Failure).error}" }
        return result.value
    }

    @Test
    fun `a feature rewire keeps resolving the persistence overlay and never an in-memory default`() {
        scenario("rewire-feature-with-overlay", persistenceOverlay = true) {
            val rewire = sim.services.getOrThrow<IRewireService>()
            val alice = player("alice")
            advance(400)
            val overlay = checkNotNull(sim.persistenceOverlay)
            sim.services.getOrThrow<ICurrencyLedger>().credit(alice.id, Currency.GOLD, 77L)
            val balance = overlay.ledger.balance(alice.id, Currency.GOLD)

            repeat(2) {
                val report = rewire.ok("economy")
                assertThat(report.warnings).isEmpty()
                assertThat(sim.services.getOrThrow<ICurrencyLedger>()).isSameAs(overlay.ledger)
                assertThat(overlay.ledger.balance(alice.id, Currency.GOLD)).isEqualTo(balance)
            }
            val stats = rewire.ok("stats")
            assertThat(stats.changes.map { it.owner.id }).containsExactly("Astera-Stats", "Astera-Economy")
            assertThat(sim.services.getOrThrow<ICurrencyLedger>()).isSameAs(overlay.ledger)
            assertThat(sim.persistenceOverlay).isSameAs(overlay)

            assertThat(sim.services.lateOverrides).isEmpty()
            assertThat(sim.services.staleRejections).isEmpty()
        }
    }

    @Test
    fun `rewiring persistence swaps the overlay generation and rebuilds its consumers with the data intact`() {
        scenario("rewire-persistence-overlay", persistenceOverlay = true) {
            val rewire = sim.services.getOrThrow<IRewireService>()
            val alice = player("alice")
            advance(400)
            val gen0 = checkNotNull(sim.persistenceOverlay)
            sim.services.getOrThrow<ICurrencyLedger>().credit(alice.id, Currency.GOLD, 77L)
            val balance = gen0.ledger.balance(alice.id, Currency.GOLD)
            val carol = player("carol")
            advance(400)
            val newcomerGrant = gen0.ledger.balance(carol.id, Currency.GOLD)

            repeat(2) { round ->
                val report = rewire.ok("persistence")
                assertThat(report.changes.map { it.owner.id }).`as`("round $round")
                    .containsExactly("Astera-Persistence", "Astera-Items", "Astera-Combat", "Astera-Match", "Astera-Stats", "Astera-Economy")
                assertThat(report.warnings).isEmpty()
                report.changes.forEach { assertThat(it.after).`as`("${it.owner} counts").isEqualTo(it.before) }
            }

            val gen2 = checkNotNull(sim.persistenceOverlay)
            assertThat(gen2).isNotSameAs(gen0)
            assertThat(gen2.ledger).isNotSameAs(gen0.ledger)
            // Consumers resolve the NEW generation, over the same data.
            assertThat(sim.services.getOrThrow<ICurrencyLedger>()).isSameAs(gen2.ledger)
            assertThat(gen2.ledger.balance(alice.id, Currency.GOLD)).isEqualTo(balance)
            assertThat(sim.services.getOrThrow<ICurrencyLedger>().balance(alice.id, Currency.GOLD)).isEqualTo(balance)

            // Old generations' handlers are gone: one join grants exactly what generation 0 granted.
            val bob = player("bob")
            advance(400)
            assertThat(gen2.ledger.balance(bob.id, Currency.GOLD)).isEqualTo(newcomerGrant)

            assertThat(rewire.status().joinToString("\n")).contains("Astera-Persistence gen 2")
            assertThat(sim.services.lateOverrides).isEmpty()
            assertThat(sim.services.staleRejections).isEmpty()
        }
    }

    @Test
    fun `reconnect reaches the persistence lifecycle, a failed one changes nothing and other plugins refuse it`() {
        scenario("rewire-persistence-reconnect", persistenceOverlay = true) {
            val rewire = sim.services.getOrThrow<IRewireService>()
            val lifecycle = checkNotNull(sim.persistenceLifecycle)
            val alice = player("alice")
            advance(400)

            val report = rewire.ok("persistence", reconnect = true)
            assertThat(lifecycle.reconnects).isEqualTo(1)
            assertThat(report.changes.first().owner.id).isEqualTo("Astera-Persistence")

            val before = rewire.status()
            lifecycle.failNextReconnect = true
            val failed = rewire.rewire("persistence", reconnect = true)
            assertThat(failed).isInstanceOf(Result.Failure::class.java)
            assertThat((failed as Result.Failure).error).isInstanceOf(RewireError.ReconnectFailed::class.java)
            assertThat(failed.error.code).isEqualTo("RECONNECT_FAILED")
            assertThat(rewire.status()).`as`("a refused reconnect leaves every generation as it was").isEqualTo(before)
            assertThat(lifecycle.reconnects).isEqualTo(1)

            val refused = rewire.rewire("economy", reconnect = true)
            assertThat((refused as Result.Failure).error.code).isEqualTo("RECONNECT_FAILED")
            assertThat(rewire.status()).isEqualTo(before)
            assertThat(sim.services.getOrThrow<ICurrencyLedger>().balance(alice.id, Currency.GOLD)).isGreaterThanOrEqualTo(0L)
        }
    }

    @Test
    fun `the registry guard refuses a retired generation and forgives an overlay inside a rewire window only`() {
        scenario("rewire-registry-guard", persistenceOverlay = true) {
            val rewire = sim.services.getOrThrow<IRewireService>()
            player("alice")
            advance(20)
            rewire.ok("persistence")
            val current = sim.services.getOrThrow<ICurrencyLedger>()

            // A callback of the retired generation 0 registers late: refused, the live winner is untouched.
            val retired = sim.services.scoped(WiringTag(SimPersistenceLifecycle.OWNER, 0))
            val registration = retired.register<ICurrencyLedger>(current, 100)
            assertThat(registration.isActive).isFalse()
            assertThat(sim.services.staleRejections.map { it.type }).containsExactly(ICurrencyLedger::class)
            assertThat(sim.services.getOrThrow<ICurrencyLedger>()).isSameAs(current)

            // A higher-priority overlay inside a rewire window is not a split brain (its consumers are rebuilt) ...
            val owner = sim.services.scoped(WiringTag(SimPersistenceLifecycle.OWNER, 5))
            sim.services.rewireWindow { owner.register<ICurrencyLedger>(current, 500) }
            assertThat(sim.services.lateOverrides).isEmpty()
            // ... the same registration outside one is.
            sim.services.getOrThrow<ICurrencyLedger>()
            owner.register<ICurrencyLedger>(current, 900)
            assertThat(sim.services.lateOverrides).hasSize(1)
        }
    }
}
