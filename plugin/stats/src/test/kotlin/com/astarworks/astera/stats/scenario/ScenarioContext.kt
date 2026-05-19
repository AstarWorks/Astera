package com.astarworks.astera.stats.scenario

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.domain.match.TeamId

/**
 * Thin wrapper around [AsteraTestHarness] that holds per-scenario mutable
 * state (test-local variables the DSL needs to carry between `fires` / `at`
 * / assertion calls).
 *
 * One [ScenarioContext] per `scenario { ... }` block. Constructed via the
 * top-level [scenario] function in [ScenarioDsl].
 */
public class ScenarioContext internal constructor(
    public val harness: AsteraTestHarness,
) {

    /** Shortcut: spawn a player by [name], optionally assigning [team]. */
    public fun player(name: String, team: TeamId? = null): IMcPlayer =
        harness.spawnPlayer(name, team)

    /** Convenience: advance the deterministic scheduler by [n] ticks. */
    public fun advanceTicks(n: Int) {
        harness.advanceTicks(n)
    }
}
