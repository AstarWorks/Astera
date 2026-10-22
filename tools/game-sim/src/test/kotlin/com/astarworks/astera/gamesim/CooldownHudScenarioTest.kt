package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.ResourcePackStatusChanged
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.ResourcePackStatus
import com.astarworks.astera.core.domain.hud.status.CooldownHudGlyphs
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skill.SkillTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0197: a cast puts the skill on cooldown; the hotbar sweep is asked for the
 * primary binding's length, and the status line shows one entry per bound input
 * of the held item with the seconds left, until it reads ready again.
 */
class CooldownHudScenarioTest {

    private val frostStaff = ItemId("frost-staff")

    @Test
    fun `a cast starts the sweep and an icon with the seconds left, which turns ready when the cooldown ends`() {
        scenario("hud-cooldown-icons-text") {
            val mage = player("mage", team = "red")
            mage.holds("frost-staff")
            advance(4)

            // Both bindings (right click: frost-shard, sneak+right click: frost-nova) read ready.
            assertThat(sim.hud.statusLineText(mage.id))
                .contains("astera.hud.cooldown.rc.ready")
                .contains("astera.hud.cooldown.sneak-rc.ready")

            mage.rightClick()               // frost-shard: 30 ticks
            mage.rightClick(sneaking = true) // frost-nova: 160 ticks
            advance(4)

            // Only the primary binding (right click) drives the hotbar sweep, for its exact cooldown.
            assertThat(sim.sweeps.of(mage.id).map { it.item to it.ticks }).containsExactly(frostStaff to 30)

            // The HUD records both entries with the remaining whole seconds.
            val line = checkNotNull(sim.hud.statusLineText(mage.id))
            assertThat(line).contains("astera.hud.cooldown.rc.waiting{seconds=2}")
            assertThat(line).contains("astera.hud.cooldown.sneak-rc.waiting{seconds=8}")

            // Right click is ready after 30 ticks, the nova after 160.
            advance(30)
            assertThat(sim.hud.statusLineText(mage.id))
                .contains("astera.hud.cooldown.rc.ready")
                .contains("astera.hud.cooldown.sneak-rc.waiting")
            advance(140)
            assertThat(sim.hud.statusLineText(mage.id))
                .contains("astera.hud.cooldown.rc.ready")
                .contains("astera.hud.cooldown.sneak-rc.ready")
                .doesNotContain("waiting")
        }
    }

    @Test
    fun `with the pack loaded the row is glyphs one row above the HP bar and keeps the line anchored`() {
        scenario("hud-cooldown-icons-glyphs") {
            val mage = player("mage", team = "red")
            mage.holds("frost-staff")
            sim.services.getOrThrow<IDomainEventBus>().publish(ResourcePackStatusChanged(mage.id, ResourcePackStatus.LOADED))
            advance(4)
            val readyWidth = (checkNotNull(sim.hud.statusLine(mage.id)).component as HudComponent.FontText).widthPx

            mage.rightClick()
            advance(4)

            val line = checkNotNull(sim.hud.statusLine(mage.id))
            val text = (line.component as HudComponent.FontText)
            assertThat(text.text).contains(CooldownHudGlyphs.cooling(SkillTrigger.RIGHT_CLICK).toString())
            assertThat(text.text).contains(CooldownHudGlyphs.ready(SkillTrigger.SNEAK_RIGHT).toString())
            assertThat(text.widthPx).isEqualTo(readyWidth)
            assertThat(line.anchorLeftPx).isEqualTo(-91)

            advance(30)
            val after = (checkNotNull(sim.hud.statusLine(mage.id)).component as HudComponent.FontText).text
            assertThat(after).contains(CooldownHudGlyphs.ready(SkillTrigger.RIGHT_CLICK).toString())
            assertThat(after).doesNotContain(CooldownHudGlyphs.cooling(SkillTrigger.RIGHT_CLICK).toString())
        }
    }

    @Test
    fun `the pistol's fire cooldown override and its reload both show as a sweep`() {
        scenario("hud-cooldown-sweep-pistol") {
            val shooter = player("shooter", team = "red")
            shooter.holds("pistol")
            advance(2)

            shooter.rightClick()
            advance(8)
            // cooldown_override_ticks (6) wins over the skill's own 8.
            assertThat(sim.sweeps.of(shooter.id).map { it.ticks }).containsExactly(6)

            shooter.drop(sneaking = true) // reload
            advance(2)
            assertThat(sim.sweeps.of(shooter.id).map { it.ticks }).containsExactly(6, 30)
        }
    }
}
