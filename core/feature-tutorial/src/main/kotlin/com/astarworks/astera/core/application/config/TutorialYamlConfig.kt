package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.dialog.DialogTreeId
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.tutorial.TutorialPredicate
import com.astarworks.astera.core.domain.tutorial.TutorialScript
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.core.domain.tutorial.TutorialStep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * YAML-shaped DTO for `content/tutorials/` definition files (Wave 5e, RTM
 * issue #17).
 *
 * Mirrors [com.astarworks.astera.match.application.config.StageYamlConfig] and
 * [SpawnPointYamlConfig]'s pattern: kept in `application/config/` so the
 * domain ([TutorialScript] / [TutorialStep]) stays free of
 * `kotlinx.serialization` annotations and the wire schema can evolve
 * independently of the domain.
 *
 * # Wire format
 *
 * ```yaml
 * id: getting-started
 * displayKey: astera.tutorial.getting_started.title
 * steps:
 *   - type: send_message
 *     key: astera.tutorial.welcome
 *   - type: give_item
 *     itemId: astera:example-pistol
 *     count: 1
 *   - type: wait_for_action
 *     actionId: fire_weapon
 *     timeoutSec: 60
 *   - type: teleport_to
 *     target: { x: 0.0, y: 64.0, z: 0.0 }
 *   - type: send_message
 *     key: astera.tutorial.complete
 * ```
 *
 * Each step variant is keyed by a `type:` discriminator (kotlinx-JSON
 * convention; kaml's `PolymorphismStyle.Property` reads the same property so
 * the schema works across YAML / TOML / JSON wire formats per ADR-0016).
 *
 * # Why `Vec3YamlConfig` is redefined here
 *
 * The match plugin owns its own `Vec3YamlConfig` in
 * `match.application.config`. Core cannot depend on match (sibling-isolation,
 * ADR-0022), so a parallel definition lives here. The pair has the same wire
 * shape so authoring is uniform across stage and tutorial YAML.
 */
@Serializable
public data class TutorialYamlConfig(
    public val id: String,
    public val displayKey: String,
    public val steps: List<TutorialStepDto>,
) {
    /**
     * Convert the parsed DTO into a domain [TutorialScript]. Any kebab-case /
     * i18n key violation surfaces as `IllegalArgumentException`, which
     * `ContentLoader` catches and counts as a failed load (the batch keeps
     * going under `bootstrap`, the whole reload aborts under `reload`).
     */
    public fun toDomain(): TutorialScript = TutorialScript(
        id = TutorialScriptId(id),
        displayKey = MessageKey(displayKey),
        steps = steps.map { it.toDomain() },
    )
}

/**
 * Polymorphic step DTO keyed by `type:`. Each subtype maps 1:1 onto a
 * [TutorialStep] variant.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class TutorialStepDto {

    public abstract fun toDomain(): TutorialStep

    @Serializable
    @SerialName("send_message")
    public data class SendMessage(val key: String) : TutorialStepDto() {
        override fun toDomain(): TutorialStep = TutorialStep.SendMessage(MessageKey(key))
    }

    @Serializable
    @SerialName("teleport_to")
    public data class TeleportTo(val target: TutorialVec3YamlConfig) : TutorialStepDto() {
        override fun toDomain(): TutorialStep = TutorialStep.TeleportTo(target.toVec3())
    }

    @Serializable
    @SerialName("wait_for_action")
    public data class WaitForAction(
        val actionId: String,
        val timeoutSec: Long? = null,
    ) : TutorialStepDto() {
        override fun toDomain(): TutorialStep =
            TutorialStep.WaitForAction(actionId = actionId, timeoutSec = timeoutSec)
    }

    @Serializable
    @SerialName("give_item")
    public data class GiveItem(
        val itemId: String,
        val count: Int = 1,
    ) : TutorialStepDto() {
        override fun toDomain(): TutorialStep =
            TutorialStep.GiveItem(itemId = itemId, count = count)
    }

    // ─── Wave 5p sequencer extensions (ADR-0088) ─────────────────────────

    @Serializable
    @SerialName("show_dialog")
    public data class ShowDialog(@SerialName("tree_id") val treeId: String) : TutorialStepDto() {
        override fun toDomain(): TutorialStep =
            TutorialStep.ShowDialog(DialogTreeId(treeId))
    }

    @Serializable
    @SerialName("await_interaction")
    public data class AwaitInteraction(
        @SerialName("interaction_tag") val interactionTag: String,
        @SerialName("timeout_sec") val timeoutSec: Long? = null,
    ) : TutorialStepDto() {
        override fun toDomain(): TutorialStep =
            TutorialStep.AwaitInteraction(interactionTag, timeoutSec)
    }

    @Serializable
    @SerialName("await_movement")
    public data class AwaitMovement(
        val target: TutorialVec3YamlConfig,
        val radius: Double,
        @SerialName("timeout_sec") val timeoutSec: Long? = null,
    ) : TutorialStepDto() {
        override fun toDomain(): TutorialStep =
            TutorialStep.AwaitMovement(target.toVec3(), radius, timeoutSec)
    }

    @Serializable
    @SerialName("await_skill_cast")
    public data class AwaitSkillCast(
        @SerialName("skill_id") val skillId: String,
        @SerialName("timeout_sec") val timeoutSec: Long? = null,
    ) : TutorialStepDto() {
        override fun toDomain(): TutorialStep =
            TutorialStep.AwaitSkillCast(SkillId(skillId), timeoutSec)
    }

    @Serializable
    @SerialName("grant_item")
    public data class GrantItem(
        @SerialName("item_id") val itemId: String,
        val count: Int = 1,
    ) : TutorialStepDto() {
        override fun toDomain(): TutorialStep =
            TutorialStep.GrantItem(ItemId(itemId), count)
    }

    @Serializable
    @SerialName("await_predicate")
    public data class AwaitPredicate(val predicate: TutorialPredicateDto) : TutorialStepDto() {
        override fun toDomain(): TutorialStep =
            TutorialStep.AwaitPredicate(predicate.toDomain())
    }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class TutorialPredicateDto {
    public abstract fun toDomain(): TutorialPredicate

    @Serializable
    @SerialName("has_item")
    public data class HasItem(
        @SerialName("item_id") val itemId: String,
        val count: Int = 1,
    ) : TutorialPredicateDto() {
        override fun toDomain(): TutorialPredicate =
            TutorialPredicate.HasItem(ItemId(itemId), count)
    }

    @Serializable
    @SerialName("has_currency")
    public data class HasCurrency(val currency: Currency, val amount: Long) : TutorialPredicateDto() {
        override fun toDomain(): TutorialPredicate =
            TutorialPredicate.HasCurrency(currency, amount)
    }

    @Serializable
    @SerialName("has_advancement")
    public data class HasAdvancement(
        @SerialName("advancement_id") val advancementId: String,
    ) : TutorialPredicateDto() {
        override fun toDomain(): TutorialPredicate =
            TutorialPredicate.HasAdvancement(AdvancementId(advancementId))
    }

    @Serializable
    @SerialName("permission_check")
    public data class PermissionCheck(val permission: String) : TutorialPredicateDto() {
        override fun toDomain(): TutorialPredicate =
            TutorialPredicate.PermissionCheck(Permission(permission))
    }
}

/**
 * YAML-shaped DTO for a [Vec3] used inside tutorial steps. Parallels
 * `match.application.config.Vec3YamlConfig`; defined here so `core` keeps no
 * dependency on `match`.
 */
@Serializable
public data class TutorialVec3YamlConfig(
    public val x: Double,
    public val y: Double,
    public val z: Double,
) {
    public fun toVec3(): Vec3 = Vec3(x = x, y = y, z = z)
}
