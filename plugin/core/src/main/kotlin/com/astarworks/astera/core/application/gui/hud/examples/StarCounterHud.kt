package com.astarworks.astera.core.application.gui.hud.examples

import com.astarworks.astera.core.application.gui.hud.hudLayout
import com.astarworks.astera.core.domain.hud.HudLayout

/**
 * Reference HUD — star counter (Wave 5j Worker δ, ADR-0097).
 *
 * Flashes the player's star (Wave 5q in-game currency) count on the
 * ActionBar for ~1 second after the wallet changes. Fire-and-forget by
 * design: the Wave 5j gateway re-sends the layout on every coin pickup;
 * the client TTL handles the fade-out automatically.
 *
 * Composition:
 *  1. star icon glyph (`hud/currency/star-icon` — a 10-px PUA glyph in
 *     the resource pack, see `content/hud/star-counter.json`),
 *  2. 2-px spacer,
 *  3. i18n count text. The MessageKey `astera.hud.star.count` is resolved
 *     at render time with the `count` placeholder filled by the gateway
 *     call site.
 *
 * **Why ttlTicks = 20**: 1 second is the standard "transient feedback"
 * window. Star pickups happen rapidly during combat and a longer TTL
 * causes the HUD to feel "stuck on" between pickups. 20 ticks also
 * matches the cooldown indicator's TTL in Wave 5n, so the ActionBar feels
 * consistent across feedback types.
 *
 * @param starCount the player's current star wallet balance — accepted
 *   as `Long` because the economy domain (Wave 5q) uses `Long` for ledger
 *   amounts to avoid integer overflow on long-running sessions.
 * @return single-layout HUD describing the ActionBar surface.
 */
public fun starCounterHud(starCount: Long): HudLayout {
    require(starCount >= 0) { "starCounterHud.starCount must be >= 0, was $starCount" }
    return hudLayout {
        actionBar(ttlTicks = 20) {
            composite(spacing = 2) {
                glyph("hud/currency/star-icon", widthPx = 10)
                spacer(2)
                text("astera.hud.star.count")
            }
        }
    }
}
