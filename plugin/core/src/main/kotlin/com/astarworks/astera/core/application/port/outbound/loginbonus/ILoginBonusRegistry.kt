package com.astarworks.astera.core.application.port.outbound.loginbonus

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.loginbonus.LoginBonusSpec

/**
 * Read-only catalog of [LoginBonusSpec] keyed by `day` (1-indexed)
 * (Wave J 5server-content-ops, ADR-0127 — Phase 2 ready skeleton).
 *
 * Populated at startup by `LoginBonusContents.bootstrap` and hot-reloadable
 * via `/astera admin reload login-bonus`.
 */
public interface ILoginBonusRegistry : Contents<Int, LoginBonusSpec> {
    /** Convenience: spec for [day], or `null` if no entry is registered. */
    public fun forDay(day: Int): LoginBonusSpec? = find(day)
}
