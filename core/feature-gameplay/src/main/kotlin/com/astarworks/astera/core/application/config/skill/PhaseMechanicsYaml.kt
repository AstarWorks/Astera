package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire shape for [PhaseMechanics]. Polymorphic by `type:` discriminator (kaml polymorphismPropertyName)
 * (matches `SkillYamlConfig.archetype` string).
 *
 * Each variant exposes the phases its archetype supports; absent phases
 * default to empty lists so YAML stays terse for the common case.
 *
 * Wave _skills-2 / ADR-0077.
 */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class PhaseMechanicsYaml {

    public abstract fun toDomain(): PhaseMechanics

    @Serializable
    @SerialName("projectile")
    public data class Projectile(
        public val cast: List<MechanicYaml> = emptyList(),
        @SerialName("hit_entity") public val hitEntity: List<MechanicYaml> = emptyList(),
        @SerialName("hit_block") public val hitBlock: List<MechanicYaml> = emptyList(),
        public val miss: List<MechanicYaml> = emptyList(),
        public val expire: List<MechanicYaml> = emptyList(),
    ) : PhaseMechanicsYaml() {
        override fun toDomain(): PhaseMechanics = PhaseMechanics.Projectile(
            cast = cast.map { it.toDomain() },
            hitEntity = hitEntity.map { it.toDomain() },
            hitBlock = hitBlock.map { it.toDomain() },
            miss = miss.map { it.toDomain() },
            expire = expire.map { it.toDomain() },
        )
    }

    @Serializable
    @SerialName("melee")
    public data class Melee(
        public val cast: List<MechanicYaml> = emptyList(),
        @SerialName("hit_entity") public val hitEntity: List<MechanicYaml> = emptyList(),
        public val miss: List<MechanicYaml> = emptyList(),
    ) : PhaseMechanicsYaml() {
        override fun toDomain(): PhaseMechanics = PhaseMechanics.Melee(
            cast = cast.map { it.toDomain() },
            hitEntity = hitEntity.map { it.toDomain() },
            miss = miss.map { it.toDomain() },
        )
    }

    @Serializable
    @SerialName("aoe")
    public data class Aoe(
        public val cast: List<MechanicYaml> = emptyList(),
        @SerialName("hit_entity") public val hitEntity: List<MechanicYaml> = emptyList(),
        public val expire: List<MechanicYaml> = emptyList(),
    ) : PhaseMechanicsYaml() {
        override fun toDomain(): PhaseMechanics = PhaseMechanics.Aoe(
            cast = cast.map { it.toDomain() },
            hitEntity = hitEntity.map { it.toDomain() },
            expire = expire.map { it.toDomain() },
        )
    }

    @Serializable
    @SerialName("buff")
    public data class Buff(
        public val cast: List<MechanicYaml> = emptyList(),
        public val expire: List<MechanicYaml> = emptyList(),
        public val removed: List<MechanicYaml> = emptyList(),
    ) : PhaseMechanicsYaml() {
        override fun toDomain(): PhaseMechanics = PhaseMechanics.Buff(
            cast = cast.map { it.toDomain() },
            expire = expire.map { it.toDomain() },
            removed = removed.map { it.toDomain() },
        )
    }

    @Serializable
    @SerialName("teleport")
    public data class Teleport(
        public val cast: List<MechanicYaml> = emptyList(),
        @SerialName("pre_teleport") public val preTeleport: List<MechanicYaml> = emptyList(),
        @SerialName("post_teleport") public val postTeleport: List<MechanicYaml> = emptyList(),
    ) : PhaseMechanicsYaml() {
        override fun toDomain(): PhaseMechanics = PhaseMechanics.Teleport(
            cast = cast.map { it.toDomain() },
            preTeleport = preTeleport.map { it.toDomain() },
            postTeleport = postTeleport.map { it.toDomain() },
        )
    }

    @Serializable
    @SerialName("sequence")
    public data class Sequence(
        public val cast: List<MechanicYaml> = emptyList(),
    ) : PhaseMechanicsYaml() {
        override fun toDomain(): PhaseMechanics = PhaseMechanics.Sequence(cast = cast.map { it.toDomain() })
    }

    @Serializable
    @SerialName("summon")
    public data class Summon(
        public val cast: List<MechanicYaml> = emptyList(),
        public val spawn: List<MechanicYaml> = emptyList(),
        @SerialName("summon_death") public val summonDeath: List<MechanicYaml> = emptyList(),
    ) : PhaseMechanicsYaml() {
        override fun toDomain(): PhaseMechanics = PhaseMechanics.Summon(
            cast = cast.map { it.toDomain() },
            spawn = spawn.map { it.toDomain() },
            summonDeath = summonDeath.map { it.toDomain() },
        )
    }
}
