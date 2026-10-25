package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.news.NewsYamlConfig
import com.astarworks.astera.core.application.port.outbound.news.INewsRegistry
import com.astarworks.astera.core.domain.news.NewsEntry
import com.astarworks.astera.core.domain.news.NewsId
import java.time.Instant

/**
 * News-side `Contents<NewsId, NewsEntry>` (Wave J 5server-content-ops, ADR-0127,
 * ADR-0060 substrate).
 *
 * Bootstrap directory: `content/news/`. Inherits the substrate's atomic map +
 * reload semantics — same shape as [AdvancementContents] / [TutorialContents].
 *
 * Implements [INewsRegistry] (the marker port the command consumes) so
 * `services.register<INewsRegistry>(newsContents, priority = 0)` in
 * `AsteraCorePlugin` exposes this instance to the command layer.
 */
public class NewsContents : YamlDynamicContents<NewsId, NewsEntry, NewsYamlConfig>(
    categoryName = "news",
    deserializer = NewsYamlConfig.serializer(),
),
    INewsRegistry {

    override fun toEntry(config: NewsYamlConfig): Pair<NewsId, NewsEntry> {
        val entry = config.toDomain()
        return entry.id to entry
    }

    override fun latest(limit: Int, now: Instant): List<NewsEntry> {
        require(limit >= 0) { "limit must be >= 0 (got $limit)" }
        if (limit == 0) return emptyList()
        return all()
            .asSequence()
            .filter { !it.isExpired(now) }
            .sortedByDescending { it.publishedAt }
            .take(limit)
            .toList()
    }
}
