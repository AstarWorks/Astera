package com.astarworks.astera.core.domain.stage

import com.astarworks.astera.core.domain.match.TeamId

/** Which terrain style an [ArenaIsland] is built in. */
public enum class ArenaIslandKind {
    /** A team's home island: themed ground, team-coloured rim and spawn pad, banners. */
    BASE,

    /** The contested middle: stone-brick plaza. */
    CENTER,

    /** A small satellite island (generators, control points): grass top, no team colour. */
    OUTPOST,
}

/**
 * One floating island of a sky-island arena (ADR-0246). The top layer sits at [ArenaLayout.surfaceY];
 * the island tapers downwards into stone like the lobby islands.
 *
 * @property x block X of the island centre (world coordinates)
 * @property z block Z of the island centre (world coordinates)
 * @property radius nominal radius in blocks; the rim wobbles by up to about 19 percent
 * @property team team id this island belongs to ([ArenaIslandKind.BASE]); its colour trims the island
 * @property accent explicit vanilla colour name; defaults to the team id when that is a colour
 */
public data class ArenaIsland(
    val id: String,
    val kind: ArenaIslandKind,
    val x: Int,
    val z: Int,
    val radius: Double,
    val team: TeamId? = null,
    val accent: String? = null,
) {
    init {
        require(ID_REGEX.matches(id)) { "island id '$id' must be lower-kebab-case" }
        require(radius in MIN_RADIUS..MAX_RADIUS) { "island '$id' radius $radius must be in $MIN_RADIUS..$MAX_RADIUS" }
        require(accent == null || accent in COLORS) { "island '$id' accent '$accent' is not a vanilla colour" }
    }

    /** Vanilla colour name used for the island's trim (`white` when none is known). */
    public val colour: String
        get() = (accent ?: team?.value)?.takeIf { it in COLORS } ?: "white"

    public companion object {
        public const val MIN_RADIUS: Double = 3.0
        public const val MAX_RADIUS: Double = 64.0
        private val ID_REGEX: Regex = Regex("^[a-z][a-z0-9-]*$")

        /** Vanilla dye colours, usable as `<colour>_concrete` / `<colour>_terracotta` / `<colour>_banner`. */
        public val COLORS: Set<String> = setOf(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
        )
    }
}

/** A walkable bridge between two islands, [width] blocks wide, drawn centre to centre. */
public data class ArenaBridge(val from: String, val to: String, val width: Int = 3) {
    init {
        require(width in 1..7) { "bridge width $width must be in 1..7" }
        require(from != to) { "bridge '$from' cannot join an island to itself" }
    }
}

/** What a [ArenaLandmark] is. */
public enum class ArenaLandmarkKind {
    /** A star-generator pedestal (the generator YAML supplies the item spawning, the builder the pedestal). */
    GENERATOR,
}

/** A decorated spot on an island that the stage's regions and spawns do not already describe. */
public data class ArenaLandmark(val kind: ArenaLandmarkKind, val x: Int, val z: Int)

/** Client-side weather an arena can show. Only these two exist per player in Paper. */
public enum class ArenaWeather { CLEAR, RAIN }

/**
 * Time of day and weather the players of one arena see (MAP-08). The arena world is shared by several stages, so the
 * Paper adapter applies it per player (fixed player time and player weather) instead of changing the world.
 *
 * @property timeOfDay `0..23999` ticks (0 sunrise, 6000 noon, 12000 sunset, 18000 midnight)
 */
public data class ArenaAtmosphere(val timeOfDay: Long, val weather: ArenaWeather = ArenaWeather.CLEAR) {
    init {
        require(timeOfDay in 0L..23_999L) { "time_of_day $timeOfDay must be in 0..23999" }
    }
}

/**
 * The block layout of a sky-island arena (ADR-0246): a pure description a builder turns into blocks.
 * Spawns, objective regions and pillars stay on the owning [Stage]; the builder decorates them
 * (pads, pedestals, footings), so the stage supplies its anchors at exactly the built positions.
 *
 * @property markerVersion bump to force a rebuild when the code (not the data) changed the look
 * @property seed drives rim wobble and decoration; same seed + same layout = same blocks
 * @property surfaceY Y of the topmost solid layer of every island (players stand at `surfaceY + 1`)
 * @property killY players below this Y are killed (void fall plane)
 * @property atmosphere time of day / weather shown to players inside the arena; `null` keeps the world baseline (noon)
 */
public data class ArenaLayout(
    val markerVersion: Int = 1,
    val theme: ArenaTheme = ArenaTheme.TEMPERATE,
    val structures: List<ArenaStructurePlacement> = emptyList(),
    val seed: Long,
    val surfaceY: Int = 64,
    val killY: Int = surfaceY - DEFAULT_KILL_DEPTH,
    val islands: List<ArenaIsland>,
    val bridges: List<ArenaBridge> = emptyList(),
    val landmarks: List<ArenaLandmark> = emptyList(),
    val atmosphere: ArenaAtmosphere? = null,
) {
    init {
        require(islands.isNotEmpty()) { "an arena layout needs at least one island" }
        require(islands.map { it.id }.toSet().size == islands.size) { "arena layout declares an island id twice" }
        require(killY < surfaceY) { "kill_y ($killY) must be below surface_y ($surfaceY)" }
        val ids = islands.map { it.id }.toSet()
        bridges.forEach {
            require(it.from in ids && it.to in ids) { "bridge ${it.from} -> ${it.to} names an island that is not declared" }
        }
    }

    public companion object {
        /** How far below the islands the void kill plane sits by default (large layouts explicitly lower killY for their deeper foundations). */
        public const val DEFAULT_KILL_DEPTH: Int = 34
    }
}
