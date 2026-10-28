package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.load.ops.ControlOpDecoders
import com.astarworks.astera.core.application.skillengine.load.ops.FrameOpDecoders
import com.astarworks.astera.core.application.skillengine.load.ops.HitOpDecoders
import com.astarworks.astera.core.application.skillengine.load.ops.damageDecoders
import com.astarworks.astera.core.application.skillengine.load.ops.movementDecoders
import com.astarworks.astera.core.application.skillengine.load.ops.spawnDecoders
import com.astarworks.astera.core.application.skillengine.load.ops.statusDecoders
import com.astarworks.astera.core.application.skillengine.load.presentation.presentationDecoders
import com.astarworks.astera.core.application.skillengine.runtime.ModelPoint
import com.astarworks.astera.core.application.skillengine.runtime.ModelPointSource
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpecRegistry
import java.nio.file.Path

/**
 * The production decode + compile stack of skill engine v2, built in one place so the plugin, the game-sim scenarios
 * and `tools/skill-lint` cannot drift apart (ADR-0301). Every op family is registered here and nowhere else.
 */
public object SkillEngineRegistries {
    public fun decoder(blocks: IBlockKeyCatalog): SkillDecoder = YamlSkillDecoder(
        DefaultOpRegistries.decoders(
            movementDecoders(), statusDecoders(), worldDecoders(blocks), spawnDecoders(), damageDecoders(), presentationDecoders(),
            listOf(FrameOpDecoders(), HitOpDecoders(), ControlOpDecoders()),
        ),
    )

    /** The v3 document decoder over every registered [SkillEngineSpecs] op (replaces [decoder] at the W1b purge). */
    public fun documentDecoder(): SkillDocumentDecoder = SkillDocumentDecoder(OpSpecRegistry(SkillEngineSpecs.all()), CelExprCompiler())

    /** Item point tables (the `content/frames` files); a table that fails to decode is reported, the rest still load. */
    public fun frameTables(framesDir: Path): Pair<Map<String, ItemFrames>, List<SkillDecodeError>> =
        ItemFramesDecoder.loadDirectory(framesDir)

    public fun compiler(tables: Map<String, ItemFrames>): SkillCompiler = DefaultSkillCompiler(CelExprCompiler()) { tables[it] }

    public fun catalog(tables: Map<String, ItemFrames>, blocks: IBlockKeyCatalog): InMemorySkillCatalog =
        InMemorySkillCatalog(decoder(blocks), compiler(tables))

    /** Point source for ITEM / PART frames, backed by the decoded point tables. */
    public fun pointSource(tables: Map<String, ItemFrames>): ModelPointSource = ModelPointSource { model, part ->
        val p = tables[model]?.partFor(part) ?: return@ModelPointSource emptyList()
        p.points.map { (name, offset) -> ModelPoint(name, offset, p.axes[name]) }
    }
}
