package com.astarworks.astera.core.application.gui.hud

import com.astarworks.astera.core.application.gui.hud.examples.starCounterHud
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * DSL behaviour tests for [HudLayoutBuilder] (Wave 5j Worker δ, ADR-0097).
 *
 * Two-level coverage:
 *  1. DSL primitives — every layout (`actionBar` / `sidebar` /
 *     `tabList` / `subtitle`) + every component primitive (`text` /
 *     `glyph` / `progressBar` / `composite` / `spacer`) + required-field
 *     guards (empty block, double layout).
 *  2. Example HUDs — `starCounterHud` exercise the same
 *     code path the production combat / economy plugins will use in
 *     Wave 5n / 5q, so any DSL signature drift surfaces here first.
 *
 * No Paper / Bukkit / Adventure harness. Domain types only.
 */
class HudLayoutBuilderTest {

    // ---------------------------------------------------------------------
    // 1. Layout primitives
    // ---------------------------------------------------------------------

    @Test
    fun `actionBar builds ActionBar layout with single Text and ttl`() {
        val layout = hudLayout {
            actionBar(ttlTicks = 40) { text("astera.hud.foo") }
        }

        assertThat(layout).isInstanceOf(HudLayout.ActionBar::class.java)
        val actionBar = layout as HudLayout.ActionBar
        assertThat(actionBar.ttlTicks).isEqualTo(40)
        assertThat(actionBar.component).isInstanceOf(HudComponent.Text::class.java)
        assertThat((actionBar.component as HudComponent.Text).messageKey.value)
            .isEqualTo("astera.hud.foo")
    }

    @Test
    fun `actionBar uses default ttl of 40 when omitted`() {
        val layout = hudLayout {
            actionBar { text("astera.hud.foo") }
        } as HudLayout.ActionBar

        assertThat(layout.ttlTicks).isEqualTo(40)
    }

    @Test
    fun `sidebar builds Sidebar with title key and ordered lines`() {
        val layout = hudLayout {
            sidebar("astera.hud.scoreboard.title") {
                line { text("astera.hud.scoreboard.row-a") }
                line { text("astera.hud.scoreboard.row-b") }
                line { text("astera.hud.scoreboard.row-c") }
            }
        }

        assertThat(layout).isInstanceOf(HudLayout.Sidebar::class.java)
        val sidebar = layout as HudLayout.Sidebar
        assertThat(sidebar.titleKey.value).isEqualTo("astera.hud.scoreboard.title")
        assertThat(sidebar.lines).hasSize(3)
        assertThat(sidebar.lines.map { (it as HudComponent.Text).messageKey.value })
            .containsExactly(
                "astera.hud.scoreboard.row-a",
                "astera.hud.scoreboard.row-b",
                "astera.hud.scoreboard.row-c",
            )
    }

    @Test
    fun `tabList builds TabList with both header and footer`() {
        val layout = hudLayout {
            tabList {
                header { text("astera.hud.tab.header") }
                footer { text("astera.hud.tab.footer") }
            }
        }

        assertThat(layout).isInstanceOf(HudLayout.TabList::class.java)
        val tabList = layout as HudLayout.TabList
        assertThat((tabList.header as HudComponent.Text).messageKey.value)
            .isEqualTo("astera.hud.tab.header")
        assertThat((tabList.footer as HudComponent.Text).messageKey.value)
            .isEqualTo("astera.hud.tab.footer")
    }

    @Test
    fun `tabList builds TabList with only header set`() {
        val layout = hudLayout {
            tabList { header { text("astera.hud.tab.header") } }
        } as HudLayout.TabList

        assertThat(layout.header).isNotNull
        assertThat(layout.footer).isNull()
    }

    @Test
    fun `subtitle builds Subtitle with custom fade and stay times`() {
        val layout = hudLayout {
            subtitle(fadeIn = 5, stay = 30, fadeOut = 5) {
                text("astera.hud.subtitle.go")
            }
        }

        assertThat(layout).isInstanceOf(HudLayout.Subtitle::class.java)
        val sub = layout as HudLayout.Subtitle
        assertThat(sub.fadeInTicks).isEqualTo(5)
        assertThat(sub.stayTicks).isEqualTo(30)
        assertThat(sub.fadeOutTicks).isEqualTo(5)
        assertThat((sub.component as HudComponent.Text).messageKey.value)
            .isEqualTo("astera.hud.subtitle.go")
    }

    @Test
    fun `subtitle defaults to vanilla MC title timings`() {
        val layout = hudLayout {
            subtitle { text("astera.hud.subtitle.go") }
        } as HudLayout.Subtitle

        assertThat(layout.fadeInTicks).isEqualTo(10)
        assertThat(layout.stayTicks).isEqualTo(60)
        assertThat(layout.fadeOutTicks).isEqualTo(10)
    }

    // ---------------------------------------------------------------------
    // 2. Component primitives
    // ---------------------------------------------------------------------

    @Test
    fun `composite of glyph + spacer + text builds Composite with three parts`() {
        val layout = hudLayout {
            actionBar {
                composite(spacing = 2) {
                    glyph("hud/icon-a")
                    spacer(2)
                    text("astera.hud.label")
                }
            }
        } as HudLayout.ActionBar

        assertThat(layout.component).isInstanceOf(HudComponent.Composite::class.java)
        val composite = layout.component as HudComponent.Composite
        assertThat(composite.spacing).isEqualTo(2)
        assertThat(composite.parts).hasSize(3)
        assertThat(composite.parts[0]).isInstanceOf(HudComponent.Glyph::class.java)
        assertThat(composite.parts[1]).isInstanceOf(HudComponent.Spacer::class.java)
        assertThat(composite.parts[2]).isInstanceOf(HudComponent.Text::class.java)
    }

    @Test
    fun `progressBar primitive builds ProgressBar with percent and segments`() {
        val layout = hudLayout {
            actionBar {
                progressBar(
                    percent = 0.75f,
                    fillRef = "hud/mana/bar-fill",
                    emptyRef = "hud/mana/bar-empty",
                    segments = 10,
                )
            }
        } as HudLayout.ActionBar

        val bar = layout.component as HudComponent.ProgressBar
        assertThat(bar.percent).isEqualTo(0.75f)
        assertThat(bar.widthSegments).isEqualTo(10)
        assertThat(bar.fillGlyph.value).isEqualTo("hud/mana/bar-fill")
        assertThat(bar.emptyGlyph.value).isEqualTo("hud/mana/bar-empty")
        assertThat(bar.currentSegments()).isEqualTo(8)
    }

    @Test
    fun `single primitive at top level is returned unwrapped (no Composite)`() {
        val layout = hudLayout { actionBar { text("astera.hud.foo") } } as HudLayout.ActionBar

        assertThat(layout.component).isInstanceOf(HudComponent.Text::class.java)
        assertThat(layout.component).isNotInstanceOf(HudComponent.Composite::class.java)
    }

    @Test
    fun `text with color override carries the HudColor on the Text primitive`() {
        val layout = hudLayout {
            actionBar { text("astera.hud.foo", color = HudColor.RED) }
        } as HudLayout.ActionBar

        val text = layout.component as HudComponent.Text
        assertThat(text.color).isEqualTo(HudColor.RED)
    }

    // ---------------------------------------------------------------------
    // 3. Guard rails — error paths
    // ---------------------------------------------------------------------

    @Test
    fun `empty hudLayout throws a meaningful error pointing to the missing call`() {
        assertThatThrownBy { hudLayout { } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("hudLayout")
            .hasMessageContaining("actionBar")
    }

    @Test
    fun `setting two layouts in one block fails fast with explanatory message`() {
        assertThatThrownBy {
            hudLayout {
                actionBar { text("astera.hud.foo") }
                actionBar { text("astera.hud.bar") }
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("already has a layout")
            .hasMessageContaining("HudHandle")
    }

    @Test
    fun `mixing two different layouts in one block also fails fast`() {
        assertThatThrownBy {
            hudLayout {
                actionBar { text("astera.hud.foo") }
                subtitle { text("astera.hud.bar") }
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("subtitle")
    }

    @Test
    fun `component block with zero primitives throws meaningful error`() {
        assertThatThrownBy {
            hudLayout { actionBar { } }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("at least one")
            .hasMessageContaining("text")
            .hasMessageContaining("glyph")
    }

    @Test
    fun `glyph with invalid ref pattern surfaces the HudGlyphRef validator`() {
        assertThatThrownBy {
            hudLayout { actionBar { glyph("INVALID/Foo!") } }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("HudGlyphRef")
    }

    // ---------------------------------------------------------------------
    // 4. Example HUDs — production-grade reference
    // ---------------------------------------------------------------------

    @Test
    fun `starCounterHud returns ActionBar with 20-tick TTL`() {
        val layout = starCounterHud(starCount = 42)

        assertThat(layout).isInstanceOf(HudLayout.ActionBar::class.java)
        val actionBar = layout as HudLayout.ActionBar
        assertThat(actionBar.ttlTicks).isEqualTo(20)
    }

    @Test
    fun `starCounterHud composite contains star icon + spacer + count text`() {
        val layout = starCounterHud(7) as HudLayout.ActionBar
        val composite = layout.component as HudComponent.Composite

        assertThat(composite.parts).hasSize(3)
        assertThat((composite.parts[0] as HudComponent.Glyph).textureRef.value)
            .isEqualTo("hud/currency/star-icon")
        assertThat((composite.parts[1] as HudComponent.Spacer).widthPx).isEqualTo(2)
        assertThat((composite.parts[2] as HudComponent.Text).messageKey.value)
            .isEqualTo("astera.hud.star.count")
    }

    @Test
    fun `starCounterHud accepts large Long values without overflow`() {
        // Economy ledger uses Long; verify the DSL keeps the value intact
        // through the conversion to MessageKey-resolved text (resolution
        // happens at render time so the count itself isn't on the layout —
        // but the call must not throw).
        val layout = starCounterHud(starCount = Long.MAX_VALUE) as HudLayout.ActionBar
        assertThat(layout.ttlTicks).isEqualTo(20)
    }

    @Test
    fun `starCounterHud rejects negative star count`() {
        assertThatThrownBy { starCounterHud(starCount = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("starCount")
    }
}
