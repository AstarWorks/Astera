package com.astarworks.astera.stats.application.port.outbound

import com.astarworks.astera.stats.domain.season.Season
import java.time.Instant

/**
 * Outbound port for competitive season lifecycle (Wave 7b, ADR-0037).
 *
 * In-memory default (priority 0) in `plugin:stats`; Postgres overlay (priority
 * 100, V48 `seasons`) in `plugin:persistence`. At most one season is active
 * ([Season.isActive]) at a time.
 */
public interface ISeasonRepository {

    /** The currently-active season, or null if none has been started. */
    public fun current(): Season?

    /**
     * Close the active season (if any) at [at] and open a new active season
     * labelled [label]. Returns the new active season. The new season's
     * [SeasonId][com.astarworks.astera.stats.domain.season.SeasonId] is derived
     * by the implementation (e.g. slug of [label]).
     */
    public fun start(label: String, at: Instant): Season

    /** Atomically record a schedule key and reset; null if that key already ran. */
    public fun startOnce(scheduleKey: String, label: String, at: Instant): Season?

    /** All seasons, most recent first. */
    public fun all(): List<Season>
}
