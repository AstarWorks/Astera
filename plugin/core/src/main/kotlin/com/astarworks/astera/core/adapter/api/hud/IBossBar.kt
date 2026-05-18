package com.astarworks.astera.core.adapter.api.hud

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Stable identifier for an [IBossBar] registered with [IBossBarManager].
 *
 * Naming rule: lowercase kebab-case with optional dot-separated segments
 * (`match-phase`, `hud.bossbar.match.phase`, `xp-progress`). Validated at
 * construction so typos surface immediately at the call site — same shape
 * as [com.astarworks.astera.core.domain.item.ItemId] and
 * [com.astarworks.astera.core.domain.i18n.MessageKey].
 *
 * Distinct from [MessageKey] so a "where is this id used?" search lands on
 * structural id sites, not translation-key sites.
 */
@JvmInline
public value class BossBarId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "BossBarId must be lower kebab/dot-case (a-z, 0-9, -, .): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*(\\.[a-z0-9-]+)*$")
    }
}

/**
 * Bossbar color, mirroring Adventure API's `BossBar.Color`.
 *
 * Enum names match Adventure exactly so the Wave 6 Paper adapter
 * (ADR-0031) can map by name without a translation table.
 */
public enum class BossBarColor {
    PINK,
    BLUE,
    RED,
    GREEN,
    YELLOW,
    PURPLE,
    WHITE,
}

/**
 * Bossbar overlay style, mirroring Adventure API's `BossBar.Overlay`.
 *
 * `PROGRESS` is the smooth filling bar; the `NOTCHED_*` variants split
 * the bar into the indicated number of segments.
 */
public enum class BossBarOverlay {
    PROGRESS,
    NOTCHED_6,
    NOTCHED_10,
    NOTCHED_12,
    NOTCHED_20,
}

/**
 * Bossbar visual flag, mirroring Adventure API's `BossBar.Flag`.
 *
 * Flags compose — a bar can have any subset active at once.
 */
public enum class BossBarFlag {
    DARKEN_SCREEN,
    PLAY_BOSS_MUSIC,
    CREATE_WORLD_FOG,
}

/**
 * Vendor-neutral handle for a Minecraft bossbar.
 *
 * Per ADR-0001/0002 this lives in `core.adapter.api` and stays free of
 * Bukkit / Adventure imports. The Wave 6 Paper implementation (ADR-0031)
 * wraps Adventure's `BossBar` and registers viewers through
 * `Audience#showBossBar`. The in-memory [com.astarworks.astera.testfixtures.FakeBossBarManager]
 * implements the same interface for application-layer tests.
 *
 * **Threading.** Adventure API requires bossbar mutation on the main
 * server thread; production callers must dispatch via `IScheduler` if
 * they touch a bar from an async context. The fake does not enforce
 * this — domain/application tests don't care about server threads.
 *
 * **Reference design.** Wave 6 Match HUD will use a single bossbar
 * keyed `hud.bossbar.match.phase` to surface match phase + remaining
 * time. Combat HP bars and XP progress bars follow the same pattern.
 *
 * Mutation of [flags] goes through [addFlag] / [removeFlag] so the
 * exposed [Set] stays read-only to callers.
 */
public interface IBossBar {
    /** Stable identifier the bar was created with. */
    public val id: BossBarId

    /**
     * i18n key for the bar's title. Resolved by the renderer at draw time
     * so the displayed text follows each viewer's locale.
     */
    public var title: MessageKey

    /**
     * Fill ratio in `[0.0, 1.0]`. Setting a value outside this range
     * throws [IllegalArgumentException] — bossbars have no meaningful
     * "over-full" or "negative" representation.
     */
    public var progress: Double

    /** Bossbar color (Adventure-aligned). */
    public var color: BossBarColor

    /** Bossbar overlay style (Adventure-aligned). */
    public var overlay: BossBarOverlay

    /**
     * Currently-active flags. Read-only view; mutate via [addFlag] /
     * [removeFlag] so impls can react to flag changes (e.g. push a
     * packet update in the Paper adapter).
     */
    public val flags: Set<BossBarFlag>

    /** Add [flag] if not already present. No-op otherwise. */
    public fun addFlag(flag: BossBarFlag)

    /** Remove [flag] if present. No-op otherwise. */
    public fun removeFlag(flag: BossBarFlag)
}
