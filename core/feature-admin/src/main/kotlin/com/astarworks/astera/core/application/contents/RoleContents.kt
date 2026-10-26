package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.permission.RolesYamlFile
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.port.outbound.permission.IRoleRegistry
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.permission.RoleRecord

/**
 * Wave 5t (ADR-0086) — role registry backed by `content/permissions.yaml`.
 *
 * Unusual shape: unlike `ItemContents` / `TutorialContents` which scan a
 * directory of one-file-per-entry, the permission file is a single YAML
 * with a top-level `roles:` list. We model that by parsing each file as
 * a [RolesYamlFile] and exploding its `roles` into multiple registry
 * entries. The [toEntry] override returns the first role only; bootstrap /
 * reload then flatten via [registerRolesFromFile].
 *
 * In practice every category bound under `content/permissions/` is a single
 * file `content/permissions.yaml`; the substrate's directory-load is reused
 * because it already gives us:
 *   - all-or-nothing reload
 *   - failed-file logging
 *   - atomic swap
 *
 * If a Phase 4 deployment grows to multiple permission files (per-server
 * overrides), they just drop into the same directory — the substrate
 * already handles that.
 */
public class RoleContents(
    parsers: List<IContentParser> = ContentLoader.DEFAULT_PARSERS,
) : YamlDynamicContents<RoleId, RoleRecord, RolesYamlFile>(
    categoryName = "permissions",
    deserializer = RolesYamlFile.serializer(),
    parsers = parsers,
),
    IRoleRegistry {

    /**
     * Substrate convention is one entry per file, but a permissions YAML
     * holds many roles. We register all of them via [register] on each
     * `bootstrap`/`reload`. The substrate's [toEntry] still needs to return
     * a single entry — we return the *first* role so the substrate's
     * own atomic swap respects the file as a unit (if `toEntry` throws,
     * the whole file is rejected and the previous state is preserved).
     */
    override fun toEntry(config: RolesYamlFile): Pair<RoleId, RoleRecord>? {
        if (config.roles.isEmpty()) return null

        // Register the tail directly; the head is returned for the substrate
        // to swap atomically.
        val head = config.roles.first().toRoleRecord()
        config.roles.drop(1).forEach { yamlRole ->
            val rec = yamlRole.toRoleRecord()
            register(rec.id, rec)
        }
        return head.id to head
    }

    override fun resolve(id: RoleId): RoleRecord? = find(id)
}
