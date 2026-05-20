package com.astarworks.astera.core.application.port.outbound.permission

import com.astarworks.astera.core.application.contents.ReloadableContents
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.permission.RoleRecord

/**
 * Wave 5t (ADR-0086) — registry of role records loaded from
 * `content/permissions.yaml` (or its multi-format equivalent).
 *
 * Extends [ReloadableContents] so `/astera reload permissions` (Wave _infra-2b
 * dispatcher, ADR-0065) atomically swaps the role tree without restart.
 *
 * Coexists with the pre-existing [com.astarworks.astera.core.application.port.outbound.IPermissionService]:
 * that port owns assignment + check; this port owns the role catalogue.
 * Together they form the two-tier surface ADR-0086 specifies (registry +
 * backend selector).
 */
public interface IRoleRegistry : ReloadableContents {
    public fun all(): Collection<RoleRecord>
    public fun resolve(id: RoleId): RoleRecord?

    /** Tab-completion friendly: every registered role id as plain strings. */
    public fun ids(): Set<String> = all().map { it.id.value }.toSet()
}
