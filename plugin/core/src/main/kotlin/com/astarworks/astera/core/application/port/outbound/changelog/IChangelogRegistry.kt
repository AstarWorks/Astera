package com.astarworks.astera.core.application.port.outbound.changelog

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.changelog.ChangelogEntry
import com.astarworks.astera.core.domain.changelog.ChangelogVersion

/**
 * Read-only catalog of [ChangelogEntry] release notes
 * (Wave J 5server-content-ops, ADR-0127).
 *
 * Populated at startup by `ChangelogContents.bootstrap` and hot-reloadable via
 * `/astera admin reload changelog`.
 */
public interface IChangelogRegistry : Contents<ChangelogVersion, ChangelogEntry> {

    /** Most-recently-released entry, or `null` if no entries are registered. */
    public fun latest(): ChangelogEntry?

    /** Entry for [version] if known; convenience wrapper over `find(version)`. */
    public fun forVersion(version: ChangelogVersion): ChangelogEntry? = find(version)

    /** Every registered entry sorted newest-first by `releasedAt`. */
    public fun allNewestFirst(): List<ChangelogEntry>
}
