package com.astarworks.astera.match.domain.mode

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Sealed parent for Astera's match game modes (Wave 5c).
 *
 * Each concrete mode is its own data class in a sibling file within this
 * package — Kotlin permits sealed subclasses to live in separate files of the
 * same package, so workers landing different modes don't race on a single
 * permits list:
 *
 * - [BattleLoyal] — small-scale arena, first to score threshold wins (this file's worker α).
 * - [BreakCore]  — destroy the enemy core block (this file's worker α).
 * - `CarryTNT`, `CaptureWool`, `StealJewelry` — added by
 *   workers β / γ / δ as separate files.
 *
 * A mode value is **configuration**, not behaviour. Per-mode runtime
 * behaviour lives in the corresponding `<Mode>Listener` under
 * `match.application.usecase.mode.*`, which subscribes to the domain events
 * (`BlockBroken`, `EntityEnteredRegion`, etc.) it cares about.
 *
 * ## Adding a new mode
 *
 * 1. Create `<Mode>.kt` in this package as `public data class <Mode>(...) : GameMode()`.
 * 2. Add a `<Mode>Listener.kt` under `match.application.usecase.mode/`.
 * 3. Wire the listener at Phase C in `AsteraMatchPlugin.kt`.
 *
 * No edits to this file are required when adding a mode.
 */
public sealed class GameMode {
    /** Stable identifier used by YAML configs and `/astera match` commands. */
    public abstract val id: GameModeId

    /** i18n key for the player-facing mode name. */
    public abstract val displayNameKey: MessageKey

    /** i18n key for the short "how to win" description shown in the match HUD. */
    public abstract val descriptionKey: MessageKey
}
