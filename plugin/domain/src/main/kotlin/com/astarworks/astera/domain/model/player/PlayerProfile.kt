package com.astarworks.astera.domain.model.player

/**
 * Pure-data record of a player's profile.
 *
 * Phase 2+ behaviour (level, statistics, donate balance) lives on companion
 * domain types so this stays a simple identity + display + temporal record.
 */
public data class PlayerProfile(
    val id: PlayerId,
    val displayName: String,
    val locale: String?,
    val joinedAtEpochMs: Long,
) {
    init {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(joinedAtEpochMs >= 0) { "joinedAtEpochMs must be >= 0" }
    }
}
