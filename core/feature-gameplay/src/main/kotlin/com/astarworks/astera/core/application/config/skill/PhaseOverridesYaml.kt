package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.item.PhaseOverrides
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape for [PhaseOverrides]. Single DTO across all archetypes —
 * authors fill only the phases relevant to the bound skill's archetype, and
 * the dispatcher ignores irrelevant fields. This keeps the YAML loader from
 * needing to look up the skill's archetype before parsing the binding.
 *
 * Each phase has an `append` / `prepend` / `replace` triplet. `replace` is
 * optional (`null` = compose with base list); `append` / `prepend` default
 * to empty lists.
 *
 * Wave _skills-2 / ADR-0077.
 */
@Serializable
public data class PhaseOverridesYaml(
    @SerialName("cast_append") public val castAppend: List<MechanicYaml> = emptyList(),
    @SerialName("cast_prepend") public val castPrepend: List<MechanicYaml> = emptyList(),
    @SerialName("cast_replace") public val castReplace: List<MechanicYaml>? = null,
    @SerialName("hit_entity_append") public val hitEntityAppend: List<MechanicYaml> = emptyList(),
    @SerialName("hit_entity_prepend") public val hitEntityPrepend: List<MechanicYaml> = emptyList(),
    @SerialName("hit_entity_replace") public val hitEntityReplace: List<MechanicYaml>? = null,
    @SerialName("hit_block_append") public val hitBlockAppend: List<MechanicYaml> = emptyList(),
    @SerialName("hit_block_prepend") public val hitBlockPrepend: List<MechanicYaml> = emptyList(),
    @SerialName("hit_block_replace") public val hitBlockReplace: List<MechanicYaml>? = null,
    @SerialName("miss_append") public val missAppend: List<MechanicYaml> = emptyList(),
    @SerialName("miss_prepend") public val missPrepend: List<MechanicYaml> = emptyList(),
    @SerialName("miss_replace") public val missReplace: List<MechanicYaml>? = null,
    @SerialName("expire_append") public val expireAppend: List<MechanicYaml> = emptyList(),
    @SerialName("expire_prepend") public val expirePrepend: List<MechanicYaml> = emptyList(),
    @SerialName("expire_replace") public val expireReplace: List<MechanicYaml>? = null,
    @SerialName("removed_append") public val removedAppend: List<MechanicYaml> = emptyList(),
    @SerialName("removed_prepend") public val removedPrepend: List<MechanicYaml> = emptyList(),
    @SerialName("removed_replace") public val removedReplace: List<MechanicYaml>? = null,
    @SerialName("pre_teleport_append") public val preTeleportAppend: List<MechanicYaml> = emptyList(),
    @SerialName("pre_teleport_prepend") public val preTeleportPrepend: List<MechanicYaml> = emptyList(),
    @SerialName("pre_teleport_replace") public val preTeleportReplace: List<MechanicYaml>? = null,
    @SerialName("post_teleport_append") public val postTeleportAppend: List<MechanicYaml> = emptyList(),
    @SerialName("post_teleport_prepend") public val postTeleportPrepend: List<MechanicYaml> = emptyList(),
    @SerialName("post_teleport_replace") public val postTeleportReplace: List<MechanicYaml>? = null,
    @SerialName("spawn_append") public val spawnAppend: List<MechanicYaml> = emptyList(),
    @SerialName("spawn_prepend") public val spawnPrepend: List<MechanicYaml> = emptyList(),
    @SerialName("spawn_replace") public val spawnReplace: List<MechanicYaml>? = null,
    @SerialName("summon_death_append") public val summonDeathAppend: List<MechanicYaml> = emptyList(),
    @SerialName("summon_death_prepend") public val summonDeathPrepend: List<MechanicYaml> = emptyList(),
    @SerialName("summon_death_replace") public val summonDeathReplace: List<MechanicYaml>? = null,
) {
    public fun toDomain(): PhaseOverrides = PhaseOverrides(
        castAppend = castAppend.map { it.toDomain() },
        castPrepend = castPrepend.map { it.toDomain() },
        castReplace = castReplace?.map { it.toDomain() },
        hitEntityAppend = hitEntityAppend.map { it.toDomain() },
        hitEntityPrepend = hitEntityPrepend.map { it.toDomain() },
        hitEntityReplace = hitEntityReplace?.map { it.toDomain() },
        hitBlockAppend = hitBlockAppend.map { it.toDomain() },
        hitBlockPrepend = hitBlockPrepend.map { it.toDomain() },
        hitBlockReplace = hitBlockReplace?.map { it.toDomain() },
        missAppend = missAppend.map { it.toDomain() },
        missPrepend = missPrepend.map { it.toDomain() },
        missReplace = missReplace?.map { it.toDomain() },
        expireAppend = expireAppend.map { it.toDomain() },
        expirePrepend = expirePrepend.map { it.toDomain() },
        expireReplace = expireReplace?.map { it.toDomain() },
        removedAppend = removedAppend.map { it.toDomain() },
        removedPrepend = removedPrepend.map { it.toDomain() },
        removedReplace = removedReplace?.map { it.toDomain() },
        preTeleportAppend = preTeleportAppend.map { it.toDomain() },
        preTeleportPrepend = preTeleportPrepend.map { it.toDomain() },
        preTeleportReplace = preTeleportReplace?.map { it.toDomain() },
        postTeleportAppend = postTeleportAppend.map { it.toDomain() },
        postTeleportPrepend = postTeleportPrepend.map { it.toDomain() },
        postTeleportReplace = postTeleportReplace?.map { it.toDomain() },
        spawnAppend = spawnAppend.map { it.toDomain() },
        spawnPrepend = spawnPrepend.map { it.toDomain() },
        spawnReplace = spawnReplace?.map { it.toDomain() },
        summonDeathAppend = summonDeathAppend.map { it.toDomain() },
        summonDeathPrepend = summonDeathPrepend.map { it.toDomain() },
        summonDeathReplace = summonDeathReplace?.map { it.toDomain() },
    )

    public companion object {
        public val EMPTY: PhaseOverridesYaml = PhaseOverridesYaml()
    }
}
