package com.astarworks.astera.core.application.config.mob.pve

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.boss.BossPhase
import com.astarworks.astera.core.domain.mob.boss.BossPhaseTransition
import com.astarworks.astera.core.domain.mob.boss.BossSpec
import com.astarworks.astera.core.domain.mob.raid.RaidLockPolicy
import com.astarworks.astera.core.domain.skill.SkillId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/pve/bosses/<id>.yaml`
 * (Wave 5pve-content, ADR-0099).
 *
 * Konsist forbids `kotlinx.serialization` annotations in `*.domain.*`, so the
 * DTO lives in `application.config.mob.pve.*`. [toEntry] returns the
 * `(BossId, BossSpec)` pair that the boss spec registry consumes.
 *
 * # Wire format
 *
 * ```yaml
 * id: astera:scorched-titan
 * display_name:
 *   en: "Scorched Titan"
 *   ja: "焦熱の巨人"
 * base_mob_type: astera:scorched-titan-base
 * phases:
 *   - index: 0
 *     name_key: boss.scorched_titan.phase.0
 *     periodic_skills: [titan-stomp]
 *   - index: 1
 *     name_key: boss.scorched_titan.phase.enraged
 *     immunity_ticks: 60
 *     transition_cast: titan-roar
 *     periodic_skills: [titan-stomp, titan-meteor]
 *     on_hit_skills: [titan-counter]
 * transitions:
 *   - type: hp_threshold
 *     from_phase: 0
 *     to_phase: 1
 *     hp_fraction: 0.5
 * raid_lock:
 *   type: party_lock
 *   max_party_size: 4
 * loot_table: astera:scorched-titan-loot
 * record_kill: true
 * ```
 *
 * # Why ship one transition kind (hp_threshold) for Phase 1
 *
 * Per ADR-0098: HP-based transitions are sufficient for the Annihilation PoC
 * raid content (Scorched Titan / Abyssal Lich). Time-based and mechanic-
 * driven transitions land via [BossPhaseTransition] sealed expansion in
 * future ADRs.
 */
@Serializable
public data class BossYamlConfig(
    public val id: String,
    @SerialName("display_name") public val displayName: Map<String, String> = emptyMap(),
    @SerialName("base_mob_type") public val baseMobType: String,
    public val phases: List<BossPhaseYaml> = emptyList(),
    public val transitions: List<BossPhaseTransitionYaml> = emptyList(),
    @SerialName("raid_lock") public val raidLock: RaidLockYaml? = null,
    @SerialName("loot_table") public val lootTable: String = LootTableId.EMPTY.value,
    @SerialName("record_kill") public val recordKill: Boolean = true,
) {
    public fun toEntry(): Pair<BossId, BossSpec> {
        val bossId = BossId(id)
        val spec = BossSpec(
            id = bossId,
            displayName = LocalizedText(
                byLocale = displayName.entries.associate { (locale, text) ->
                    LocaleCode(locale) to text
                },
            ),
            baseMobType = MobTypeId(baseMobType),
            phases = phases.map { it.toDomain(bossId) },
            transitions = transitions.map { it.toDomain(bossId) },
            raidLock = raidLock?.toDomain(bossId) ?: RaidLockPolicy.OPEN,
            lootTable = LootTableId(lootTable),
            recordKill = recordKill,
        )
        return bossId to spec
    }
}

/** One phase. See [BossYamlConfig] class KDoc for wire shape. */
@Serializable
public data class BossPhaseYaml(
    public val index: Int,
    @SerialName("name_key") public val nameKey: String,
    @SerialName("immunity_ticks") public val immunityTicks: Int = 0,
    @SerialName("transition_cast") public val transitionCast: String? = null,
    @SerialName("periodic_skills") public val periodicSkills: List<String> = emptyList(),
    @SerialName("on_hit_skills") public val onHitSkills: List<String> = emptyList(),
) {
    public fun toDomain(bossId: BossId): BossPhase = BossPhase(
        index = index,
        nameKey = nameKey,
        immunityTicks = immunityTicks,
        transitionCast = transitionCast?.let { SkillId(it) },
        periodicSkills = periodicSkills.map {
            require(it.isNotBlank()) {
                "BossYamlConfig(${bossId.value}).phases[$index].periodic_skills entry must not be blank"
            }
            SkillId(it)
        },
        onHitSkills = onHitSkills.map {
            require(it.isNotBlank()) {
                "BossYamlConfig(${bossId.value}).phases[$index].on_hit_skills entry must not be blank"
            }
            SkillId(it)
        },
    )
}

/** Discriminated-union DTO for [BossPhaseTransition]. `type` selects the variant. */
@Serializable
public data class BossPhaseTransitionYaml(
    public val type: String,
    @SerialName("from_phase") public val fromPhase: Int,
    @SerialName("to_phase") public val toPhase: Int,
    @SerialName("hp_fraction") public val hpFraction: Double? = null,
) {
    public fun toDomain(bossId: BossId): BossPhaseTransition {
        return when (type.lowercase()) {
            "hp_threshold" -> BossPhaseTransition.HpThreshold(
                fromPhase = fromPhase,
                toPhase = toPhase,
                hpFraction = requireNotNull(hpFraction) {
                    "BossYamlConfig(${bossId.value}).transitions.hp_threshold requires hp_fraction"
                },
            )
            else -> throw IllegalArgumentException(
                "BossYamlConfig(${bossId.value}).transitions.type='$type' unknown; " +
                    "expected hp_threshold.",
            )
        }
    }
}

/** Discriminated-union DTO for [RaidLockPolicy]. `type` selects the variant. */
@Serializable
public data class RaidLockYaml(
    public val type: String,
    @SerialName("max_party_size") public val maxPartySize: Int? = null,
) {
    public fun toDomain(bossId: BossId): RaidLockPolicy {
        return when (type.lowercase()) {
            "open" -> RaidLockPolicy.Open
            "party_lock" -> RaidLockPolicy.PartyLock(
                maxPartySize = requireNotNull(maxPartySize) {
                    "BossYamlConfig(${bossId.value}).raid_lock.party_lock requires max_party_size"
                },
            )
            else -> throw IllegalArgumentException(
                "BossYamlConfig(${bossId.value}).raid_lock.type='$type' unknown; " +
                    "expected open / party_lock.",
            )
        }
    }
}
