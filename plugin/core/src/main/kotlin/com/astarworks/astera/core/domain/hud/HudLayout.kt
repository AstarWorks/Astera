package com.astarworks.astera.core.domain.hud

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Sealed layout target — describes WHERE on the player's screen the
 * [HudComponent] tree renders (Wave 5j, ADR-0097).
 *
 * The Wave 6b `PaperHudGateway` maps each layout to its
 * Bukkit/Adventure surface (Action Bar / BossBar / Sidebar / TabList /
 * Title-Subtitle). Sealed so the gateway's `when` is exhaustive.
 *
 * **Vendor-neutral**: nothing here imports `org.bukkit.boss.BossBar` or
 * Adventure types — the [BossBarColor] / [BossBarStyle] enums mirror
 * Adventure's values exactly so the renderer can do a 1-1 map.
 */
public sealed interface HudLayout {

    /**
     * Above-hotbar action-bar slot. Single horizontal line, no wrap.
     *
     * @property ttlTicks how long the message stays visible. Vanilla
     *   action bar fades in ~40 ticks (2s); set higher for persistent
     *   readouts (mana / cooldown). Wave 6b adapter re-sends every
     *   `ttlTicks - 5` to avoid flicker.
     */
    public data class ActionBar(
        public val component: HudComponent,
        public val ttlTicks: Int = 40,
    ) : HudLayout {
        init { require(ttlTicks in 1..1200) { "ActionBar.ttlTicks must be 1..1200, was $ttlTicks" } }
    }

    /**
     * One of up to four stacked boss bars at the top of the player's
     * screen.
     *
     * @property slot 1..4 (Bukkit stacks up to 4 visible BossBars).
     * @property color color of the bar background (Adventure mirror).
     * @property style notch style (Adventure mirror).
     * @property progress 0.0..1.0 fill ratio of the bar itself
     *   (independent of any [HudComponent.ProgressBar] in `component`).
     */
    public data class BossBarSlot(
        public val slot: Int,
        public val component: HudComponent,
        public val color: BossBarColor,
        public val style: BossBarStyle,
        public val progress: Float = 1f,
    ) : HudLayout {
        init {
            require(slot in 1..4) { "BossBarSlot.slot must be 1..4, was $slot" }
            require(progress in 0f..1f) { "BossBarSlot.progress must be 0..1, was $progress" }
        }

        /**
         * Convenience copy helper: same slot/component/color/style with a
         * fresh progress value. Validates the new value via [BossBarSlot]
         * init checks.
         */
        public fun withProgress(newProgress: Float): BossBarSlot =
            copy(progress = newProgress)
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
}

/**
 * Mirror of Adventure's `BossBar.Color`. Renderer maps 1-1 — values must
 * stay in sync if Adventure ever adds new colors (none in 7+ years).
 */
public enum class BossBarColor { PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE }

/**
 * Mirror of Adventure's `BossBar.Overlay`. Renderer maps 1-1.
 */
public enum class BossBarStyle { PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20 }
