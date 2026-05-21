package com.astarworks.astera.core.domain.mob

import com.astarworks.astera.core.domain.skill.SkillId

/**
 * Sealed failure mode for mob-side use cases + adapter calls (Wave 5pve,
 * ADR-0085).
 *
 * Per ADR-0001: application APIs never throw — they return
 * `Result<T, MobError>` so callers can handle each case exhaustively. The
 * variants intentionally mirror [com.astarworks.astera.core.domain.entity.SpawnError]
 * where the failure modes overlap (`UnknownMobType` parallels `UnknownTypeKey`,
 * `NoAdapterAvailable` parallels `NoSpawnableBackend`), but keep their own
 * names so call-site error messages name the mob domain explicitly.
 */
public sealed interface MobError {

    /** The requested [MobTypeId] is not registered with `IMobRegistry`. */
    public data object UnknownMobType : MobError

    /**
     * A skill binding on a [MobSpec] references a [SkillId] not registered in
     * `ISkillRegistry`. The mob can still be spawned (the binding becomes a
     * dead reference); use cases that want to fail-fast on missing skills
     * surface this error during registry validation.
     */
    public data class SkillBindingMissing(public val skillId: SkillId) : MobError

    /**
     * No [com.astarworks.astera.core.application.port.outbound.mob.IMobAdapter]
     * available at runtime (e.g. only MythicMobs adapter is registered but
     * MythicMobs is not installed). The router returns this when every
     * adapter's `isAvailable()` returned false.
     */
    public data object NoAdapterAvailable : MobError

    /**
     * The underlying backend (vanilla server, MythicMobs plugin, …) rejected
     * the call. Carries a free-text [cause] for logging; adapters should
     * never surface raw JVM throwables — wrap them in this variant instead.
     */
    public data class BackendError(public val cause: String) : MobError
}
