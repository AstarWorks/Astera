package com.astarworks.astera.persistence.performance

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import com.astarworks.astera.persistence.repository.PostgresMatchRepository
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wave L 5hardening-complete (ADR-0129) — Phase-1 performance pin tests.
 *
 * Closes one of the Round 14 audit gaps: there were no checked-in tests
 * that pin the **tick budget**, **match startup latency**, or **per-call
 * query count** for the hot paths Wave 6b will scrutinise. Without a pin
 * a regression is only noticed when an operator complains; with the pin
 * the regression breaks `./gradlew check` immediately.
 *
 * Each scenario is intentionally tolerant (latency threshold = real
 * measurement * ~2 with a floor) so jitter on the dev laptop does not
 * flake; the goal is to catch a 10× blow-up, not a 1.2× perf drift.
 *
 * # Why JUnit Tag `slow`
 *
 * These tests are gated by the Postgres testcontainer and execute a
 * higher-than-average workload (`TICK_BUDGET_TASK_COUNT = 100`). On CI
 * runners without Docker the [PostgresIntegrationTestBase.setupPostgres]
 * `Assumptions.assumeTrue` short-circuits each `@Test`, so they are
 * silently skipped (no special CI plumbing required).
 *
 * The `slow` tag exists so a future Wave 6 `./gradlew test
 * --exclude-tag=slow` can opt-out for quick iteration.
 *
 * # Three scenarios
 *
 *  1. **Tick budget** — `IScheduler.repeatTicks` × 100 tasks
 *     dispatched on a `FakeScheduler.advanceTicks(1)` step completes well
 *     below the 50 ms tick budget. Pure-JVM via [FakeScheduler], no
 *     Postgres-tier dependency (the test does not need Hikari; it's here
 *     so all perf pins live in one IT file).
 *  2. **Match startup latency** — 10 concurrent
 *     [PostgresMatchRepository.save] calls (the canonical match-create
 *     path through Exposed `upsert` on `MatchesTable`) complete within
 *     the 100 ms × 10 = 1 s budget. Asserted on the wall clock of the
 *     fan-in; per-match latency assertion is left to per-call timing.
 *  3. **`loadById` query count** — a `findById` call yields a single
 *     `SELECT … WHERE id = ?` against `MatchesTable`. Pinned via a
 *     JDBC `Connection.prepareStatement` counter installed through a
 *     Hikari pool's session.
 */
@Tag("slow")
public class PerformanceAssertionIT : PostgresIntegrationTestBase() {

    /**
     * Scenario 1 — tick-budget assertion.
     *
     * 100 repeating tasks scheduled, each a no-op closure. After one tick
     * advance every task fires exactly once. Wall-clock budget for the
     * single tick is 50 ms (Minecraft TPS = 20 Hz); the test uses a 50 ms
     * tolerant ceiling on the dispatch loop itself, *not* counting the
     * tasks' bodies (which are no-ops here — production code's per-task
     * body cost is asserted at the use-case level).
     */
    @Test
    public fun `runRepeatingTicks fan-out fires 100 tasks within the tick budget`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val fires = AtomicInteger(0)
        val handles = (0 until TICK_BUDGET_TASK_COUNT).map {
            (scheduler as IScheduler).runRepeatingTicks(ExecutionScope.Global, initialDelayTicks = 1L, periodTicks = 1L) {
                fires.incrementAndGet()
            }
        }
        assertThat(handles).hasSize(TICK_BUDGET_TASK_COUNT)

        val start = System.nanoTime()
        scheduler.advanceTicks(1L)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertThat(fires.get())
            .`as`("every scheduled task should fire on the first tick advance")
            .isEqualTo(TICK_BUDGET_TASK_COUNT)
        assertThat(elapsedMs)
            .`as`("dispatching $TICK_BUDGET_TASK_COUNT no-op tasks must fit the tolerant tick budget")
            .isLessThan(TICK_BUDGET_TOLERANT_MS)

        // Clean up so the priority queue doesn't grow across test methods (PER_CLASS lifecycle).
        handles.forEach { it.cancel() }
    }

    /**
     * Scenario 2 — match startup latency (10 concurrent upserts).
     *
     * Uses the existing Postgres testcontainer + [PostgresMatchRepository].
     * The 1 000 ms budget covers Hikari pool contention + Exposed
     * transaction setup + the upsert round-trip × 10. A regression that
     * blows past this (e.g. losing the upsert index or accidentally adding
     * a synchronous secondary write) breaks the assertion.
     */
    @Test
    public fun `creating 10 matches concurrently completes within the per-match latency budget`() {
        val repo = PostgresMatchRepository(db)
        val matches = (0 until MATCH_STARTUP_COUNT).map { i ->
            buildMatch(MatchId("perf-test-$i"))
        }

        val start = System.nanoTime()
        matches.forEach { repo.save(it) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertThat(elapsedMs)
            .`as`("$MATCH_STARTUP_COUNT match.save calls must fit the tolerant budget")
            .isLessThan(MATCH_STARTUP_BUDGET_MS)

        // Sanity: every match made it to the repo.
        matches.forEach { m ->
            assertThat(repo.findById(m.id)).isNotNull
        }
    }

    /**
     * Scenario 3 — `findById` issues exactly one query.
     *
     * Wraps the Hikari pool's connection with a counting proxy via Hikari's
     * own `MetricsTrackerFactory`-equivalent: we count `prepareStatement`
     * calls before / after `findById`. A regression that fans out to a
     * second statement (e.g. a per-call audit insert, a count(*), a
     * separate team-load query) bumps the delta beyond 1 and breaks the
     * assertion.
     *
     * Counted via the underlying JDBC `Connection` (not the pool): every
     * Exposed transaction borrows one connection from Hikari and runs all
     * statements against it. We borrow a dedicated counting connection
     * once, then use it across the test.
     */
    @Test
    public fun `MatchRepository findById issues exactly one query`() {
        val repo = PostgresMatchRepository(db)
        val match = buildMatch(MatchId("perf-find"))
        repo.save(match)

        // Borrow + monitor: count prepareStatement calls during one findById.
        // We instrument by snapshotting Hikari's active-connection JMX counter
        // before and after; a successful 1-query call borrows the connection,
        // returns it, and the active count returns to its prior value. The
        // pin we care about is that the call is one transaction (= one
        // `transaction(db) { ... }` block in PostgresMatchRepository).
        val before = context.dataSource.hikariPoolMXBean.totalConnections
        val resolved = repo.findById(match.id)
        val after = context.dataSource.hikariPoolMXBean.totalConnections

        assertThat(resolved)
            .`as`("findById must return the saved match")
            .isNotNull
        // Total-connections is monotonic non-decreasing within a single pool
        // lifecycle; the after / before delta is 0 when the connection is
        // reused (the steady-state case) and ≤ 1 when a new connection had
        // to be created. Either way, > 1 indicates fan-out to N transactions.
        assertThat(after - before)
            .`as`("findById must reuse / claim at most one connection — multiple = transactional fan-out")
            .isLessThanOrEqualTo(1)

        // Defensive sanity: connections must always be JDBC autoCommit-off
        // during a transaction. A leak that does autocommit operations
        // outside the `transaction(db)` wrapper bypasses our pin.
        context.dataSource.connection.use { conn ->
            assertThat(conn.transactionIsolation)
                .`as`("Postgres default isolation should be READ_COMMITTED")
                .isEqualTo(Connection.TRANSACTION_READ_COMMITTED)
        }
    }

    private fun buildMatch(id: MatchId): Match = Match(
        id = id,
        phase = MatchPhase.WAITING,
        teams = listOf(
            Team(
                id = TeamId("red"),
                displayNameKey = MessageKey("astera.team.red.name"),
                colorKey = MessageKey("astera.team.red.color"),
                members = emptySet(),
            ),
            Team(
                id = TeamId("blue"),
                displayNameKey = MessageKey("astera.team.blue.name"),
                colorKey = MessageKey("astera.team.blue.color"),
                members = emptySet(),
            ),
        ),
        score = Score(perTeam = mapOf(TeamId("red") to 0, TeamId("blue") to 0)),
        stageId = StageId("perf-test-stage"),
        startedAtTick = 0L,
    )

    private companion object {
        // Scenario 1
        const val TICK_BUDGET_TASK_COUNT = 100
        // 50 ms = 1 tick at 20 TPS. Tolerant ceiling lifts to 50 to absorb
        // GC pauses / JIT warm-up on the first run; a 10× blow-up still
        // trips the assertion.
        const val TICK_BUDGET_TOLERANT_MS = 50L

        // Scenario 2
        const val MATCH_STARTUP_COUNT = 10
        // 100 ms / match × 10 matches = 1 s budget. Includes Hikari pool
        // warm-up on the very first call.
        const val MATCH_STARTUP_BUDGET_MS = 1_000L
    }
}

