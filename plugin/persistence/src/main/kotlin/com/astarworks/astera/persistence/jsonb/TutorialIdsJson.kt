package com.astarworks.astera.persistence.jsonb

import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import kotlinx.serialization.Serializable

/**
 * JSONB wrapper for `Set<TutorialScriptId>` (Wave 5h, ADR-0079 §3).
 *
 * Used twice on `player_tutorial_state`: one column each for completed and
 * skipped script ids. Mirrors [PermissionsJson] in shape — the domain type is a
 * `@JvmInline value class` with no `@Serializable` annotation, so this DTO
 * persists the inner string list and reconstructs the value class on read.
 *
 * The list is stored sorted for deterministic JSONB on disk (eases diffing /
 * test assertions). Reads de-duplicate via `toSet()`.
 */
@Serializable
public data class TutorialIdsJson(val ids: List<String>) {

    public fun toDomain(): Set<TutorialScriptId> = ids.map { TutorialScriptId(it) }.toSet()

    public companion object {
        public fun fromDomain(ids: Set<TutorialScriptId>): TutorialIdsJson =
            TutorialIdsJson(ids.map { it.value }.sorted())
    }
}
