package com.astarworks.astera.core.domain.mob

/**
 * Filter that gates whether a [MobSpec] may spawn at a candidate world
 * position (Wave 5pve, ADR-0085).
 *
 * All filters are AND-combined: a mob spawns iff every populated condition
 * matches the candidate site. A `null` condition is interpreted as "no
 * constraint" (the field doesn't gate spawning).
 *
 * # Evaluation
 *
 * Pure domain logic (no I/O). The mob spawner / spawn ladder fills the
 * candidate context (current biome key, light level, world time, region) and
 * calls [matches]. The adapter side decides where the context comes from
 * (Paper world API in Wave 6b, fake-world harness in tests).
 *
 * # Why not parametric ranges + a YAML DSL
 *
 * Phase 1 ships the minimal set that supports the Annihilation PoC: biome
 * filtering, day/night cycle, region tagging, light level. Phase 4 may add
 * weather, height bands, structure proximity — those will land via a
 * supersede ADR, not by widening this record.
 *
 * @property minLightLevel Inclusive lower bound on the block light level
 *   (`0..15`). `null` = unconstrained.
 * @property maxLightLevel Inclusive upper bound on the block light level
 *   (`0..15`). `null` = unconstrained.
 * @property biomeKeys If non-null, the candidate's biome key must appear in
 *   the set. Keys are vendor-neutral strings (`minecraft:forest`).
 * @property regionId Wave 5d / 5n region tag. `null` = any region. See
 *   [MobRegionId] for the forward-compat note.
 * @property timeOfDayRange World-time range in ticks (`0..24000` per
 *   in-game day). `null` = any time. Inclusive on both ends.
 */
public data class SpawnConditions(
    public val minLightLevel: Int? = null,
    public val maxLightLevel: Int? = null,
    public val biomeKeys: Set<String>? = null,
    public val regionId: MobRegionId? = null,
    public val timeOfDayRange: LongRange? = null,
) {
    init {
        minLightLevel?.let {
            require(it in 0..15) { "SpawnConditions.minLightLevel must be in 0..15: $it" }
        }
        maxLightLevel?.let {
            require(it in 0..15) { "SpawnConditions.maxLightLevel must be in 0..15: $it" }
        }
        if (minLightLevel != null && maxLightLevel != null) {
            require(minLightLevel <= maxLightLevel) {
                "SpawnConditions.minLightLevel ($minLightLevel) must be <= maxLightLevel ($maxLightLevel)"
            }
        }
        biomeKeys?.let { keys ->
            require(keys.isNotEmpty()) {
                "SpawnConditions.biomeKeys must be null or non-empty (use null for unconstrained)"
            }
            keys.forEach { key ->
                require(key.isNotBlank()) { "SpawnConditions biome key must not be blank" }
            }
        }
        timeOfDayRange?.let { range ->
            require(range.first >= 0L && range.last <= 24_000L) {
                "SpawnConditions.timeOfDayRange must lie within [0, 24000]: $range"
            }
            require(!range.isEmpty()) {
                "SpawnConditions.timeOfDayRange must not be empty"
            }
        }
    }

    /**
     * Pure evaluation against a candidate context. Every non-null condition
     * must match for the result to be `true`.
     */
    public fun matches(context: SpawnContext): Boolean {
        if (minLightLevel != null && context.lightLevel < minLightLevel) return false
        if (maxLightLevel != null && context.lightLevel > maxLightLevel) return false
        if (biomeKeys != null && context.biomeKey !in biomeKeys) return false
        if (regionId != null && context.regionId != regionId) return false
        if (timeOfDayRange != null && context.worldTimeTicks !in timeOfDayRange) return false
        return true
    }

    public companion object {
        /** No constraint — every candidate position matches. */
        public val ANY: SpawnConditions = SpawnConditions()
    }
}

/**
 * Snapshot of the spawn-candidate site, passed to [SpawnConditions.matches].
 *
 * All fields are required (pure value record) — the spawner / spawn-ladder
 * assembles the context from world state before evaluating each candidate
 * [MobSpec]. Tests construct contexts directly with synthetic values.
 *
 * @property biomeKey Vendor-neutral biome key (`minecraft:forest`).
 * @property lightLevel Block light level (`0..15`).
 * @property worldTimeTicks World time mod 24000 (in-game day).
 * @property regionId Region tag for the position, or `null` if untracked.
 */
public data class SpawnContext(
    public val biomeKey: String,
    public val lightLevel: Int,
    public val worldTimeTicks: Long,
    public val regionId: MobRegionId? = null,
) {
    init {
        require(biomeKey.isNotBlank()) { "SpawnContext.biomeKey must not be blank" }
        require(lightLevel in 0..15) {
            "SpawnContext.lightLevel must be in 0..15: $lightLevel"
        }
        require(worldTimeTicks in 0L..24_000L) {
            "SpawnContext.worldTimeTicks must be in [0, 24000]: $worldTimeTicks"
        }
    }
}
