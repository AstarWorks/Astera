package com.astarworks.astera.core.domain.stage

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * A position in a [Stage] where a player can be teleported during a match
 * lifecycle event (entry, respawn, post-match return to lobby).
 *
 * # Wave 5o (ADR-0081) reshape
 *
 * Pre-Wave-5o the type was a `sealed interface SpawnPoint { Attacker / Defender
 * / Lobby / Spectator }` carrying only a [Vec3]. That shape conflated two
 * orthogonal concerns: **where** the spawn lives in the world (world + pos +
 * facing) and **which team-role** it belongs to.
 *
 * The Wave 5o respawn flow needs:
 *  - facing (yaw/pitch) so the player looks at their team's objective after
 *    teleport, and
 *  - a stable handle for "the Nth attacker spawn" so the [SpawnSelector]
 *    strategies (Random / CycleRotation / FurthestFromEnemies) can index into
 *    a flat list filtered by faction.
 *
 * A sealed-variant shape with positional-only state couldn't carry facing
 * without surgery on every variant and couldn't expose a list-index without
 * leaking the variant tag, so Wave 5o reshapes the type once.
 *
 * Per the pre-release posture (CLAUDE.md §Pre-release posture), the rename +
 * reshape is taken in one commit with no compatibility shim — the YAML
 * loader, [com.astarworks.astera.match.application.usecase.spawn.SpawnKind],
 * and [Stage] all migrate together. Authors may omit `yaw` / `pitch` (default
 * `0.0f`) and `world` (defaults to "world") in YAML; existing single-stage
 * fixtures need no change beyond the schema rename.
 *
 * @property world The world the spawn lives in. Most stages today use a single
 *   world; per-match world copies (Wave 5d `MatchWorld`) place each match in a
 *   per-id world, in which case the loader threads the per-match world id.
 * @property position Spawn coordinates in [world].
 * @property yaw Facing yaw in degrees (Bukkit convention: 0 = facing +Z south,
 *   90 = +X west, 180 = -Z north, -90 / 270 = -X east). The Paper adapter
 *   applies this to the player's `Location` on teleport.
 * @property pitch Facing pitch in degrees (0 = level, -90 = up, +90 = down).
 * @property faction Which side of the match this spawn belongs to. The
 *   [com.astarworks.astera.match.application.service.ISpawnSelector] uses this
 *   to filter the spawn list before applying its strategy.
 */
public data class SpawnPoint(
    public val world: WorldId,
    public val position: Vec3,
    public val yaw: Float = 0.0f,
    public val pitch: Float = 0.0f,
    public val faction: SpawnFaction = SpawnFaction.LOBBY,
) {
    /**
     * Position-only accessor retained as `pos` for the small set of call sites
     * (Wave 4.5 `MatchSpawnUseCase`, fakes) that only care about coordinates.
     * New code should reach for [position] directly so the intent is explicit.
     */
    public val pos: Vec3 get() = position
}

/**
 * The team-role a [SpawnPoint] fulfils.
 *
 * Replaces the pre-Wave-5o `SpawnPoint.Attacker` / `Defender` / `Lobby` /
 * `Spectator` sealed variants — kept as an enum so:
 *  - YAML authors write `faction: attacker` instead of `type: attacker` and
 *    use a single flat DTO,
 *  - the [com.astarworks.astera.match.application.service.ISpawnSelector] can
 *    filter spawns with `.filter { it.faction == kind.toFaction() }` rather
 *    than a `when (variant)` ladder that would have to be edited every time
 *    a new role appears.
 */
public enum class SpawnFaction {
    /** Attacker / red team. */
    ATTACKER,

    /** Defender / blue team. */
    DEFENDER,

    /** Pre-match lobby / staging area. */
    LOBBY,

    /** Spectator camera / dead-player view. */
    SPECTATOR,
}
