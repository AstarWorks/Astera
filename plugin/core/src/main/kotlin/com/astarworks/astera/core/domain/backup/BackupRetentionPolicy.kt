package com.astarworks.astera.core.domain.backup

/**
 * How many snapshots to retain in each bucket (Wave 5w / ADR-0089).
 *
 * Mirrors restic's `--keep-hourly / --keep-daily / --keep-weekly` flags so the
 * adapter can translate 1:1. Phase 1 PoC defaults: 24h hot + 7-day daily + 4
 * weekly per `idea-anti-grief-policy.md` §(b).
 */
public data class BackupRetentionPolicy(
    val hourly: Int,
    val daily: Int,
    val weekly: Int,
) {
    init {
        require(hourly >= 0) { "hourly retention must be non-negative; got $hourly" }
        require(daily >= 0) { "daily retention must be non-negative; got $daily" }
        require(weekly >= 0) { "weekly retention must be non-negative; got $weekly" }
    }

    public companion object {
        /** Phase 1 PoC default per `idea-anti-grief-policy.md`. */
        public val PHASE_1_DEFAULT: BackupRetentionPolicy = BackupRetentionPolicy(
            hourly = 24,
            daily = 7,
            weekly = 4,
        )
    }
}
