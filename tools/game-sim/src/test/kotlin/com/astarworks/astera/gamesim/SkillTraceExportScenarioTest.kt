package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineContents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The generic v2 trace feed of the skill preview (ADR-0301, SKL-08): casts any shipped v2 skill once against a dummy 6 blocks
 * ahead and writes `build/sim-traces/v2/<skill-id>.json` (`astera-sim-trace/2`, `tools/vfx-shot/SIM-TRACE-V2.md`).
 *
 * Driven by `make skill-trace SKILL=<id>[,<id>...]` (`-Pskill=` -> system property `astera.sim.skill`); without it the test is
 * skipped. Reuses the coin-2 wiring and the shared [Sim] harness, so the trace is what the real cast runner produces.
 */
class SkillTraceExportScenarioTest {

    @Test
    fun `exports the v2 trace of the requested skills`() {
        val ids = System.getProperty("astera.sim.skill").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        assumeTrue(ids.isNotEmpty(), "no -Pskill=<id>: nothing to export")
        val c = Coin2CastWiring.collaborators()
        val catalog = c.newCatalog()
        val report = SkillEngineContents(catalog).reload(c.skillsDir)
        assertThat(report.failed).describedAs("shipped skills load: ${report.failures}").isZero()
        for (id in ids) {
            val skill = requireNotNull(catalog.get(id)) { "no v2 skill '$id' (known: ${catalog.ids().sorted()})" }
            val sim = Sim(c, catalog, withDummy = true)
            val cast = sim.start(skill, 0)
            val end = sim.run(0, listOf(cast), limit = 160)
            writeTrace(skill, sim, cast, end)
            // a broken op never blocks the preview: the trace carries `diagnostics`, the preview lists them as warnings
            for (d in sim.runner.diagnostics()) System.err.println("skill-trace $id: tick ${d.tick} op ${d.opId}: ${d.message}")
        }
    }
}
