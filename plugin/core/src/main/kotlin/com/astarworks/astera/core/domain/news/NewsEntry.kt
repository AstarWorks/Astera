package com.astarworks.astera.core.domain.news

import com.astarworks.astera.core.domain.i18n.MessageKey
import java.time.Instant

/**
 * Editorial / announcement entry surfaced by `/astera news`
 * (Wave J 5server-content-ops, ADR-0127).
 *
 * Loaded from `content/news/<id>.yaml` files at boot. Title + body are
 * [MessageKey] indirections so each entry's strings live in
 * `content/languages/{en,ja}.yaml` and respect the player's locale at
 * render time.
 *
 * Entries past [expiresAt] are filtered out by `INewsRegistry.latest(...)`.
 * A `null` [expiresAt] means "never expires".
 */
public data class NewsEntry(
    public val id: NewsId,
    public val titleKey: MessageKey,
    public val bodyKey: MessageKey,
    public val publishedAt: Instant,
    public val expiresAt: Instant? = null,
    public val severity: NewsSeverity = NewsSeverity.Info,
) {
    init {
        if (expiresAt != null) {
            require(!expiresAt.isBefore(publishedAt)) {
                "NewsEntry $id: expiresAt ($expiresAt) must be on or after publishedAt ($publishedAt)"
            }
        }
    }

    /** True iff [now] is on or after [expiresAt] (and [expiresAt] is non-null). */
    public fun isExpired(now: Instant): Boolean =
        expiresAt != null && !now.isBefore(expiresAt)
}
