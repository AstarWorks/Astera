package com.astarworks.astera.core.domain.world

/**
 * Vendor-neutral world difficulty.
 *
 * The Paper adapter maps these to Bukkit `Difficulty.PEACEFUL` / `EASY` /
 * `NORMAL` / `HARD` (Wave 6b). The domain stays free of any Bukkit type so
 * use cases (per-match config overlay) stay testable in pure JVM.
 *
 * Wave 5n (ADR-0080).
 */
public enum class WorldDifficulty {
    PEACEFUL,
    EASY,
    NORMAL,
    HARD,
}
