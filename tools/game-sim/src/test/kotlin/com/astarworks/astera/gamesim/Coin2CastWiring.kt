package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.load.SkillCatalog
import com.astarworks.astera.core.application.skillengine.load.SkillEngineRegistries
import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.load.ItemFramesDecoder
import com.astarworks.astera.core.application.skillengine.runtime.ModelPointSource
import com.astarworks.astera.core.domain.skillengine.expr.ExprCompiler
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.frame.InMemoryFrameTree
import com.astarworks.astera.core.domain.skillengine.hit.ContactResolver
import com.astarworks.astera.core.domain.skillengine.hit.DefaultContactResolver
import com.astarworks.astera.core.domain.skillengine.motion.DefaultMotionLawFactory
import com.astarworks.astera.core.domain.skillengine.motion.MotionLawFactory
import java.nio.file.Path

/**
 * The real collaborators [Coin2CastScenarioTest] runs against (ADR-0299): the production frame tree, motion laws,
 * contact resolver, CEL compiler and the shipped `content/frames` point tables. The op executors are assembled by
 * `SkillEngineKit` from the scenario's fake gateways.
 */
data class Coin2Collaborators(
    val newTree: () -> FrameTree,
    val motion: MotionLawFactory,
    val contacts: ContactResolver,
    val exprs: ExprCompiler,
    val points: ModelPointSource,
    val newCatalog: () -> SkillCatalog,
    val skillsDir: Path,
)

object Coin2CastWiring {
    fun collaborators(): Coin2Collaborators {
        val content = Path.of(requireNotNull(System.getProperty("astera.sim.contentDir")) { "astera.sim.contentDir is not set" })
        val (tables, errors) = ItemFramesDecoder.loadDirectory(content.resolve("frames"))
        check(errors.isEmpty()) { "frame tables: $errors" }
        return Coin2Collaborators(
            newTree = { InMemoryFrameTree() },
            motion = DefaultMotionLawFactory(),
            contacts = DefaultContactResolver(),
            exprs = CelExprCompiler(),
            points = SkillEngineRegistries.pointSource(tables),
            newCatalog = { SkillEngineRegistries.catalog(tables) },
            skillsDir = content.resolve("skills"),
        )
    }
}
