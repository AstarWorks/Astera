package com.astarworks.astera.core.domain.player

import com.astarworks.astera.core.domain.i18n.LocaleCode

/**
 * Operator choice for skill activation (Wave 5e; RTM README §プレイヤーデータ
 * "操作方法選択").
 *
 * - [RightClick]: long-tradition Minecraft idiom — right-click while holding
 *   the item triggers the skill.
 * - [Button]: bind to a hotbar slot or off-hand keybind. Worker ε defines
 *   the binding map; Wave 6a's input adapter reads.
 */
public sealed interface ControlScheme {
    public data object RightClick : ControlScheme
    public data object Button : ControlScheme
}

/**
 * Per-viewer particle filter (Wave 5e; RTM README §戦闘システム + §プレイヤー
 * データ "パーティクル非表示設定").
 *
 * Wave 6a's Paper packet filter on `Server#spawnParticle` reads this and
 * suppresses non-matching particles per-viewer.
 */
public sealed interface ParticleVisibility {
    public data object All : ParticleVisibility
    public data object OwnTeamOnly : ParticleVisibility
    public data object OwnOnly : ParticleVisibility
}

/**
 * Per-player audio channel (Wave G 5player-prefs-social / ADR-0124).
 *
 * Three discrete buses keep the volume model legible — vanilla Minecraft and
 * most Source-family engines use the same split. The numeric volume value
 * lives on [PlayerSettings] as [PlayerSettings.musicVolume] / [PlayerSettings.soundVolume]
 * / [PlayerSettings.voiceVolume] so callers address each independently
 * without a map.
 *
 * Phase 1 PoC: actual mix-down to client sound packets is left to a later
 * wave's adapter — this enum + value pair is the **persisted choice**, not
 * the audio backend. The `/astera volume <channel> <0-100>` Brigadier
 * command + the Settings GUI volume tile both round-trip through here.
 */
public enum class VolumeChannel {
    Music,
    Sound,
    Voice,
}

/**
 * Persistent per-player preferences (Wave 5e, RTM issue #15; Wave G ADR-0124
 * added [locale] + per-channel volume).
 *
 * Backed by V8 `player_settings` JSONB. Each typed field decodes via
 * sealed-by-key — JSONB stores the discriminator, the read returns the
 * variant. The JSONB codec runs `ignoreUnknownKeys = true`, so Wave G's new
 * fields default-back on older rows without a DB migration.
 *
 * Per CLAUDE.md, [JumpCountTracker] (Wave 5e combat plugin) gates the
 * 2-jump max when [doubleJumpEnabled] = true (default).
 *
 * [quickCastSlots] maps hotbar slot index (1-9) to a skill content-id
 * (e.g. `"astera:fireball"`). Stored as `String` here because Core cannot
 * depend on Combat per ADR-0022 sibling-isolation; combat resolves the
 * content-id to a `SkillId` at use-site. Same pattern as `itemId: String`
 * in `PersonalVault` / `PlayerInventorySnapshot`.
 *
 * Wave 6a's input adapter reads [quickCastSlots] when the player presses
 * the slot hotkey while a `ControlScheme.Button` is selected.
 *
 * ### Volume range (Wave G)
 *
 * Each of [musicVolume] / [soundVolume] / [voiceVolume] is an Int in `0..100`
 * inclusive (percent). The `init` block rejects out-of-range values so the
 * domain stays self-consistent; [SetVolumeUseCase] surfaces the same range
 * check via a typed error instead of throwing.
 *
 * ### Locale (Wave G)
 *
 * `null` = follow the server default (ADR-0282); a value is the player's explicit
 * choice. [SetLocaleUseCase] only accepts locales the
 * server can render (currently `en` / `ja` — gated by [SUPPORTED_LOCALES]).
 * Adding a new locale means adding the YAML file under `content/languages/`
 * and extending [SUPPORTED_LOCALES] — no domain change.
 */
public data class PlayerSettings(
    val playerId: PlayerId,
    val controlScheme: ControlScheme = ControlScheme.RightClick,
    val particleVisibility: ParticleVisibility = ParticleVisibility.All,
    val doubleJumpEnabled: Boolean = true,
    val quickCastSlots: Map<Int, String> = emptyMap(),
    /**
     * Wave 5engage / ADR-0104 §Privacy: opt-in flag for the public profile
     * REST endpoint (`/api/players/<id>/profile`). Default `false` —
     * privacy-by-default. The Wave 12g handler must check this flag before
     * returning equipment / cosmetic visibility.
     */
    val publicProfileEnabled: Boolean = false,
    /**
     * Wave G ADR-0124 — preferred UI language for chat / GUI / commands.
     * `null` (default) follows the server default locale (ADR-0282); non-null is
     * the player's explicit choice and wins over it. Persisted as the raw `code`
     * string in JSONB, absent when `null`.
     */
    val locale: LocaleCode? = null,
    /** Wave G ADR-0124 — music bus volume, percent in `0..100`. */
    val musicVolume: Int = DEFAULT_VOLUME,
    /** Wave G ADR-0124 — SFX bus volume, percent in `0..100`. */
    val soundVolume: Int = DEFAULT_VOLUME,
    /** Wave G ADR-0124 — voice bus volume, percent in `0..100`. */
    val voiceVolume: Int = DEFAULT_VOLUME,
    /** UI-03 — show the HP / ammo / cooldown status line. Off hides the whole line (the pack blanks the vanilla hearts). */
    val hpLineVisible: Boolean = true,
    /** UI-03 — show the floating damage number over a target you hit (the hit sound stays). */
    val damageNumbersVisible: Boolean = true,
    /** UI-03 — show the first-time guide's title / action-bar hints and world marker. Off pauses the guide. */
    val tutorialHintsEnabled: Boolean = true,
) {
    init {
        quickCastSlots.keys.forEach { slot ->
            require(slot in 1..9) { "quickCastSlots slot must be in 1..9: $slot" }
        }
        require(musicVolume in VOLUME_RANGE) { "musicVolume must be in $VOLUME_RANGE: $musicVolume" }
        require(soundVolume in VOLUME_RANGE) { "soundVolume must be in $VOLUME_RANGE: $soundVolume" }
        require(voiceVolume in VOLUME_RANGE) { "voiceVolume must be in $VOLUME_RANGE: $voiceVolume" }
    }

    /**
     * Wave G — read a single bus' volume by [VolumeChannel]. Used by
     * `SetVolumeUseCase` / GUI cycle handler so the cycling logic doesn't
     * branch on the enum value at every call site.
     */
    public fun volume(channel: VolumeChannel): Int = when (channel) {
        VolumeChannel.Music -> musicVolume
        VolumeChannel.Sound -> soundVolume
        VolumeChannel.Voice -> voiceVolume
    }

    /**
     * Wave G — return a copy with [channel]'s bus set to [value]. Single
     * source of truth for the `(channel, value)` → field mapping; the rest
     * of the codebase routes through here.
     */
    public fun withVolume(channel: VolumeChannel, value: Int): PlayerSettings = when (channel) {
        VolumeChannel.Music -> copy(musicVolume = value)
        VolumeChannel.Sound -> copy(soundVolume = value)
        VolumeChannel.Voice -> copy(voiceVolume = value)
    }

    public companion object {
        public fun default(playerId: PlayerId): PlayerSettings = PlayerSettings(playerId)

        /** Wave G — default per-bus volume (100% = unattenuated). */
        public const val DEFAULT_VOLUME: Int = 100

        /** Wave G — valid range for any per-bus volume value, percent inclusive. */
        public val VOLUME_RANGE: IntRange = 0..100

        /**
         * Wave G — locales the server can fully render. Adding a new locale
         * means adding the YAML file + extending this set; [SetLocaleUseCase]
         * rejects anything outside it with `SettingsError.UnsupportedLocale`.
         */
        public val SUPPORTED_LOCALES: Set<LocaleCode> = setOf(LocaleCode.EN, LocaleCode.JA)
    }
}
