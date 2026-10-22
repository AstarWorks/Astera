package com.astarworks.astera.stats.application.usecase.season

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.stats.application.port.outbound.ISeasonRepository
import com.astarworks.astera.stats.domain.season.Season
import java.time.Instant

/**
 * Closes the active season and opens a fresh one labelled [label] (Wave 7b,
 * ADR-0037). Backs the `/astera-season reset` admin command.
 *
 * Leaderboards are partitioned by season, so opening a new season starts a
 * blank ranking while the prior season's standings stay queryable by id.
 *
 * @param clock supplies the close/open instant; injected for deterministic tests.
 */
public class ResetSeasonUseCase(
    private val seasonRepository: ISeasonRepository,
    private val clock: () -> Instant = { Instant.now() },
) {

    public fun execute(label: String): Result<Season, SeasonError> {
        if (label.isBlank()) return Result.failure(SeasonError.BlankLabel)
        return Result.success(seasonRepository.start(label, clock()))
    }

    public fun executeScheduled(scheduleKey: String, label: String, at: Instant): Result<Season?, SeasonError> {
        if (label.isBlank()) return Result.failure(SeasonError.BlankLabel)
        if (scheduleKey.isBlank()) return Result.failure(SeasonError.BlankScheduleKey)
        return Result.success(seasonRepository.startOnce(scheduleKey, label, at))
    }

    /** Expected failure modes of [ResetSeasonUseCase.execute]. */
    public sealed interface SeasonError {
        /** The supplied label was blank — a season must carry a human-readable name. */
        public data object BlankLabel : SeasonError
        public data object BlankScheduleKey : SeasonError
    }
}
