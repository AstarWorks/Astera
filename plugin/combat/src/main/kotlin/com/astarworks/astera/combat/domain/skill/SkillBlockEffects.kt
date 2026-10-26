package com.astarworks.astera.combat.domain.skill

import com.astarworks.astera.core.domain.block.BlockDamageProfile
import com.astarworks.astera.core.domain.world.ToolType

/**
 * Per-skill opt-in to cover damage (ADR-0280). A skill without it never touches cover.
 *
 * - [amount] without a [tool]: a flat HP chip (guns and spells deal small ones).
 *   With a [tool]: the matrix base, 100 = one swing of that tool (tool skills deal large ones).
 * - [radius]: blocks around the impact / cast point that take the damage; 0 = the single cover block struck.
 * - [reach] (cast-time skills: melee, sequence, buff, ...): how far the caster's look ray travels to find cover,
 *   and for [Shape.LINE] the length of the cut. Projectile and AOE skills strike where they land and ignore it.
 */
public data class BlockDamageSpec(
    val amount: Int,
    val radius: Double = 0.0,
    val tool: ToolType? = null,
    val reach: Double = 0.0,
    val shape: Shape = Shape.BALL,
) {
    init {
        require(amount > 0) { "block_damage.amount must be > 0: $amount" }
        require(radius in 0.0..MAX_RADIUS) { "block_damage.radius must be in 0..$MAX_RADIUS: $radius" }
        require(reach in 0.0..MAX_REACH) { "block_damage.reach must be in 0..$MAX_REACH: $reach" }
        require(shape == Shape.BALL || reach > 0.0) { "block_damage.shape line needs reach > 0" }
    }

    /** How the damage is spread: a ball around one point, or a tube along the look direction. */
    public enum class Shape { BALL, LINE }

    /** The damage profile handed to the block gateway. */
    public fun profile(): BlockDamageProfile =
        if (tool == null) BlockDamageProfile.flat(amount) else BlockDamageProfile(amount, tool)

    public companion object {
        public const val MAX_RADIUS: Double = 6.0
        public const val MAX_REACH: Double = 16.0
    }
}

/**
 * A tool skill that raises a temporary wall of a cover block in front of the caster (ADR-0280): [width] wide and
 * [height] tall, [distance] blocks ahead, only into empty space. Match end removes it.
 */
public data class BlockPlaceSpec(
    val block: String,
    val distance: Int = 2,
    val width: Int = 3,
    val height: Int = 2,
) {
    init {
        require(block.isNotBlank()) { "block_place.block must not be blank" }
        require(distance in 1..MAX_DISTANCE) { "block_place.distance must be in 1..$MAX_DISTANCE: $distance" }
        require(width in 1..MAX_WIDTH) { "block_place.width must be in 1..$MAX_WIDTH: $width" }
        require(height in 1..MAX_HEIGHT) { "block_place.height must be in 1..$MAX_HEIGHT: $height" }
    }

    public companion object {
        public const val MAX_DISTANCE: Int = 6
        public const val MAX_WIDTH: Int = 7
        public const val MAX_HEIGHT: Int = 4
    }
}
