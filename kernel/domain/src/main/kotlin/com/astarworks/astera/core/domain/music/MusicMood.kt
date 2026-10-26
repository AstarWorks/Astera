package com.astarworks.astera.core.domain.music

/**
 * High-level mood tag for adaptive BGM selection (Wave 5cinematic, ADR-0110).
 *
 * The adaptive BGM service (Wave 5pve-boss / Wave 5annihilation linkage)
 * uses this enum to pick a track family when a phase / HP threshold fires:
 *
 *  - [Lobby] — pre-match peaceful idle.
 *  - [Match] — generic in-match (default for `Phase1Build` / `Phase2Engage`).
 *  - [BossReveal] — boss spawn cutscene sting.
 *  - [HighIntensity] — `Phase3Tier3` / boss-low-HP / sudden-death.
 *  - [Victory] — post-match win sting (winning team only).
 *  - [Defeat] — post-match loss sting (losing team / eliminated only).
 *  - [Cinematic] — generic cutscene background (intro / tutorial).
 */
public enum class MusicMood {
    LOBBY,
    MATCH,
    BOSS_REVEAL,
    HIGH_INTENSITY,
    VICTORY,
    DEFEAT,
    CINEMATIC,
}
