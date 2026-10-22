package com.astarworks.astera.match.domain.death

/**
 * Per-mode death / respawn tuning (F3-5), read from the `default_config` of
 * `content/game-modes/<id>.yaml`:
 *
 * ```yaml
 * default_config:
 *   respawn_delay_seconds: "5"      # death screen + countdown length; 0 = instant respawn
 *   death_cam_seconds: "2"          # spectate the killer first, then free-cam
 *   spawn_protection_seconds: "3"   # invulnerability after respawn; ends on the first attack
 * ```
 *
 * @property respawnDelaySeconds seconds between the death and the respawn teleport (0..60).
 * @property deathCamSeconds seconds the victim's camera is attached to the killer (0..30,
 *   clamped to [respawnDelaySeconds] by [effectiveDeathCamSeconds]).
 * @property spawnProtectionSeconds invulnerability window after the respawn (0..30).
 */
public data class DeathRespawnConfig(
    public val respawnDelaySeconds: Int = DEFAULT_RESPAWN_DELAY_SECONDS,
    public val deathCamSeconds: Int = DEFAULT_DEATH_CAM_SECONDS,
    public val spawnProtectionSeconds: Int = DEFAULT_SPAWN_PROTECTION_SECONDS,
) {
    init {
        require(respawnDelaySeconds in 0..MAX_DELAY_SECONDS) {
            "respawnDelaySeconds must be 0..$MAX_DELAY_SECONDS, was $respawnDelaySeconds"
        }
        require(deathCamSeconds in 0..MAX_DEATH_CAM_SECONDS) {
            "deathCamSeconds must be 0..$MAX_DEATH_CAM_SECONDS, was $deathCamSeconds"
        }
        require(spawnProtectionSeconds in 0..MAX_PROTECTION_SECONDS) {
            "spawnProtectionSeconds must be 0..$MAX_PROTECTION_SECONDS, was $spawnProtectionSeconds"
        }
    }

    /** Death-cam length actually used: never longer than the respawn delay. */
    public val effectiveDeathCamSeconds: Int get() = minOf(deathCamSeconds, respawnDelaySeconds)

    /** Outcome of [parse]: the config plus one warning per rejected value. */
    public data class Parsed(public val config: DeathRespawnConfig, public val warnings: List<String>)

    public companion object {
        public const val KEY_RESPAWN_DELAY: String = "respawn_delay_seconds"
        public const val KEY_DEATH_CAM: String = "death_cam_seconds"
        public const val KEY_SPAWN_PROTECTION: String = "spawn_protection_seconds"

        public const val DEFAULT_RESPAWN_DELAY_SECONDS: Int = 5
        public const val DEFAULT_DEATH_CAM_SECONDS: Int = 2
        public const val DEFAULT_SPAWN_PROTECTION_SECONDS: Int = 3

        private const val MAX_DELAY_SECONDS = 60
        private const val MAX_DEATH_CAM_SECONDS = 30
        private const val MAX_PROTECTION_SECONDS = 30

        public val DEFAULT: DeathRespawnConfig = DeathRespawnConfig()

        /** Parse a mode's free-form `default_config`; a bad value keeps the default and yields a warning. */
        public fun parse(raw: Map<String, String>): Parsed {
            val warnings = mutableListOf<String>()
            fun int(key: String, default: Int, max: Int): Int {
                val text = raw[key] ?: return default
                val value = text.trim().toIntOrNull()
                if (value == null || value !in 0..max) {
                    warnings += "death/respawn: '$key' must be an integer 0..$max (got '$text'); using $default."
                    return default
                }
                return value
            }
            val config = DeathRespawnConfig(
                respawnDelaySeconds = int(KEY_RESPAWN_DELAY, DEFAULT_RESPAWN_DELAY_SECONDS, MAX_DELAY_SECONDS),
                deathCamSeconds = int(KEY_DEATH_CAM, DEFAULT_DEATH_CAM_SECONDS, MAX_DEATH_CAM_SECONDS),
                spawnProtectionSeconds = int(KEY_SPAWN_PROTECTION, DEFAULT_SPAWN_PROTECTION_SECONDS, MAX_PROTECTION_SECONDS),
            )
            return Parsed(config, warnings)
        }
    }
}
