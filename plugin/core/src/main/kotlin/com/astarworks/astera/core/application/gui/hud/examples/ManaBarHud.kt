package com.astarworks.astera.core.application.gui.hud.examples

import com.astarworks.astera.core.application.gui.hud.hudLayout
import com.astarworks.astera.core.domain.hud.BossBarColor
import com.astarworks.astera.core.domain.hud.BossBarStyle
import com.astarworks.astera.core.domain.hud.HudLayout

/**
 * Reference HUD — mana bar (Wave 5j Worker δ, ADR-0097).
 *
 * Renders the player's current mana as a BossBar in slot 1 (the top slot
 * by convention; the other 3 are reserved for stamina / boss fight /
 * objective progress in later waves). The bar uses MC's notched-10 style
 * so each filled tenth maps visually to a unit of cost — most spells in
 * Wave 5n cost a round percentage.
 *
 * The bar's *title* component is built from three primitives:
 *  1. mana icon glyph (`hud/mana/icon` — a small droplet/orb defined in
 *     the resource pack, see `content/hud/mana-bar.json`),
 *  2. 2-px spacer for breathing room,
 *  3. i18n value text. The MessageKey `astera.hud.mana.value` is resolved
 *     by `IMessageRenderer` in the renderer (Wave 5j Worker γ) which
 *     substitutes the `current` / `max` placeholders from the gateway
 *     call's `placeholders` map.
 *
 * **Visibility window**: this HUD is driven by the combat plugin during
 * the ACTIVE match phase. Lobby / spectator phases must clear it via
 * `IHudGateway.clearFor(playerId, HudRegion.BOSS_BAR_SLOT_1)`.
 *
 * **Why not ActionBar?**: ActionBar already carries the cooldown
 * indicator (the [starCounterHud] uses it for collection feedback) and
 * fades after [HudLayout.ActionBar.ttlTicks]. Mana needs to be persistent
 * during combat, which is the BossBar's contract.
 *
 * @param currentMana current mana, must be `>= 0`.
 * @param maxMana max mana, must be `>= 0`. When `0`, the progress is
 *   clamped to `0f` rather than producing `NaN` — a "no mana pool" state
 *   (e.g. spectator) renders an empty bar instead of crashing.
 * @return single-layout HUD describing the BossBar slot.
 */
public fun manaBarHud(currentMana: Int, maxMana: Int): HudLayout {
    require(currentMana >= 0) { "manaBarHud.currentMana must be >= 0, was $currentMana" }
    require(maxMana >= 0) { "manaBarHud.maxMana must be >= 0, was $maxMana" }
    val progress = if (maxMana == 0) {
        0f
    } else {
        (currentMana.toFloat() / maxMana.toFloat()).coerceIn(0f, 1f)
    }
    return hudLayout {
        bossBar(
            slot = 1,
            color = BossBarColor.BLUE,
            style = BossBarStyle.NOTCHED_10,
            progress = progress,
        ) {
            composite(spacing = 2) {
                glyph("hud/mana/icon", widthPx = 10)
                spacer(2)
                text("astera.hud.mana.value")
            }
        }
    }
}
