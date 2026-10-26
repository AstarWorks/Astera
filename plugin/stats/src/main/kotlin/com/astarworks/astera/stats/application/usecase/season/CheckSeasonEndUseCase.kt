package com.astarworks.astera.stats.application.usecase.season

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.stats.application.config.SeasonWindowYamlConfig
import com.astarworks.astera.stats.domain.season.Season
import java.time.Instant

/** Invoked only by lobby/all hosts. The durable schedule key fences competing lobby instances. */
public class CheckSeasonEndUseCase(
    private val windows: List<SeasonWindowYamlConfig>,
    private val reset: ResetSeasonUseCase,
    private val announce: (String, Season) -> Unit,
) {
    public suspend fun execute(now: Instant): Result<Int, ResetSeasonUseCase.SeasonError> {
        var count = 0
        for (window in windows) {
            if (now.isBefore(Instant.parse(window.end))) continue
            when (val result = reset.executeScheduled(window.id, window.next, now)) {
                is Result.Failure -> return Result.failure(result.error)
                is Result.Success -> result.value?.let {
                    count++
                    announce(window.id, it)
                }
            }
        }
        return Result.success(count)
    }
}
