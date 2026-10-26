package com.astarworks.astera.core.domain.stage

/**
 * A pre-built world ("imported arena", ADR-0281): every block of a map built elsewhere, as vendor-neutral block-data
 * strings, in the map's own x/z coordinates and absolute y (the floor already sits at the stage's `surface_y`).
 * Pure data; the `content/arenas/` loader produces it and [ArenaIslandGenerator] turns it into an [ArenaBuildPlan].
 *
 * @property credits who made the world and under which licence; carried with the derived data (CC BY-SA keeps applying)
 */
public class ArenaImport(
    public val id: String,
    public val blocks: Map<ArenaBlockPos, String>,
    public val credits: ArenaImportCredits,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]*"))) { "arena import id '$id' must be lower-kebab-case" }
        require(blocks.isNotEmpty()) { "arena import '$id' has no blocks" }
    }
}

/** Attribution for an imported world; every field is required so a map cannot ship without its licence trail. */
public data class ArenaImportCredits(
    val title: String,
    val authors: List<String>,
    val source: String,
    val license: String,
    val modified: String,
) {
    init {
        require(authors.isNotEmpty()) { "credits need at least one author" }
        require(title.isNotBlank() && source.isNotBlank() && license.isNotBlank() && modified.isNotBlank()) { "credits fields must not be blank" }
    }
}

/** Where an [ArenaImport] sits in the stage's local coordinates: the import's own x/z origin lands on ([x], [z]). */
public data class ArenaImportPlacement(val map: String, val x: Int = 0, val z: Int = 0)
