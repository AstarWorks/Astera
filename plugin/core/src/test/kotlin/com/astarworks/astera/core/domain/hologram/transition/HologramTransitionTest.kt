package com.astarworks.astera.core.domain.hologram.transition

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.hologram.BillboardMode
import com.astarworks.astera.core.domain.hologram.HologramBrightness
import com.astarworks.astera.core.domain.hologram.HologramColor
import com.astarworks.astera.core.domain.hologram.HologramTransform
import com.astarworks.astera.core.domain.hologram.ItemDisplayMode
import com.astarworks.astera.core.domain.hologram.TextAlignment
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HologramTransitionTest {

    @Test
    fun `smooth variants reject negative durationTicks`() {
        assertThatThrownBy {
            HologramTransition.Transform(HologramTransform(), durationTicks = -1, easing = Easing.LINEAR)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            HologramTransition.Brightness(null, durationTicks = -5, easing = Easing.LINEAR)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            HologramTransition.GlowColor(null, durationTicks = -1, easing = Easing.LINEAR)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            HologramTransition.ShadowRadius(2f, durationTicks = -10, easing = Easing.EASE_IN_OUT_QUAD)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            HologramTransition.TextOpacity(127, durationTicks = -1, easing = Easing.LINEAR)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            HologramTransition.TextLineWidth(100, durationTicks = -1, easing = Easing.LINEAR)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `smooth variants accept zero durationTicks for snap-to-target semantics`() {
        // durationTicks = 0 is allowed — the controller treats it as an instant
        // final-value set on the first sampled tick.
        HologramTransition.Transform(HologramTransform(), durationTicks = 0, easing = Easing.LINEAR)
        HologramTransition.ShadowRadius(5f, durationTicks = 0, easing = Easing.LINEAR)
        HologramTransition.TextOpacity(0, durationTicks = 0, easing = Easing.LINEAR)
    }

    @Test
    fun `categorical variants reject negative delayTicks`() {
        assertThatThrownBy {
            HologramTransition.TextContent(emptyList(), delayTicks = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            HologramTransition.ItemSwap(ItemId("example-sword"), delayTicks = -2)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            HologramTransition.BlockData("minecraft:stone", delayTicks = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            HologramTransition.Billboard(BillboardMode.FIXED, delayTicks = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `BlockData rejects blank blockKey`() {
        assertThatThrownBy {
            HologramTransition.BlockData(" ")
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            HologramTransition.BlockData("")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Composite rejects empty transition list`() {
        assertThatThrownBy {
            HologramTransition.Composite(emptyList())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Composite accepts non-empty transition list`() {
        val composite = HologramTransition.Composite(
            transitions = listOf(
                HologramTransition.ShadowRadius(1f, 10, Easing.LINEAR),
                HologramTransition.GlowColor(HologramColor(1, 2, 3), 10, Easing.LINEAR),
            ),
        )
        assertThat(composite.transitions).hasSize(2)
    }

    @Test
    fun `categorical TextSeeThrough TextShadowed TextDefaultBackground all constructible with valid args`() {
        // These three exist as separate variants so the controller's sealed-when
        // can dispatch per field. Smoke-test all three construct without throwing
        // for valid delayTicks values (0 and positive).
        HologramTransition.TextSeeThrough(true, delayTicks = 0)
        HologramTransition.TextSeeThrough(false, delayTicks = 5)
        HologramTransition.TextShadowed(true, delayTicks = 0)
        HologramTransition.TextDefaultBackground(true, delayTicks = 10)
        HologramTransition.TextAlignmentSet(TextAlignment.LEFT, delayTicks = 0)
        HologramTransition.ItemDisplayModeSet(ItemDisplayMode.HEAD, delayTicks = 0)
    }

    @Test
    fun `all 19 sealed variants enumerable for exhaustiveness`() {
        // Compile-time exhaustiveness check: every variant has a branch.
        // Build one instance of each variant and dispatch through `when`; the
        // when must be exhaustive without a remaining `else` clause.
        val every: List<HologramTransition> = listOf(
            HologramTransition.Transform(HologramTransform(), 1, Easing.LINEAR),
            HologramTransition.Brightness(HologramBrightness(5, 5), 1, Easing.LINEAR),
            HologramTransition.GlowColor(HologramColor(0, 0, 0), 1, Easing.LINEAR),
            HologramTransition.ShadowRadius(1f, 1, Easing.LINEAR),
            HologramTransition.ShadowStrength(1f, 1, Easing.LINEAR),
            HologramTransition.ViewRange(64f, 1, Easing.LINEAR),
            HologramTransition.TextOpacity(0, 1, Easing.LINEAR),
            HologramTransition.TextBackgroundColor(HologramColor(0, 0, 0), 1, Easing.LINEAR),
            HologramTransition.TextLineWidth(100, 1, Easing.LINEAR),
            HologramTransition.TextContent(listOf(MessageKey("astera.test.x"))),
            HologramTransition.TextAlignmentSet(TextAlignment.CENTER),
            HologramTransition.TextSeeThrough(true),
            HologramTransition.TextShadowed(false),
            HologramTransition.TextDefaultBackground(true),
            HologramTransition.ItemSwap(ItemId("example-sword")),
            HologramTransition.ItemDisplayModeSet(ItemDisplayMode.FIXED),
            HologramTransition.BlockData("minecraft:stone"),
            HologramTransition.Billboard(BillboardMode.FIXED),
            HologramTransition.Composite(listOf(HologramTransition.ShadowRadius(1f, 1, Easing.LINEAR))),
        )
        val tagged: List<String> = every.map { transition ->
            // Exhaustive `when` — if a new variant lands without a branch the
            // build fails here, forcing the controller / events / converter to
            // be updated in lock-step.
            when (transition) {
                is HologramTransition.Transform -> "transform"
                is HologramTransition.Brightness -> "brightness"
                is HologramTransition.GlowColor -> "glow_color"
                is HologramTransition.ShadowRadius -> "shadow_radius"
                is HologramTransition.ShadowStrength -> "shadow_strength"
                is HologramTransition.ViewRange -> "view_range"
                is HologramTransition.TextOpacity -> "text_opacity"
                is HologramTransition.TextBackgroundColor -> "text_background_color"
                is HologramTransition.TextLineWidth -> "text_line_width"
                is HologramTransition.TextContent -> "text_content"
                is HologramTransition.TextAlignmentSet -> "text_alignment"
                is HologramTransition.TextSeeThrough -> "text_see_through"
                is HologramTransition.TextShadowed -> "text_shadowed"
                is HologramTransition.TextDefaultBackground -> "text_default_background"
                is HologramTransition.ItemSwap -> "item_swap"
                is HologramTransition.ItemDisplayModeSet -> "item_display_mode"
                is HologramTransition.BlockData -> "block_data"
                is HologramTransition.Billboard -> "billboard"
                is HologramTransition.Composite -> "composite"
            }
        }
        assertThat(tagged).hasSize(19)
        assertThat(tagged.toSet()).hasSize(19) // no duplicates
    }
}
