package com.astarworks.astera.core.adapter.api.hud

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Stable identifier for an [IScoreboard] or [ITeamScoreboard] registered with
 * [IScoreboardManager].
 *
 * Naming rule: lowercase kebab-case with optional dot-separated segments
 * (`match-sidebar`, `hud.scoreboard.match.scores`, `xp-progress`). Validated
 * at construction so typos surface immediately at the call site — same shape
 * as [com.astarworks.astera.core.domain.item.ItemId] and
 * [com.astarworks.astera.core.domain.i18n.MessageKey].
 *
 * The id space is shared between line-style ([IScoreboard]) and team-style
 * ([ITeamScoreboard]) boards: [IScoreboardManager.remove] takes a single
 * [ScoreboardId] and clears whichever flavor is registered.
 */
@JvmInline
public value class ScoreboardId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "ScoreboardId must be lower kebab/dot-case (a-z, 0-9, -, .): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*(\\.[a-z0-9-]+)*$")
    }
}

/**
 * A rendered sidebar line.
 *
 * Vanilla Minecraft's sidebar shows at most 15 lines (`index` 0..14). The
 * `index` is the visual slot: 0 is the bottom line, 14 the top. [rendered] is
 * the i18n-resolved, placeholder-substituted text ready for display.
 *
 * The Wave 5b in-memory fake stores [rendered] as the naive substitution of
 * `{key}` → `value` over the [MessageKey]'s key-string itself (see
 * [com.astarworks.astera.testfixtures.FakeScoreboardManager]). The Wave 6
 * Paper adapter (ADR-0031) replaces that with MiniMessage rendering through
 * `IMessageRenderer` so each viewer sees their locale-specific translation.
 */
public data class ScoreboardLine(
    public val index: Int,
    public val rendered: String,
) {
    init {
        require(index in 0..14) {
            "ScoreboardLine.index must be in 0..14 (Minecraft sidebar limit): $index"
        }
    }
}

/**
 * Vendor-neutral handle for a line-style sidebar scoreboard.
 *
 * Per ADR-0001/0002 this lives in `core.adapter.api` and stays free of
 * Bukkit / Adventure imports. The Paper/Folia implementation renders through
 * [PacketSink] without creating a platform scoreboard (ADR-0260). The in-memory
 * [com.astarworks.astera.testfixtures.FakeScoreboardManager] implements the
 * same interface for application-layer tests.
 *
 * **Per-line model.** Each line is identified by an [Int] slot in `0..14`
 * matching the vanilla sidebar's 15-row limit. [addLine] inserts or
 * replaces; [removeLine] is no-op-when-absent so callers can clear blindly.
 *
 * **i18n resolution timing.** [setTitle] / [addLine] take a [MessageKey] +
 * placeholders. The fake renders eagerly at add-time using
 * naive `{name}` substitution over the key-string for test verifiability.
 * The Wave 6 Paper impl renders per-viewer at draw-time via
 * `IMessageRenderer` so each player sees their locale.
 *
 * **Threading.** Production models serialize mutations. The packet sink owns
 * each viewer's client state on their Entity scope. The headless fake is unsynchronized.
 *
 * **Why [title] is not a `var`.** Adventure-style API consistency with
 * [IBossBar] led to mutable fields, but [setTitle] gives the Paper adapter
 * a single hook to push title-update packets when the value changes. The
 * `val` exposes the *current* title for read-only callers.
 */
public interface IScoreboard {
    /** Stable identifier the board was created with. */
    public val id: ScoreboardId

    /**
     * i18n key for the sidebar title. Resolved by the renderer at draw time
     * so the displayed text follows each viewer's locale. Use [setTitle] to
     * change it — the [val] exposes the current value for inspection only.
     */
    public val title: MessageKey

    /** Replace the sidebar title. */
    public fun setTitle(title: MessageKey)

    /**
     * Add or replace the line at [index].
     *
     * The line's rendered string is built by substituting `{name}` tokens
     * inside [text]'s key-string with the matching [placeholders] entry.
     * Unknown placeholders are left as `{name}` literals (Wave 6 will swap
     * to MiniMessage + `IMessageRenderer` resolution).
     *
     * @throws IllegalArgumentException if [index] is outside `0..14`.
     */
    public fun addLine(index: Int, text: MessageKey, placeholders: Map<String, String> = emptyMap())

    /** Remove the line at [index]. No-op if no line is present there. */
    public fun removeLine(index: Int)

    /**
     * Current lines as an immutable snapshot, sorted by [ScoreboardLine.index]
     * ascending (slot 0 first).
     */
    public fun lines(): List<ScoreboardLine>
}
