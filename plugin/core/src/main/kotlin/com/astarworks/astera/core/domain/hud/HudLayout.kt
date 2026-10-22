package com.astarworks.astera.core.domain.hud

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Sealed layout target — describes WHERE on the player's screen the
 * [HudComponent] tree renders (Wave 5j, ADR-0097).
 *
 * The Wave 6b `PaperHudGateway` maps each layout to its
 * Bukkit/Adventure surface (Action Bar / Sidebar / TabList /
 * Title-Subtitle / status line). Sealed so the gateway's `when` is exhaustive.
 * There is deliberately **no boss-bar layout** (F2-2, ADR-0194): energy is the
 * food bar, ammo is on the status line, reload progress is an item cooldown.
 *
 * **Vendor-neutral**: nothing here imports Bukkit or Adventure types.
 */
public sealed interface HudLayout {

    /**
     * Above-hotbar action-bar slot. Single horizontal line, no wrap.
     *
     * @property ttlTicks how long the message stays visible. Vanilla
     *   action bar fades in ~40 ticks (2s); set higher for persistent
     *   readouts (mana / cooldown). Wave 6b adapter re-sends every
     *   `ttlTicks - 5` to avoid flicker.
     * @property priority arbitration between producers sharing the action
     *   bar (see [HudChannelPolicy]): 0 = persistent readout, >= 1 = toast
     *   that must stay visible for its TTL.
     */
    public data class ActionBar(
        public val component: HudComponent,
        public val ttlTicks: Int = 40,
        public val priority: Int = 0,
    ) : HudLayout {
        init {
            require(ttlTicks in 1..1200) { "ActionBar.ttlTicks must be 1..1200, was $ttlTicks" }
            require(priority >= 0) { "ActionBar.priority must be >= 0, was $priority" }
        }
    }

    /**
     * Right-side sidebar (Bukkit Scoreboard objective, DISPLAY=SIDEBAR).
     *
     * @property titleKey i18n key for the sidebar's header line.
     * @property lines body lines, top-to-bottom. 1..15 entries; vanilla
     *   sidebar caps visible lines at 15 (16 with header).
     */
    public data class Sidebar(
        public val titleKey: MessageKey,
        public val lines: List<HudComponent>,
    ) : HudLayout {
        init {
            require(lines.size in 1..15) { "Sidebar.lines size must be 1..15, was ${lines.size}" }
        }
    }

    /**
     * Player-list (TAB key) overlay header and/or footer. Either side
     * may be `null` to leave that part untouched by this layout.
     */
    public data class TabList(
        public val header: HudComponent? = null,
        public val footer: HudComponent? = null,
    ) : HudLayout

    /**
     * Title / subtitle overlay (large centered fade-in text).
     *
     * @property fadeInTicks ticks the title fades in over.
     * @property stayTicks ticks the title is fully visible.
     * @property fadeOutTicks ticks the title fades out over.
     * @property title optional large main-title line shown above [component]
     *   (the death screen's "You died"); `null` renders an empty main title.
     *
     * The Wave 6b adapter calls
     * `Player.showTitle(Title.title(empty, sub, Title.Times.times(...)))`.
     * All three are in MC ticks (20 ticks = 1s). At least one must be > 0
     * or the title never shows.
     */
    public data class Subtitle(
        public val component: HudComponent,
        public val fadeInTicks: Int = 10,
        public val stayTicks: Int = 60,
        public val fadeOutTicks: Int = 10,
        public val title: HudComponent? = null,
    ) : HudLayout {
        init {
            require(fadeInTicks >= 0) { "Subtitle.fadeInTicks must be >= 0, was $fadeInTicks" }
            require(stayTicks >= 0) { "Subtitle.stayTicks must be >= 0, was $stayTicks" }
            require(fadeOutTicks >= 0) { "Subtitle.fadeOutTicks must be >= 0, was $fadeOutTicks" }
            require(fadeInTicks + stayTicks + fadeOutTicks > 0) {
                "Subtitle total duration (fadeIn+stay+fadeOut) must be > 0"
            }
        }
    }

    /**
     * The persistent status line (HP bar), owned by `HpStatusHudService` (ADR-0182).
     *
     * It is not a screen surface of its own: the gateway composites it into the
     * action bar line together with whatever readout / toast is live there, so
     * the HP bar survives toasts and the one-owner rule of ADR-0177 still holds
     * (the action bar's *content* has owners, the *status line* has exactly one).
     *
     * @property component what to draw. [HudComponent.FontText] is the glyph
     *   form (resource pack loaded); anything else is the plain-text fallback
     *   and is simply prefixed to the readout.
     * @property anchorLeftPx for a [HudComponent.FontText] component: where its
     *   left edge must land, in pixels from the screen centre (negative = left).
     *   The gateway cancels the centring of the action bar around it. `null`
     *   for non-glyph components.
     */
    public data class StatusLine(
        public val component: HudComponent,
        public val anchorLeftPx: Int? = null,
    ) : HudLayout {
        init {
            require((component is HudComponent.FontText) == (anchorLeftPx != null)) {
                "StatusLine.anchorLeftPx must be set exactly when the component is FontText"
            }
        }
    }
}
