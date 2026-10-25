package com.astarworks.astera.core.application.config.permission

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.PermissionRule
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.permission.RoleRecord
import kotlinx.serialization.Serializable

/**
 * Wave 5t (ADR-0086) — YAML DTO for `content/permissions.yaml`.
 *
 * ```yaml
 * roles:
 *   - id: astera:admin
 *     titleKey: astera.role.admin
 *     priority: 100
 *     permissions:
 *       - allow: "astera.*"
 *   - id: astera:gm
 *     titleKey: astera.role.gm
 *     priority: 80
 *     permissions:
 *       - allow: "astera.moderation.*"
 *       - allow: "astera.match.start"
 *   - id: astera:vip
 *     titleKey: astera.role.vip
 *     priority: 50
 *     permissions:
 *       - allow: "astera.cosmetic.*"
 *   - id: astera:player
 *     titleKey: astera.role.player
 *     priority: 10
 *     permissions: []
 * ```
 *
 * Kept in `application/config/` (kaml-annotated) so the domain stays free
 * of `kotlinx.serialization` annotations (Konsist rule 6 / coding-posture).
 */
@Serializable
public data class RolesYamlFile(
    val roles: List<RoleYamlConfig> = emptyList(),
)

@Serializable
public data class RoleYamlConfig(
    val id: String,
    val titleKey: String,
    val priority: Int = 0,
    val permissions: List<PermissionRuleYaml> = emptyList(),
) {
    public fun toRoleRecord(): RoleRecord {
        require(id.isNotBlank()) { "Role id must not be blank" }
        return RoleRecord(
            id = RoleId(id),
            titleKey = MessageKey(titleKey),
            priority = priority,
            permissions = permissions.map { it.toDomain() },
        )
    }
}

/**
 * A permission rule encoded as either `allow: ...` or `deny: ...`. The DTO
 * accepts both keys; exactly one must be set per entry.
 */
@Serializable
public data class PermissionRuleYaml(
    val allow: String? = null,
    val deny: String? = null,
) {
    public fun toDomain(): PermissionRule {
        val allow = allow
        val deny = deny
        return when {
            allow != null && deny == null -> {
                require(allow.isNotBlank()) { "permission rule 'allow' must not be blank" }
                PermissionRule.Allow(allow)
            }
            deny != null && allow == null -> {
                require(deny.isNotBlank()) { "permission rule 'deny' must not be blank" }
                PermissionRule.Deny(deny)
            }
            else ->
                throw IllegalArgumentException(
                    "permission rule must set exactly one of 'allow' or 'deny' (got allow=$allow, deny=$deny)",
                )
        }
    }
}
