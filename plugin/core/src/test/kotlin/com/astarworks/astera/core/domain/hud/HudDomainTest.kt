package com.astarworks.astera.core.domain.hud

import com.astarworks.astera.core.domain.i18n.MessageKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Wave 5j, ADR-0097 — HUD domain construction / validation tests.
 *
 * Pure Kotlin; no Bukkit / Paper / Adventure harness. Mirrors the format
 * of MessageKeyTest / DialogTest. Covers the construction-time invariants
 * for every public type in `core.domain.hud.*` plus the helpers added in
 * Worker α's refinement.
 *
 * Char literals use `\uXXXX` escapes throughout for reviewability; raw
 * BMP-PUA glyphs would render as tofu in IDE / GitHub diff.
 */
class HudDomainTest {

    private val promptKey = MessageKey("astera.hud.resourcepack.prompt")
    private val titleKey = MessageKey("astera.hud.sidebar.title")
    private val fillRef = HudGlyphRef("hud/mana/fill")
    private val emptyRef = HudGlyphRef("hud/mana/empty")

    // ---------------------------------------------------------------- HudGlyphRef

    @Test
    fun `HudGlyphRef accepts lower-kebab-case with optional slash segments`() {
        assertThat(HudGlyphRef("mana").value).isEqualTo("mana")
        assertThat(HudGlyphRef("hud/mana/fill").value).isEqualTo("hud/mana/fill")
        assertThat(HudGlyphRef("currency/star-small").value).isEqualTo("currency/star-small")
        assertThat(HudGlyphRef("a").value).isEqualTo("a")
        assertThat(HudGlyphRef("hud/mana/bar-fill-2").value).isEqualTo("hud/mana/bar-fill-2")
    }

    @Test
    fun `HudGlyphRef rejects uppercase, empty, leading digit, trailing slash, double slash`() {
        assertThatThrownBy { HudGlyphRef("Mana") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudGlyphRef("") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudGlyphRef("hud/") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudGlyphRef("/hud") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudGlyphRef("hud//mana") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudGlyphRef("1mana") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudGlyphRef("hud mana") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---------------------------------------------------------------- HudColor

    @Test
    fun `HudColor fromHex round-trips RRGGBB`() {
        val red = HudColor.fromHex("FF0000")
        assertThat(red.redInt).isEqualTo(0xFF)
        assertThat(red.greenInt).isEqualTo(0x00)
        assertThat(red.blueInt).isEqualTo(0x00)
        assertThat(red.alphaInt).isEqualTo(0xFF) // default opaque
        assertThat(red.toHex()).isEqualTo("FF0000")

        // Round-trip with alpha
        val half = HudColor.fromHex("11223380")
        assertThat(half.redInt).isEqualTo(0x11)
        assertThat(half.greenInt).isEqualTo(0x22)
        assertThat(half.blueInt).isEqualTo(0x33)
        assertThat(half.alphaInt).isEqualTo(0x80)
        assertThat(half.toHex()).isEqualTo("11223380")
    }

    @Test
    fun `HudColor fromHex tolerates leading hash and lowercase`() {
        val a = HudColor.fromHex("#ff00aa")
        val b = HudColor.fromHex("FF00AA")
        assertThat(a).isEqualTo(b)
        assertThat(a.toHex()).isEqualTo("FF00AA")
    }

    @Test
    fun `HudColor fromHex rejects bad length and non-hex chars`() {
        assertThatThrownBy { HudColor.fromHex("FF00") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudColor.fromHex("FF0000FF00") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudColor.fromHex("XYZXYZ") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudColor.fromHex("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `HudColor rgba rejects out of range channels`() {
        assertThatThrownBy { HudColor.rgba(-1, 0, 0, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudColor.rgba(256, 0, 0, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudColor.rgba(0, 0, 0, 999) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `HudColor static palette uses expected hex values`() {
        assertThat(HudColor.WHITE.toHex()).isEqualTo("FFFFFF")
        assertThat(HudColor.BLACK.toHex()).isEqualTo("000000")
        assertThat(HudColor.RED.toHex()).isEqualTo("FF5555")
        assertThat(HudColor.GREEN.toHex()).isEqualTo("55FF55")
        assertThat(HudColor.BLUE.toHex()).isEqualTo("5555FF")
        assertThat(HudColor.YELLOW.toHex()).isEqualTo("FFFF55")
        assertThat(HudColor.GRAY.toHex()).isEqualTo("AAAAAA")
        assertThat(HudColor.GOLD.toHex()).isEqualTo("FFAA00")
        assertThat(HudColor.TRANSPARENT.alphaInt).isEqualTo(0x00)
        assertThat(HudColor.TRANSPARENT.toHex()).isEqualTo("00000000")
    }

    // ---------------------------------------------------------------- HudComponent.Text

    @Test
    fun `HudComponent Text accepts color override and null default`() {
        val plain = HudComponent.Text(MessageKey("astera.hud.mana.label"))
        assertThat(plain.color).isNull()

        val red = HudComponent.Text(MessageKey("astera.hud.mana.label"), color = HudColor.RED)
        assertThat(red.color).isEqualTo(HudColor.RED)
    }

    // ---------------------------------------------------------------- HudComponent.Glyph

    @Test
    fun `HudComponent Glyph accepts valid widthPx and rejects out of range`() {
        val g = HudComponent.Glyph(textureRef = fillRef, widthPx = 16, ascent = 8)
        assertThat(g.widthPx).isEqualTo(16)
        assertThat(g.ascent).isEqualTo(8)

        assertThatThrownBy { HudComponent.Glyph(fillRef, widthPx = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudComponent.Glyph(fillRef, widthPx = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudComponent.Glyph(fillRef, widthPx = 300) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---------------------------------------------------------------- HudComponent.ProgressBar

    @Test
    fun `HudComponent ProgressBar currentSegments returns rounded count`() {
        val empty = HudComponent.ProgressBar(0f, fillRef, emptyRef, widthSegments = 20)
        assertThat(empty.currentSegments()).isEqualTo(0)

        val half = HudComponent.ProgressBar(0.5f, fillRef, emptyRef, widthSegments = 20)
        assertThat(half.currentSegments()).isEqualTo(10)

        val full = HudComponent.ProgressBar(1f, fillRef, emptyRef, widthSegments = 20)
        assertThat(full.currentSegments()).isEqualTo(20)

        // Rounding sanity: 0.74 * 20 = 14.8 → 15
        val pct74 = HudComponent.ProgressBar(0.74f, fillRef, emptyRef, widthSegments = 20)
        assertThat(pct74.currentSegments()).isEqualTo(15)
    }

    @Test
    fun `HudComponent ProgressBar rejects percent out of 0 to 1`() {
        assertThatThrownBy { HudComponent.ProgressBar(-0.1f, fillRef, emptyRef) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudComponent.ProgressBar(1.1f, fillRef, emptyRef) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            HudComponent.ProgressBar(0.5f, fillRef, emptyRef, widthSegments = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            HudComponent.ProgressBar(0.5f, fillRef, emptyRef, widthSegments = 101)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---------------------------------------------------------------- HudComponent.Composite

    @Test
    fun `HudComponent Composite of factory builds with default spacing zero`() {
        val a = HudComponent.Text(MessageKey("astera.hud.label.a"))
        val b = HudComponent.Glyph(fillRef, widthPx = 8)
        val c = HudComponent.Spacer(2)

        val comp = HudComponent.Composite.of(a, b, c)
        assertThat(comp.parts).containsExactly(a, b, c)
        assertThat(comp.spacing).isEqualTo(0)

        // overloaded factory with spacing
        val spaced = HudComponent.Composite.of(spacing = 4, a, b)
        assertThat(spaced.parts).containsExactly(a, b)
        assertThat(spaced.spacing).isEqualTo(4)
    }

    // ---------------------------------------------------------------- HudComponent.Spacer

    @Test
    fun `HudComponent Spacer allows negative width for reverse advance`() {
        val pos = HudComponent.Spacer.px(8)
        assertThat(pos.widthPx).isEqualTo(8)

        // Negative-space font 技法: reverse-advance is a feature, not a bug.
        val neg = HudComponent.Spacer.px(-16)
        assertThat(neg.widthPx).isEqualTo(-16)

        val zero = HudComponent.Spacer(0)
        assertThat(zero.widthPx).isEqualTo(0)
    }

    // ---------------------------------------------------------------- HudLayout.ActionBar

    @Test
    fun `HudLayout ActionBar defaults ttlTicks to 40 and accepts overrides`() {
        val text = HudComponent.Text(MessageKey("astera.hud.actionbar.message"))
        val def = HudLayout.ActionBar(text)
        assertThat(def.ttlTicks).isEqualTo(40)

        val long = HudLayout.ActionBar(text, ttlTicks = 200)
        assertThat(long.ttlTicks).isEqualTo(200)

        assertThatThrownBy { HudLayout.ActionBar(text, ttlTicks = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudLayout.ActionBar(text, ttlTicks = 1201) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---------------------------------------------------------------- HudLayout.BossBarSlot

    @Test
    fun `HudLayout BossBarSlot rejects slot 0 and slot 5`() {
        val text = HudComponent.Text(MessageKey("astera.hud.boss.label"))
        assertThatThrownBy {
            HudLayout.BossBarSlot(
                slot = 0,
                component = text,
                color = BossBarColor.RED,
                style = BossBarStyle.PROGRESS,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            HudLayout.BossBarSlot(
                slot = 5,
                component = text,
                color = BossBarColor.RED,
                style = BossBarStyle.PROGRESS,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        // 1 and 4 are the boundary valid slots
        val one = HudLayout.BossBarSlot(1, text, BossBarColor.RED, BossBarStyle.PROGRESS)
        assertThat(one.slot).isEqualTo(1)
        val four = HudLayout.BossBarSlot(4, text, BossBarColor.RED, BossBarStyle.PROGRESS)
        assertThat(four.slot).isEqualTo(4)
    }

    @Test
    fun `HudLayout BossBarSlot withProgress returns updated copy`() {
        val text = HudComponent.Text(MessageKey("astera.hud.boss.label"))
        val base = HudLayout.BossBarSlot(
            slot = 2,
            component = text,
            color = BossBarColor.BLUE,
            style = BossBarStyle.NOTCHED_10,
            progress = 1f,
        )
        val updated = base.withProgress(0.25f)
        assertThat(updated.progress).isEqualTo(0.25f)
        assertThat(updated.slot).isEqualTo(2)
        assertThat(updated.color).isEqualTo(BossBarColor.BLUE)
        assertThat(updated.style).isEqualTo(BossBarStyle.NOTCHED_10)
        assertThat(base.progress).isEqualTo(1f) // original untouched

        // withProgress still enforces 0..1
        assertThatThrownBy { base.withProgress(-0.1f) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { base.withProgress(1.5f) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---------------------------------------------------------------- HudLayout.Sidebar

    @Test
    fun `HudLayout Sidebar accepts 1 to 15 lines and rejects 0 or 16`() {
        val one = HudLayout.Sidebar(
            titleKey = titleKey,
            lines = listOf(HudComponent.Text(MessageKey("astera.hud.sidebar.line1"))),
        )
        assertThat(one.lines).hasSize(1)

        val fifteen = HudLayout.Sidebar(
            titleKey = titleKey,
            lines = (1..15).map { HudComponent.Text(MessageKey("astera.hud.sidebar.line$it")) },
        )
        assertThat(fifteen.lines).hasSize(15)

        assertThatThrownBy { HudLayout.Sidebar(titleKey, lines = emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            HudLayout.Sidebar(
                titleKey,
                lines = (1..16).map { HudComponent.Text(MessageKey("astera.hud.sidebar.line$it")) },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---------------------------------------------------------------- HudLayout.Subtitle

    @Test
    fun `HudLayout Subtitle rejects all zero durations but allows component-only positives`() {
        val text = HudComponent.Text(MessageKey("astera.hud.subtitle.text"))

        // default values sum > 0
        val def = HudLayout.Subtitle(text)
        assertThat(def.fadeInTicks + def.stayTicks + def.fadeOutTicks).isGreaterThan(0)

        // explicit single non-zero is ok
        val justStay = HudLayout.Subtitle(text, fadeInTicks = 0, stayTicks = 1, fadeOutTicks = 0)
        assertThat(justStay.stayTicks).isEqualTo(1)

        // all zero rejected
        assertThatThrownBy {
            HudLayout.Subtitle(text, fadeInTicks = 0, stayTicks = 0, fadeOutTicks = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)

        // negative individual values rejected
        assertThatThrownBy { HudLayout.Subtitle(text, fadeInTicks = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudLayout.Subtitle(text, stayTicks = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudLayout.Subtitle(text, fadeOutTicks = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---------------------------------------------------------------- HudGlyph

    @Test
    fun `HudGlyph accepts PUA codepoints and rejects out of range`() {
        // E000 = first PUA codepoint
        val first = HudGlyph(fillRef, '\uE000', widthPx = 8, heightPx = 9)
        assertThat(first.unicodeChar.code).isEqualTo(0xE000)

        // F8FF = last PUA codepoint
        val last = HudGlyph(fillRef, '\uF8FF', widthPx = 8, heightPx = 9)
        assertThat(last.unicodeChar.code).isEqualTo(0xF8FF)

        // E000 - 1 = DFFF (high-surrogate area, not PUA) rejected
        assertThatThrownBy { HudGlyph(fillRef, '\uDFFF', widthPx = 8, heightPx = 9) }
            .isInstanceOf(IllegalArgumentException::class.java)
        // F900 = CJK Compat Ideographs, NOT PUA — rejected
        assertThatThrownBy { HudGlyph(fillRef, '\uF900', widthPx = 8, heightPx = 9) }
            .isInstanceOf(IllegalArgumentException::class.java)
        // ASCII A definitely not PUA
        assertThatThrownBy { HudGlyph(fillRef, 'A', widthPx = 8, heightPx = 9) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `HudGlyph rejects bad widthPx and heightPx`() {
        assertThatThrownBy { HudGlyph(fillRef, '\uE000', widthPx = 0, heightPx = 9) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudGlyph(fillRef, '\uE000', widthPx = 257, heightPx = 9) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudGlyph(fillRef, '\uE000', widthPx = 8, heightPx = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudGlyph(fillRef, '\uE000', widthPx = 8, heightPx = 257) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `HudGlyph nextAvailableUnicodeChar returns first unused PUA codepoint`() {
        // Empty used set → first PUA codepoint
        assertThat(HudGlyph.nextAvailableUnicodeChar(emptySet())?.code).isEqualTo(0xE000)

        // E000, E001 used → returns E002
        val used = setOf('\uE000', '\uE001')
        assertThat(HudGlyph.nextAvailableUnicodeChar(used)?.code).isEqualTo(0xE002)

        // Skip a gap: E000 used, E001 not used → returns E001
        val gap = setOf('\uE000', '\uE002', '\uE003')
        assertThat(HudGlyph.nextAvailableUnicodeChar(gap)?.code).isEqualTo(0xE001)

        // Fully exhausted PUA → null
        val full = (HudGlyph.PUA_START..HudGlyph.PUA_END).map { it.toChar() }.toSet()
        assertThat(HudGlyph.nextAvailableUnicodeChar(full)).isNull()
    }

    // ---------------------------------------------------------------- HudHandle

    @Test
    fun `HudHandle rejects blank values`() {
        val ok = HudHandle("hud-12345")
        assertThat(ok.value).isEqualTo("hud-12345")
        assertThat(ok.toString()).isEqualTo("hud-12345")

        assertThatThrownBy { HudHandle("") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudHandle("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HudHandle("\t\n") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---------------------------------------------------------------- ResourcePackSpec

    @Test
    fun `ResourcePackSpec accepts http and https URLs with valid SHA-1`() {
        val sha = "0123456789ABCDEF0123456789abcdef01234567"
        val httpSpec = ResourcePackSpec(
            url = "http://localhost:8080/pack.zip",
            sha1Hash = sha,
            promptKey = promptKey,
        )
        assertThat(httpSpec.url).startsWith("http://")
        assertThat(httpSpec.required).isFalse()

        val httpsSpec = ResourcePackSpec(
            url = "https://cdn.example.com/pack.zip",
            sha1Hash = sha,
            required = true,
            promptKey = promptKey,
        )
        assertThat(httpsSpec.required).isTrue()
    }

    @Test
    fun `ResourcePackSpec rejects URL without http or https scheme`() {
        val sha = "0123456789abcdef0123456789abcdef01234567"
        assertThatThrownBy {
            ResourcePackSpec("ftp://localhost/pack.zip", sha, promptKey = promptKey)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ResourcePackSpec("localhost/pack.zip", sha, promptKey = promptKey)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ResourcePackSpec("", sha, promptKey = promptKey)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ResourcePackSpec rejects bad SHA-1 length and non-hex chars`() {
        val url = "https://cdn.example.com/pack.zip"
        // 39 char
        assertThatThrownBy {
            ResourcePackSpec(url, "0".repeat(39), promptKey = promptKey)
        }.isInstanceOf(IllegalArgumentException::class.java)
        // 41 char
        assertThatThrownBy {
            ResourcePackSpec(url, "0".repeat(41), promptKey = promptKey)
        }.isInstanceOf(IllegalArgumentException::class.java)
        // 40 char but non-hex (G)
        assertThatThrownBy {
            ResourcePackSpec(url, "G".repeat(40), promptKey = promptKey)
        }.isInstanceOf(IllegalArgumentException::class.java)
        // Empty
        assertThatThrownBy {
            ResourcePackSpec(url, "", promptKey = promptKey)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ResourcePackSpec withPromptKey returns copy with updated key only`() {
        val sha = "0123456789abcdef0123456789abcdef01234567"
        val base = ResourcePackSpec(
            url = "https://cdn.example.com/pack.zip",
            sha1Hash = sha,
            required = true,
            promptKey = MessageKey("astera.hud.resourcepack.prompt.original"),
        )
        val newKey = MessageKey("astera.hud.resourcepack.prompt.updated")
        val updated = base.withPromptKey(newKey)
        assertThat(updated.promptKey).isEqualTo(newKey)
        assertThat(updated.url).isEqualTo(base.url)
        assertThat(updated.sha1Hash).isEqualTo(base.sha1Hash)
        assertThat(updated.required).isEqualTo(base.required)
        // Original untouched
        assertThat(base.promptKey.value).isEqualTo("astera.hud.resourcepack.prompt.original")
    }

    // ---------------------------------------------------------------- HudRegion enum sanity

    @Test
    fun `HudRegion enum covers every layout target`() {
        // Compile-time exhaustiveness check: every HudLayout subtype has at
        // least one corresponding HudRegion. Hand-asserted because
        // HudLayout.BossBarSlot fans out to 4 regions.
        assertThat(HudRegion.values()).contains(
            HudRegion.ACTION_BAR,
            HudRegion.BOSS_BAR_SLOT_1,
            HudRegion.BOSS_BAR_SLOT_2,
            HudRegion.BOSS_BAR_SLOT_3,
            HudRegion.BOSS_BAR_SLOT_4,
            HudRegion.SIDEBAR,
            HudRegion.TAB_LIST_HEADER,
            HudRegion.TAB_LIST_FOOTER,
            HudRegion.SUBTITLE,
        )
    }
}
