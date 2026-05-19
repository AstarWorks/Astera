package com.astarworks.astera.core.domain.player

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
 * Persistent per-player preferences (Wave 5e, RTM issue #15).
 *
 * Backed by V8 `player_settings` JSONB. Each typed field decodes via
 * sealed-by-key — JSONB stores the discriminator, the read returns the
 * variant. Worker ε implements the codec; Phase A declares the shape.
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
 */
public data class PlayerSettings(
    val playerId: PlayerId,
    val controlScheme: ControlScheme = ControlScheme.RightClick,
    val particleVisibility: ParticleVisibility = ParticleVisibility.All,
    val doubleJumpEnabled: Boolean = true,
    val quickCastSlots: Map<Int, String> = emptyMap(),
) {
    init {
        quickCastSlots.keys.forEach { slot ->
            require(slot in 1..9) { "quickCastSlots slot must be in 1..9: $slot" }
        }
    }

    public companion object {
        public fun default(playerId: PlayerId): PlayerSettings = PlayerSettings(playerId)
    }
}
