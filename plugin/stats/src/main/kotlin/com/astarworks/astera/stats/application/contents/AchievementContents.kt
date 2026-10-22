package com.astarworks.astera.stats.application.contents

import com.astarworks.astera.core.application.contents.ReloadableContents
import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.stats.application.config.AchievementYamlConfig
import com.astarworks.astera.stats.domain.achievement.Achievement
import java.nio.file.Path

/**
 * `content/achievements/` catalog (ADR-0199). Before this existed nothing ever filled the
 * achievement catalog, so every `UnlockAchievementUseCase` call failed with `NotInCatalog`.
 */
public class AchievementContents : YamlDynamicContents<AchievementId, Achievement, AchievementYamlConfig>(
    categoryName = "achievements",
    deserializer = AchievementYamlConfig.serializer(),
) {
    override fun toEntry(config: AchievementYamlConfig): Pair<AchievementId, Achievement> {
        val achievement = config.toDomain()
        return achievement.id to achievement
    }
}

/** Wraps a [ReloadableContents] so a successful `/astera reload` also runs [onReloaded] (e.g. re-syncing a repository's catalog). */
public class ReloadSyncedContents(
    private val delegate: ReloadableContents,
    private val onReloaded: () -> Unit,
) : ReloadableContents {
    override val categoryName: String get() = delegate.categoryName

    override fun reload(dir: Path): ContentLoader.LoadReport =
        delegate.reload(dir).also { if (it.failed == 0) onReloaded() }
}
