-- Wave 5t (ADR-0086) — V22 role/permission two-tier persistence.
--
-- Coexists with V4__permissions.sql (`role_definitions` + `role_assignments`).
-- V4 backs the legacy IPermissionService (RoleDefinition-shaped, no priority,
-- no expiry). V22 backs the Wave 5t IPermissionBackend / MinimalPermissionBackend
-- + IRoleRegistry (RoleRecord-shaped, with priority + expiresAt + grantedBy).
--
-- Phase 1 PoC does **not** dual-write between V4 and V22 — they intentionally
-- represent different layers of the same domain. The legacy V4 tables stay
-- in place for the older AssignRoleUseCase / CheckPermissionUseCase tests +
-- the Wave 3 PermissionGating decorator. Wave 5t admin tooling reads /
-- writes V22 exclusively.

CREATE TABLE permission_role_assignment (
    player_id    UUID         NOT NULL,
    role_id      TEXT         NOT NULL,
    granted_at   TIMESTAMP(3) NOT NULL,
    expires_at   TIMESTAMP(3),  -- NULL = permanent
    granted_by   UUID,          -- NULL = system grant
    PRIMARY KEY (player_id, role_id)
);

CREATE INDEX idx_permission_role_assignment_player
    ON permission_role_assignment (player_id);

CREATE INDEX idx_permission_role_assignment_expires
    ON permission_role_assignment (expires_at)
    WHERE expires_at IS NOT NULL;
