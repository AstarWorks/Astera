package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.stage.ArenaBlockPos
import com.astarworks.astera.core.domain.stage.ArenaImport
import com.astarworks.astera.core.domain.stage.ArenaImportCredits
import kotlinx.serialization.Serializable

/**
 * `content/arenas/<id>.yaml`: a pre-built world (ADR-0281) in run-length layers.
 *
 * ```yaml
 * id: temple-trouble
 * credits: { title: ..., authors: [...], source: <url>, license: CC-BY-SA-4.0, modified: <what changed> }
 * origin: [-34, 53, -44]     # x, y, z of the first cell
 * size: [59, 26, 123]        # x, y, z extent
 * palette: ["minecraft:stone", ...]   # token 0 is air, token n is palette[n - 1]
 * layers:                    # one string per y (bottom first); cells run x fastest, then z
 *   - "0:120 1:3 0:7 ..."    # `token:count` runs
 * ```
 */
@Serializable
public data class ArenaImportYamlConfig(
    val id: String,
    val credits: ArenaImportCreditsYamlConfig,
    val origin: List<Int>,
    val size: List<Int>,
    val palette: List<String>,
    val layers: List<String>,
) {
    public fun toDomain(): ArenaImport {
        require(origin.size == 3 && size.size == 3) { "arena '$id' origin/size need [x, y, z]" }
        val (sx, sy, sz) = size
        require(sx > 0 && sy > 0 && sz > 0) { "arena '$id' size must be positive" }
        require(layers.size == sy) { "arena '$id' has ${layers.size} layers, size says $sy" }
        val blocks = HashMap<ArenaBlockPos, String>()
        for ((dy, layer) in layers.withIndex()) {
            var cell = 0
            for (run in layer.trim().split(' ').filter { it.isNotEmpty() }) {
                val token = run.substringBefore(':').toInt()
                val count = run.substringAfter(':').toInt()
                require(token in 0..palette.size && count > 0) { "arena '$id' layer $dy has a bad run '$run'" }
                if (token != 0) {
                    val state = palette[token - 1]
                    for (c in cell until cell + count) blocks[ArenaBlockPos(origin[0] + c % sx, origin[1] + dy, origin[2] + c / sx)] = state
                }
                cell += count
            }
            require(cell == sx * sz) { "arena '$id' layer $dy covers $cell cells, expected ${sx * sz}" }
        }
        return ArenaImport(id, blocks, credits.toDomain())
    }
}

@Serializable
public data class ArenaImportCreditsYamlConfig(
    val title: String,
    val authors: List<String>,
    val source: String,
    val license: String,
    val modified: String,
) {
    public fun toDomain(): ArenaImportCredits = ArenaImportCredits(title, authors, source, license, modified)
}
