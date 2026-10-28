package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.application.skillengine.compose.FrameTables
import com.astarworks.astera.core.application.skillengine.compose.PartCatalog
import com.astarworks.astera.core.application.skillengine.compose.SkillCompiler
import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpecRegistry
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.part.Part
import java.nio.file.Path

/**
 * The production decode + compile stack of skill engine v3 (ADR-0303), built in one place so the plugin, the game-sim scenarios
 * and `tools/skill-lint` cannot drift apart (ADR-0301). Every op spec is registered in [SkillEngineSpecs] and nowhere else.
 */
public object SkillEngineRegistries {
    /** The v3 document decoder over every registered [SkillEngineSpecs] op. */
    public fun documentDecoder(): SkillDocumentDecoder = SkillDocumentDecoder(OpSpecRegistry(SkillEngineSpecs.all()), CelExprCompiler())

    /** Item point tables (the `content/frames` files); a table that fails to decode is reported, the rest still load. */
    public fun frameTables(framesDir: Path): Pair<Map<String, ItemFrames>, List<SkillDecodeError>> =
        ItemFramesDecoder.loadDirectory(framesDir)

    public fun compiler(tables: Map<String, ItemFrames>, config: EngineConfig = EngineConfig()): SkillCompiler {
        val frames = object : FrameTables {
            override fun item(item: String): ItemFrames? = tables[item]
        }
        return SkillCompiler(OpSpecRegistry(SkillEngineSpecs.all()), CelExprCompiler(), frames, config)
    }

    public fun catalog(tables: Map<String, ItemFrames>, parts: Map<String, Part>, config: EngineConfig = EngineConfig()): InMemorySkillCatalog {
        val partCatalog = object : PartCatalog {
            override fun part(id: String): Part? = parts[id]
        }
        return InMemorySkillCatalog(documentDecoder(), compiler(tables, config), partCatalog, parts.keys)
    }
}
