package com.astarworks.astera.economy.application.config

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.economy.domain.reward.MatchRewardTable
import com.charleskorn.kaml.Yaml
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Loads `content/balance/match-rewards.yaml`. Fail-soft like the other balance
 * files: a missing or broken file logs and yields [MatchRewardTable.FALLBACK],
 * so a match end never fails because of a typo in content.
 */
public class MatchRewardsLoader {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun load(path: Path): MatchRewardTable {
        if (!path.exists()) {
            log.info("match-rewards.yaml not found at {}; using the fallback table", path)
            return MatchRewardTable.FALLBACK
        }
        val text = runCatching { path.readText() }.getOrElse {
            log.warn("Failed to read match-rewards.yaml at {}: {}; using the fallback table", path, it.message)
            return MatchRewardTable.FALLBACK
        }
        return parse(text, path.toString())
    }

    public fun parse(text: String, origin: String = "<text>"): MatchRewardTable {
        val dto = runCatching { Yaml.default.decodeFromString(MatchRewardsYamlConfig.serializer(), text) }
            .getOrElse {
                log.warn("YAML parse failure for match-rewards.yaml at {}: {}; using the fallback table", origin, it.message)
                return MatchRewardTable.FALLBACK
            }
        return when (val result = dto.toDomain()) {
            is Result.Success -> result.value
            is Result.Failure -> {
                log.warn("Invalid match-rewards.yaml at {}: {}; using the fallback table", origin, result.error.detail)
                MatchRewardTable.FALLBACK
            }
        }
    }
}
