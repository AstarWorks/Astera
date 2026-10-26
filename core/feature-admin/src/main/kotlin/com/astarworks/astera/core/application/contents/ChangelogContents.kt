package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.changelog.ChangelogYamlConfig
import com.astarworks.astera.core.application.port.outbound.changelog.IChangelogRegistry
import com.astarworks.astera.core.domain.changelog.ChangelogEntry
import com.astarworks.astera.core.domain.changelog.ChangelogVersion

/**
 * Changelog-side `Contents<ChangelogVersion, ChangelogEntry>`
 * (Wave J 5server-content-ops, ADR-0127, ADR-0060 substrate).
 *
 * Bootstrap directory: `content/changelog/`.
 */
public class ChangelogContents : YamlDynamicContents<ChangelogVersion, ChangelogEntry, ChangelogYamlConfig>(
    categoryName = "changelog",
    deserializer = ChangelogYamlConfig.serializer(),
),
    IChangelogRegistry {

    override fun toEntry(config: ChangelogYamlConfig): Pair<ChangelogVersion, ChangelogEntry> {
        val entry = config.toDomain()
        return entry.version to entry
    }

    override fun latest(): ChangelogEntry? =
        all().maxByOrNull { it.releasedAt }

    override fun allNewestFirst(): List<ChangelogEntry> =
        all().sortedByDescending { it.releasedAt }
}
