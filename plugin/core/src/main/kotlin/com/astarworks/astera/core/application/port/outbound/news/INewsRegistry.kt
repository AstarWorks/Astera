package com.astarworks.astera.core.application.port.outbound.news

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.news.NewsEntry
import com.astarworks.astera.core.domain.news.NewsId
import java.time.Instant

/**
 * Read-only catalog of [NewsEntry] surfaces (Wave J 5server-content-ops, ADR-0127).
 *
 * Populated at startup by `NewsContents.bootstrap` and hot-reloadable via
 * `/astera-reload news`. Mirrors [com.astarworks.astera.core.application.port.outbound.IAdvancementRegistry] /
 * [com.astarworks.astera.core.application.port.outbound.ITutorialRegistry] — the marker
 * extends [Contents] so the underlying substrate (`YamlDynamicContents`) provides
 * `find` / `all` uniformly.
 *
 * In addition to the substrate API, [latest] returns the top `n` non-expired
 * entries newest-first (`publishedAt` DESC) so the command layer doesn't have
 * to re-implement the same filter at every call site.
 */
public interface INewsRegistry : Contents<NewsId, NewsEntry> {

    /**
     * Newest-first list of non-expired entries, capped at [limit].
     *
     * "Non-expired" is evaluated against [now] (defaults to wall-clock); callers
     * may pass a fixed instant in tests.
     */
    public fun latest(limit: Int, now: Instant = Instant.now()): List<NewsEntry>
}
