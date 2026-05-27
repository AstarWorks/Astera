package com.astarworks.astera.core.domain.hologram.transition

import com.astarworks.astera.core.domain.hologram.HologramColor
import kotlin.math.roundToInt

/**
 * Pure-math helper for tweening RGBA hologram colours (Wave 5transitions,
 * ADR-0115 §D).
 *
 * Lerps each channel as an `Int` (the byte interpreted as a signed value)
 * and rounds back to [Byte] at write time. Channel-wise lerp is correct
 * for the gamma-encoded sRGB byte representation Bukkit uses on
 * `TextDisplay.backgroundColor` / `Display.glowColorOverride` — the
 * adapter does no further translation, so we tween in the same space the
 * adapter writes.
 *
 * # Null handling
 *
 * `null` represents "no override" semantically. For interpolation we treat
 * `null` start or end as fully-transparent black `(0, 0, 0, 0)` — the user
 * sees a smooth fade from / to invisibility. Both `null` returns `null`
 * unchanged regardless of `factor` (no transition needed).
 *
 * # Byte rounding edge cases
 *
 * `Byte` is signed `[-128, 127]`. Interpolating between `-128` and `127`
 * traverses through `0` — the visual effect on a colour channel is the
 * conventional "0x80 → 0x00 → 0x7F" wrap, which is **wrong** for sRGB
 * (the user expects a smooth `0x80 → 0xC0 → 0xFF` brightening). Callers
 * must specify the **signed** byte values they actually want as endpoints
 * — `HologramColor(127, 127, 127)` is "max value, no further to go" rather
 * than "mid-grey". The signed-byte representation comes from Bukkit's
 * `Color` API; we don't second-guess it at the interpolation step.
 */
public object HologramColorInterpolator {

    /**
     * Interpolate between [from] and [to] by [factor].
     *
     * Returns a fresh [HologramColor] with each channel rounded
     * half-to-even (`Double.roundToInt()`) and coerced into `[-128, 127]`.
     * `factor` clamping is the caller's responsibility — easing curves
     * may overshoot, and the channel coercion absorbs the resulting
     * out-of-range Doubles.
     */
    public fun lerp(from: HologramColor?, to: HologramColor?, factor: Double): HologramColor? {
        if (from == null && to == null) return null
        val a = from ?: HologramColor(0, 0, 0, 0)
        val b = to ?: HologramColor(0, 0, 0, 0)
        return HologramColor(
            red = lerpChannel(a.red, b.red, factor),
            green = lerpChannel(a.green, b.green, factor),
            blue = lerpChannel(a.blue, b.blue, factor),
            alpha = lerpChannel(a.alpha, b.alpha, factor),
        )
    }

    private fun lerpChannel(a: Byte, b: Byte, factor: Double): Byte {
        val aInt = a.toInt()
        val bInt = b.toInt()
        val raw = aInt + (bInt - aInt) * factor
        return raw.roundToInt().coerceIn(BYTE_MIN, BYTE_MAX).toByte()
    }

    private const val BYTE_MIN: Int = -128
    private const val BYTE_MAX: Int = 127
}
