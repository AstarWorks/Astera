package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.match.application.port.outbound.IMatchNormaStore
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.application.service.MatchCarriedState
import com.astarworks.astera.match.application.service.result.MatchTallyService
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.wiring.IRewireService
import com.astarworks.astera.core.application.wiring.RewireError
import com.astarworks.astera.core.application.wiring.RewireReport
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.EndReason
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
    fun `items combat and match rewire twice with equal counts and each rebuilds its dependents`() {
        scenario("rewire-core-chain") {
            val rewire = sim.services.getOrThrow<IRewireService>()
            player("alice", team = "red")
            advance(400)
            repeat(2) { round ->
                val items = rewire.ok("items")
                assertThat(items.changes.map { it.owner.id }).`as`("items round $round")
                    .containsExactly("Astera-Items", "Astera-Combat", "Astera-Match")
                items.changes.forEach { assertThat(it.after).`as`("${it.owner} counts").isEqualTo(it.before) }
                val combat = rewire.ok("combat")
                assertThat(combat.changes.map { it.owner.id }).containsExactly("Astera-Combat", "Astera-Match")
                combat.changes.forEach { assertThat(it.after).`as`("${it.owner} counts").isEqualTo(it.before) }
                val match = rewire.ok("match")
                assertThat(match.changes.map { it.owner.id }).containsExactly("Astera-Match")
                match.changes.forEach { assertThat(it.after).`as`("${it.owner} counts").isEqualTo(it.before) }
            }
            advance(20)
        }
    }

    @Test
    fun `an item cooldown is carried across an items rewire`() {
        scenario("rewire-items-cooldown") {
            val rewire = sim.services.getOrThrow<IRewireService>()
            val alice = player("alice")
            val key = CooldownKey.Custom("rewire", "probe")
            sim.services.getOrThrow<ICooldownTracker>().start(alice.id, key, 200)
            advance(20)
            rewire.ok("items")
            val tracker = sim.services.getOrThrow<ICooldownTracker>()
            assertThat(tracker.isCoolingDown(alice.id, key)).isTrue()
            assertThat(tracker.remainingTicks(alice.id, key)).isBetween(150L, 190L)
            advance(200)
            assertThat(sim.services.getOrThrow<ICooldownTracker>().isCoolingDown(alice.id, key)).isFalse()
        }
    }

    @Test
    fun `an active status is carried across a combat rewire`() {
        scenario("rewire-combat-status") {
            val rewire = sim.services.getOrThrow<IRewireService>()
            val alice = player("alice", team = "red")
            val registry = sim.services.getOrThrow<IStatusEffectRegistry>()
            sim.services.getOrThrow<IStatusEffectContainer>().apply(
                StatusEffectInstance(
                    spec = checkNotNull(registry.find(StatusEffectId("burn"))),
                    targetPlayerId = alice.id,
                    remainingTicks = 400,
                    currentStacks = 1,
                    appliedAtTick = sim.tick,
                ),
            )
            rewire.ok("combat")
            assertThat(sim.services.getOrThrow<IStatusEffectContainer>().has(alice.id, StatusEffectId("burn"))).isTrue()
        }
    }

    @Test
    fun `a running match keeps its phase teams and score across a match rewire and ends on the new generation`() {
        scenario("rewire-match-running") {
            val rewire = sim.services.getOrThrow<IRewireService>()
            val red = player("red1", team = "red")
            val blue = player("blue1", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"), stage = "siege-isles-tnt")
            advance(40)
            val before = checkNotNull(sim.services.getOrThrow<IMatchRepository>().findById(matchId))
            val lifecycleBefore = sim.services.getOrThrow<IMatchLifecycle>()

            val report = rewire.ok("match")
            assertThat(report.warnings).isEmpty()

            val after = checkNotNull(sim.services.getOrThrow<IMatchRepository>().findById(matchId))
            assertThat(after.phase).isEqualTo(before.phase)
            assertThat(after.teams).isEqualTo(before.teams)
            assertThat(after.score).isEqualTo(before.score)
            assertThat(after.startedAtTick).isEqualTo(before.startedAtTick)
            assertThat(sim.services.getOrThrow<IMatchLifecycle>()).isNotSameAs(lifecycleBefore)

            advance(40)
            val ended = sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
            assertThat(ended).isInstanceOf(Result.Success::class.java)
        }
    }

    private fun clockSlot(carry: MatchCarriedState): Map<*, *> =
        carry.slot<java.util.concurrent.ConcurrentHashMap<*, *>>("star-clock-start") { error("star clock slot missing") }

    @Test
    fun `stars norma level kill tally and the star clock keep counting across a match rewire`() {
        scenario("rewire-match-counters") {
            val rewire = sim.services.getOrThrow<IRewireService>()
            val red = player("red1", team = "red")
            val blue = player("blue1", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"), stage = "siege-isles-tnt")
            advance(40)
            val stars = sim.services.getOrThrow<IMatchStarStore>()
            val norma = sim.services.getOrThrow<IMatchNormaStore>()
            stars.add(matchId, red.id, 7)
            sim.eventBus.publish(PlayerKilled(blue.id, red.id, DamageSource.Environment.Generic, 1L))
            val carry = sim.services.getOrThrow<MatchCarriedState>()
            val clockBefore = clockSlot(carry).toMap()
            val levelBefore = norma.get(matchId, red.id).level

            rewire.ok("match")

            assertThat(sim.services.getOrThrow<MatchCarriedState>()).isSameAs(carry)
            assertThat(sim.services.getOrThrow<IMatchStarStore>().get(matchId, red.id)).isEqualTo(7)
            assertThat(sim.services.getOrThrow<IMatchNormaStore>().get(matchId, red.id).level).isEqualTo(levelBefore)
            assertThat(clockSlot(carry).toMap())
                .isEqualTo(clockBefore)

            // Progress continues on the new generation: a second kill adds to the first one's tally.
            sim.eventBus.publish(PlayerKilled(blue.id, red.id, DamageSource.Environment.Generic, 2L))
            advance(20)
            val counts = sim.services.getOrThrow<MatchTallyService>().take(matchId)
            assertThat(counts[red.id]?.kills).isEqualTo(2)
        }
    }

    @Test
    fun `an economy rewire in a running match still lets the match end cleanly`() {
        scenario("rewire-economy-mid-match") {
            val rewire = sim.services.getOrThrow<IRewireService>()
            val red = player("red1", team = "red")
            val blue = player("blue1", team = "blue")
            val matchId = startMatch("carry-tnt", mapOf(red to "red", blue to "blue"), stage = "siege-isles-tnt")
            advance(40)
            rewire.ok("economy")
            advance(20)
            val ended = sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.AdminEnded)
            assertThat(ended).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `an unknown plugin name is refused`() {
        scenario("rewire-refused") {
            val rewire = sim.services.getOrThrow<IRewireService>()
            val nope = rewire.rewire("nope")
            assertThat((nope as Result.Failure).error).isInstanceOf(RewireError.UnknownPlugin::class.java)
        }
    }
}
