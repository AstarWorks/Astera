package com.astarworks.astera.core.application.gui.hud

import com.astarworks.astera.core.application.port.outbound.ICustomFontRegistry
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudGlyph
import com.astarworks.astera.core.domain.hud.HudGlyphRef
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [HudRenderer] (Wave 5j Worker γ).
 *
 * The fake [ICustomFontRegistry] is intentionally anonymous + per-test —
 * Worker β's `FakeCustomFontRegistry` (test-fixtures) is not yet wired,
 * and keeping this test self-contained lets it run independently. When
 * Worker β lands the fixture, Phase C may refactor these tests to use
 * the shared fake.
 */
class HudRendererTest {

    private val viewer = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000010"))

    private val manaIconRef = HudGlyphRef("mana-icon")
    private val barFillRef = HudGlyphRef("hud/bar-fill")
    private val barEmptyRef = HudGlyphRef("hud/bar-empty")
    private val unknownRef = HudGlyphRef("nope/not-registered")

    private val manaIcon = HudGlyph(
        textureRef = manaIconRef,
        unicodeChar = '\uE001',
        widthPx = 8,
        heightPx = 8,
    )
    private val barFill = HudGlyph(
        textureRef = barFillRef,
        unicodeChar = '\uE100',
        widthPx = 8,
        heightPx = 4,
    )
    private val barEmpty = HudGlyph(
        textureRef = barEmptyRef,
        unicodeChar = '\uE101',
        widthPx = 8,
        heightPx = 4,
    )

    private fun fontRegistry(glyphs: List<HudGlyph> = listOf(manaIcon, barFill, barEmpty)): ICustomFontRegistry {
        val map = glyphs.associateBy { it.textureRef }.toMutableMap()
        return object : ICustomFontRegistry {
            override fun lookup(ref: HudGlyphRef): HudGlyph? = map[ref]
            override fun all(): Collection<HudGlyph> = map.values
            override fun register(glyph: HudGlyph) {
                map[glyph.textureRef] = glyph
            }
        }
    }

    private fun renderer(
        messages: FakeMessageRenderer = FakeMessageRenderer(),
        fonts: ICustomFontRegistry = fontRegistry(),
    ): HudRenderer = HudRenderer(messages, fonts)

    @Test
    fun `Text without color renders the i18n-resolved string with no color`() {
        val key = MessageKey("astera.hud.mana.label")
        val rendered = renderer().render(HudComponent.Text(key, color = null), viewer)

        assertThat(rendered).isInstanceOf(TextComponent::class.java)
        assertThat((rendered as TextComponent).content()).isEqualTo("astera.hud.mana.label")
        assertThat(rendered.color()).isNull()
    }

    @Test
    fun `Text with HudColor red renders a Component with matching TextColor`() {
        val key = MessageKey("astera.hud.warning")
        val red = HudColor.rgb(0xFF, 0x00, 0x00)

        val rendered = renderer().render(HudComponent.Text(key, color = red), viewer)

        assertThat(rendered).isInstanceOf(TextComponent::class.java)
        assertThat(rendered.color()).isEqualTo(TextColor.color(0xFF, 0x00, 0x00))
    }

    @Test
    fun `Glyph with registered ref emits unicodeChar with astera default font`() {
        val rendered = renderer().render(
            HudComponent.Glyph(textureRef = manaIconRef, widthPx = 8, ascent = 0),
            viewer,
        )

        assertThat(rendered).isInstanceOf(TextComponent::class.java)
        assertThat((rendered as TextComponent).content()).isEqualTo(manaIcon.unicodeChar.toString())
        assertThat(rendered.font()).isEqualTo(HudRenderer.ASTERA_FONT_KEY)
    }

    @Test
    fun `Glyph with unknown ref falls back to the red question-mark placeholder`() {
        val rendered = renderer().render(
            HudComponent.Glyph(textureRef = unknownRef, widthPx = 8, ascent = 0),
            viewer,
        )

        assertThat(rendered).isEqualTo(HudRenderer.BARRIER_PLACEHOLDER_COMPONENT)
        assertThat(rendered).isInstanceOf(TextComponent::class.java)
        assertThat((rendered as TextComponent).content()).isEqualTo("?")
        assertThat(rendered.color()).isEqualTo(NamedTextColor.RED)
    }

    @Test
    fun `ProgressBar at 50 percent of 10 segments renders 5 fill glyphs followed by 5 empty glyphs`() {
        val rendered = renderer().render(
            HudComponent.ProgressBar(
                percent = 0.5f,
                fillGlyph = barFillRef,
                emptyGlyph = barEmptyRef,
                widthSegments = 10,
            ),
            viewer,
        )

        // The renderer produces empty.append(fill).append(empty); children carry the glyph text.
        val children = rendered.children()
        assertThat(children).hasSize(2)

        val fillChild = children[0] as TextComponent
        assertThat(fillChild.content()).isEqualTo(barFill.unicodeChar.toString().repeat(5))
        assertThat(fillChild.font()).isEqualTo(HudRenderer.ASTERA_FONT_KEY)

        val emptyChild = children[1] as TextComponent
        assertThat(emptyChild.content()).isEqualTo(barEmpty.unicodeChar.toString().repeat(5))
        assertThat(emptyChild.font()).isEqualTo(HudRenderer.ASTERA_FONT_KEY)
    }

    @Test
    fun `ProgressBar at 0 percent renders zero fill glyphs and all empty glyphs`() {
        val rendered = renderer().render(
            HudComponent.ProgressBar(
                percent = 0f,
                fillGlyph = barFillRef,
                emptyGlyph = barEmptyRef,
                widthSegments = 8,
            ),
            viewer,
        )

        val children = rendered.children()
        val fillChild = children[0] as TextComponent
        val emptyChild = children[1] as TextComponent
        assertThat(fillChild.content()).isEmpty()
        assertThat(emptyChild.content()).isEqualTo(barEmpty.unicodeChar.toString().repeat(8))
    }

    @Test
    fun `ProgressBar at 100 percent renders all fill glyphs and zero empty glyphs`() {
        val rendered = renderer().render(
            HudComponent.ProgressBar(
                percent = 1f,
                fillGlyph = barFillRef,
                emptyGlyph = barEmptyRef,
                widthSegments = 8,
            ),
            viewer,
        )

        val children = rendered.children()
        val fillChild = children[0] as TextComponent
        val emptyChild = children[1] as TextComponent
        assertThat(fillChild.content()).isEqualTo(barFill.unicodeChar.toString().repeat(8))
        assertThat(emptyChild.content()).isEmpty()
    }

    @Test
    fun `ProgressBar with either glyph ref missing degrades to the placeholder`() {
        val limitedFonts = fontRegistry(glyphs = listOf(barFill)) // no empty glyph
        val rendered = renderer(fonts = limitedFonts).render(
            HudComponent.ProgressBar(
                percent = 0.5f,
                fillGlyph = barFillRef,
                emptyGlyph = barEmptyRef,
                widthSegments = 10,
            ),
            viewer,
        )

        assertThat(rendered).isEqualTo(HudRenderer.BARRIER_PLACEHOLDER_COMPONENT)
    }

    @Test
    fun `Composite with no spacing appends each part in order with no separator`() {
        val key = MessageKey("astera.hud.label")
        val composite = HudComponent.Composite(
            parts = listOf(
                HudComponent.Glyph(textureRef = manaIconRef, widthPx = 8, ascent = 0),
                HudComponent.Text(key, color = null),
            ),
            spacing = 0,
        )

        val rendered = renderer().render(composite, viewer)
        val children = rendered.children()
        assertThat(children).hasSize(2)

        val glyphChild = children[0] as TextComponent
        assertThat(glyphChild.content()).isEqualTo(manaIcon.unicodeChar.toString())
        assertThat(glyphChild.font()).isEqualTo(HudRenderer.ASTERA_FONT_KEY)

        val textChild = children[1] as TextComponent
        assertThat(textChild.content()).isEqualTo("astera.hud.label")
    }

    @Test
    fun `Composite with positive spacing inserts a spacer component between parts`() {
        val composite = HudComponent.Composite(
            parts = listOf(
                HudComponent.Text(MessageKey("a.one"), color = null),
                HudComponent.Text(MessageKey("a.two"), color = null),
                HudComponent.Text(MessageKey("a.three"), color = null),
            ),
            spacing = 4,
        )

        val rendered = renderer().render(composite, viewer)
        val children = rendered.children()
        // 3 parts + 2 separators = 5 children
        assertThat(children).hasSize(5)
        assertThat((children[0] as TextComponent).content()).isEqualTo("a.one")
        assertThat((children[2] as TextComponent).content()).isEqualTo("a.two")
        assertThat((children[4] as TextComponent).content()).isEqualTo("a.three")
        // Separators are spacer fallbacks (ASCII spaces), not actual parts.
        assertThat((children[1] as TextComponent).content()).isEqualTo(" ")
        assertThat((children[3] as TextComponent).content()).isEqualTo(" ")
    }

    @Test
    fun `Composite with empty parts returns Component empty`() {
        val rendered = renderer().render(
            HudComponent.Composite(parts = emptyList(), spacing = 4),
            viewer,
        )
        assertThat(rendered).isEqualTo(Component.empty())
    }

    @Test
    fun `Spacer with no registered glyph falls back to ASCII spaces`() {
        // 10 pixels / ~4 px per space, ceil → 3 spaces.
        val rendered = renderer().render(HudComponent.Spacer(widthPx = 10), viewer)

        assertThat(rendered).isInstanceOf(TextComponent::class.java)
        assertThat((rendered as TextComponent).content()).isEqualTo("   ")
    }

    @Test
    fun `Spacer prefers a registered space slash widthPx negative-space glyph`() {
        val spacer4 = HudGlyph(
            textureRef = HudGlyphRef("space/4"),
            unicodeChar = '\uE200',
            widthPx = 4,
            heightPx = 1,
        )
        val fonts = fontRegistry(glyphs = listOf(manaIcon, barFill, barEmpty, spacer4))
        val rendered = renderer(fonts = fonts).render(HudComponent.Spacer(widthPx = 4), viewer)

        assertThat(rendered).isInstanceOf(TextComponent::class.java)
        assertThat((rendered as TextComponent).content()).isEqualTo(spacer4.unicodeChar.toString())
        assertThat(rendered.font()).isEqualTo(HudRenderer.ASTERA_FONT_KEY)
    }

    @Test
    fun `renderLayout ActionBar delegates to render of its component`() {
        val layout = HudLayout.ActionBar(
            component = HudComponent.Text(MessageKey("astera.hud.action"), color = null),
        )
        val rendered = renderer().renderLayout(layout, viewer)
        assertThat((rendered as TextComponent).content()).isEqualTo("astera.hud.action")
    }

    @Test
    fun `renderLayout Sidebar returns Component empty because lines are rendered individually`() {
        val layout = HudLayout.Sidebar(
            titleKey = MessageKey("astera.hud.sidebar.title"),
            lines = listOf(HudComponent.Text(MessageKey("a.line"), color = null)),
        )
        assertThat(renderer().renderLayout(layout, viewer)).isEqualTo(Component.empty())
    }

    @Test
    fun `render forwards the viewer player id through to IMessageRenderer`() {
        val messages = FakeMessageRenderer()
        val r = renderer(messages = messages)
        r.render(HudComponent.Text(MessageKey("astera.hud.x"), color = null), viewer)

        assertThat(messages.calls).hasSize(1)
        assertThat(messages.calls.single().playerId).isEqualTo(viewer)
        assertThat(messages.calls.single().key).isEqualTo(MessageKey("astera.hud.x"))
    }
}
