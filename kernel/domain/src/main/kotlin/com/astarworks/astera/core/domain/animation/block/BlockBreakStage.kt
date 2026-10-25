package com.astarworks.astera.core.domain.animation.block

/**
 * Cracked-block break stage (Wave 5animation, ADR-0113).
 *
 * Vanilla Minecraft renders 10 break-progress textures (stage 0 = first
 * crack appearing, stage 9 = nearly-broken). The Wave 6
 * `PaperBlockBreakAnimationGateway` translates each stage to a
 * `BlockBreakAnimationPacket(entityId, position, stage)` per viewer.
 *
 * [RESET] clears the animation entirely (vanilla packet stage value of
 * -1 / 10 depending on the protocol version; the adapter handles the
 * branch). Calling `setBreakStage(... RESET)` is equivalent to
 * `cancelBreakAnimation(...)` — the explicit RESET enum exists so the
 * gateway's history can record "the caller asked for a clear via
 * setBreakStage" vs "the caller asked for a cancel via cancelBreakAnimation"
 * (the underlying state change is identical).
 *
 * # Mapping to mining HP
 *
 * The progress mapping is owned by the caller, not the enum:
 * ```
 * val fraction = currentHp.toDouble() / maxHp.toDouble()  // 1.0 (intact) → 0.0 (broken)
 * val stage = when {
 *   fraction >= 1.0 -> BlockBreakStage.RESET
 *   fraction <= 0.0 -> BlockBreakStage.STAGE_9
 *   else -> BlockBreakStage.entries[
 *     ((1.0 - fraction) * 10.0).toInt().coerceIn(0, 9)
 *   ]
 * }
 * ```
 * The Wave 5j Mining HUD lives in `core.application.service.animation.*`
 * and applies exactly this mapping. The enum stays pure (10 ordered
 * stages + RESET) so callers can choose their own mapping (Boss-room
 * blocks may map 50/50/50 HP buckets to STAGE_0/STAGE_4/STAGE_9 instead
 * of a linear sweep).
 */
public enum class BlockBreakStage {
    /** First crack appears (vanilla packet stage `0`). */
    STAGE_0,

    /** Vanilla packet stage `1`. */
    STAGE_1,

    /** Vanilla packet stage `2`. */
    STAGE_2,

    /** Vanilla packet stage `3`. */
    STAGE_3,

    /** Vanilla packet stage `4`. */
    STAGE_4,

    /** Vanilla packet stage `5`. */
    STAGE_5,

    /** Vanilla packet stage `6`. */
    STAGE_6,

    /** Vanilla packet stage `7`. */
    STAGE_7,

    /** Vanilla packet stage `8`. */
    STAGE_8,

    /** Nearly-broken (vanilla packet stage `9`). */
    STAGE_9,

    /**
     * Clear any active break animation at this position. Equivalent to
     * calling `cancelBreakAnimation(world, pos)`.
     */
    RESET,
}
