package com.astarworks.astera.core.domain.permission

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Wave 5t (ADR-0086) — full role record for the role/permission two-tier
 * system (Minimal core + LuckPerms adapter).
 *
 * Coexists with the pre-existing simpler [RoleDefinition] (Wave 3 / V4
 * migration). [RoleRecord] is the richer shape used by [IRoleRegistry] +
 * [com.astarworks.astera.core.application.port.outbound.permission.IPermissionBackend]:
 *
 *   - **priority**: higher wins on Allow/Deny conflict resolution
 *   - **titleKey**: i18n key for the role's display title (no raw string)
 *   - **permissions**: list of [PermissionRule] (Allow/Deny) — Deny from
 *     a higher-priority role beats Allow from a lower-priority one
 *
 * The pre-existing [RoleDefinition] stays in place for the legacy
 * [com.astarworks.astera.core.application.port.outbound.IPermissionService]
 * surface (Wave 3 tests, AsteraTestHarness scenarios); both representations
 * map to the same underlying permission check via [PermissionRule.matches].
 */
public data class RoleRecord(
    val id: RoleId,
    val titleKey: MessageKey,
    val priority: Int,
    val permissions: List<PermissionRule>,
) {
    init {
        require(permissions.distinctBy { it.node }.size == permissions.size) {
            "Role $id has duplicate permission nodes"
        }
    }
}

/**
 * Sealed permission rule. `Allow` grants the node; `Deny` revokes it; the
 * higher-priority role wins on conflict.
 *
 * Node syntax: dot-separated lower-case, optionally ending in `.*` for a
 * subtree wildcard. The sentinel `*` matches everything.
 */
public sealed interface PermissionRule {
    public val node: String

    public data class Allow(override val node: String) : PermissionRule
    public data class Deny(override val node: String) : PermissionRule

    public companion object {
        /**
         * Does [rule] grant access for [query]?
         *
         *   - `*` matches anything
         *   - exact match wins
         *   - `foo.bar.*` matches `foo.bar.anything`
         */
        public fun matches(rule: String, query: String): Boolean {
            if (rule == "*") return true
            if (rule == query) return true
            if (rule.endsWith(".*")) {
                val prefix = rule.removeSuffix("*")
                return query.startsWith(prefix)
            }
            return false
        }
    }
}

/**
 * Time-bounded role grant. `expiresAt = null` means permanent until revoked.
 * Granted by an admin / GM (recorded as [grantedBy]) or by the system
 * (`grantedBy = null`).
 */
public data class RoleAssignmentRecord(
    val playerId: PlayerId,
    val roleId: RoleId,
    val grantedAt: Instant,
    val expiresAt: Instant?,
    val grantedBy: PlayerId? = null,
)
