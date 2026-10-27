package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.load.SkillCatalog
import com.astarworks.astera.core.application.skillengine.runtime.ModelPointSource
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutorRegistry
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.hit.ContactResolver
import com.astarworks.astera.core.domain.skillengine.motion.MotionLawFactory
import java.nio.file.Path

/**
 * The real collaborators [Coin2CastScenarioTest] runs against. Everything here is a contract interface; the
 * implementations come from the other skill-engine workers (ADR-0299):
 *
 * - [newTree]: W1 `InMemoryFrameTree` (one tree per cast).
 * - [motion]: W1 motion law factory.
 * - [contacts]: W2 contact resolver.
 * - [exprs]: W3b CEL layer (`ExprCompiler`).
 * - [executors]: a registry with every op-family executor of W10 to W15 registered.
 * - [points]: W3a item point source (`content/frames/<model>.yaml`).
 * - [newCatalog]: a fresh `SkillCatalog` backed by W3a's decoder and compiler.
 * - [skillsDir]: the directory holding the shipped coin-2 files (`couture-rapier-s2*`, base and `@crimson` patch).
 */
data class Coin2Collaborators(
    val newTree: () -> FrameTree,
    val motion: MotionLawFactory,
    val contacts: ContactResolver,
    val exprs: ExprCompiler,
    val executors: OpExecutorRegistry,
    val points: ModelPointSource,
    val newCatalog: () -> SkillCatalog,
    val skillsDir: Path,
)

/**
 * Wiring of the coin-2 scenario. The manager re-points [collaborators] at the real implementations when the
 * workers' branches are merged. Until then it answers `null` ("not wired"), and the scenario skips itself
 * instead of failing: it must only run once the real frame tree and contact resolver exist.
 */
object Coin2CastWiring {
    fun collaborators(): Coin2Collaborators? = null
}
