package com.astarworks.astera.core.domain.engagement

/**
 * Stickiness gauge (Wave 5engage, ADR-0104): Daily / Weekly / Monthly Active
 * Users, computed off the latest [com.astarworks.astera.core.domain.engagement.EngagementSnapshot]
 * pool by the dashboard service.
 *
 * Stickiness ratio (DAU / MAU) is *not* stored — it's derivable, so we let
 * the caller (Grafana / REST handler) compute it. Storing the derivable form
 * would double the migration surface for the same information.
 *
 * Phase 1 measurement window:
 *  - DAU: distinct players with a session ending in the last 24 h
 *  - WAU: distinct players with a session ending in the last 7 d
 *  - MAU: distinct players with a session ending in the last 30 d
 *
 * The "last session" boundary uses the player's most recent recorded
 * [EngagementSnapshot.lastSeenAt], not session-open-events — this lets the
 * batch job recompute DAU/WAU/MAU from snapshot rows alone without needing
 * a separate session stream to join against.
 */
public data class DauWauMau(
    val dau: Long,
    val wau: Long,
    val mau: Long,
) {
    init {
        require(dau >= 0) { "DAU must be >= 0, was $dau" }
        require(wau >= 0) { "WAU must be >= 0, was $wau" }
        require(mau >= 0) { "MAU must be >= 0, was $mau" }
        require(dau <= wau) {
            "DAU ($dau) must be <= WAU ($wau): a daily-active player is also weekly-active"
        }
        require(wau <= mau) {
            "WAU ($wau) must be <= MAU ($mau): a weekly-active player is also monthly-active"
        }
    }

    /** DAU / MAU ratio — the classic stickiness measure. Returns 0.0 when MAU = 0. */
    public val stickiness: Double get() = if (mau == 0L) 0.0 else dau.toDouble() / mau.toDouble()

    public companion object {
        public val EMPTY: DauWauMau = DauWauMau(0L, 0L, 0L)
    }
}
