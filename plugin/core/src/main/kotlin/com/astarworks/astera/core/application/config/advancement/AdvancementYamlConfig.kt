package com.astarworks.astera.core.application.config.advancement

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.achievement.TitleId
import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.advancement.AdvancementFrame
import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.advancement.AdvancementReward
import com.astarworks.astera.core.domain.advancement.CustomAdvancement
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * YAML-shaped DTO for `content/advancements/` files (Wave 5p, ADR-0088).
 *
 * Mirrors [com.astarworks.astera.core.application.config.TutorialYamlConfig] and
 * [com.astarworks.astera.core.application.config.ItemYamlConfig]: kept in
 * `application/config/` so the domain stays free of
 * `kotlinx.serialization` annotations, and the wire schema can evolve
 * independently.
 *
 * # Wire format
 *
 * ```yaml
 * id: first-kill
 * title_key: astera.advancement.first_kill.title
 * description_key: astera.advancement.first_kill.description
 * icon_item: example-sword
 * frame: TASK
 * hidden: false
 * parent: getting-started
 * criteria:
 *   type: kill_count
 *   target: 1
 * rewards:
 *   - type: grant_currency
 *     currency: GOLD
 *     amount: 100
 *   - type: grant_title
 *     title_id: rookie
 * ```
 *
 * Composite criteria nest:
 *
 * ```yaml
 * criteria:
 *   type: all_of
 *   children:
 *     - { type: kill_count, target: 10 }
 *     - { type: matches_won, count: 1 }
 * ```
 *
 * Each polymorphic variant uses kotlinx's `JsonClassDiscriminator("type")`
 * convention (kaml's `PolymorphismStyle.Property` reads the same field per
 * ADR-0016).
 */
@Serializable
public data class AdvancementYamlConfig(
    public val id: String,
    @SerialName("title_key") public val titleKey: String,
    @SerialName("description_key") public val descriptionKey: String,
    @SerialName("icon_item") public val iconItem: String,
    public val criteria: AdvancementCriteriaDto,
    public val rewards: List<AdvancementRewardDto> = emptyList(),
    public val parent: String? = null,
    public val frame: AdvancementFrame = AdvancementFrame.TASK,
    public val hidden: Boolean = false,
) {
    public fun toDomain(): CustomAdvancement = CustomAdvancement(
        id = AdvancementId(id),
        titleKey = MessageKey(titleKey),
        descriptionKey = MessageKey(descriptionKey),
        iconItemId = ItemId(iconItem),
        criteria = criteria.toDomain(),
        rewards = rewards.map { it.toDomain() },
        parent = parent?.let { AdvancementId(it) },
        frame = frame,
        hidden = hidden,
    )
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class AdvancementCriteriaDto {

    public abstract fun toDomain(): AdvancementCriteria

    @Serializable
    @SerialName("kill_count")
    public data class KillCount(public val target: Int) : AdvancementCriteriaDto() {
        override fun toDomain(): AdvancementCriteria = AdvancementCriteria.KillCount(target)
    }

    @Serializable
    @SerialName("currency_earned")
    public data class CurrencyEarned(
        public val currency: Currency,
        public val amount: Long,
    ) : AdvancementCriteriaDto() {
        override fun toDomain(): AdvancementCriteria =
            AdvancementCriteria.CurrencyEarned(currency, amount)
    }

    @Serializable
    @SerialName("matches_won")
    public data class MatchesWon(public val count: Int) : AdvancementCriteriaDto() {
        override fun toDomain(): AdvancementCriteria = AdvancementCriteria.MatchesWon(count)
    }

    @Serializable
    @SerialName("item_acquired")
    public data class ItemAcquired(@SerialName("item_id") public val itemId: String) :
        AdvancementCriteriaDto() {
        override fun toDomain(): AdvancementCriteria =
            AdvancementCriteria.ItemAcquired(ItemId(itemId))
    }

    @Serializable
    @SerialName("all_of")
    public data class AllOf(public val children: List<AdvancementCriteriaDto>) :
        AdvancementCriteriaDto() {
        override fun toDomain(): AdvancementCriteria =
            AdvancementCriteria.AllOf(children.map { it.toDomain() })
    }

    @Serializable
    @SerialName("any_of")
    public data class AnyOf(public val children: List<AdvancementCriteriaDto>) :
        AdvancementCriteriaDto() {
        override fun toDomain(): AdvancementCriteria =
            AdvancementCriteria.AnyOf(children.map { it.toDomain() })
    }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class AdvancementRewardDto {

    public abstract fun toDomain(): AdvancementReward

    @Serializable
    @SerialName("grant_currency")
    public data class GrantCurrency(
        public val currency: Currency,
        public val amount: Long,
    ) : AdvancementRewardDto() {
        override fun toDomain(): AdvancementReward =
            AdvancementReward.GrantCurrency(currency, amount)
    }

    @Serializable
    @SerialName("grant_item")
    public data class GrantItem(
        @SerialName("item_id") public val itemId: String,
        public val count: Int = 1,
    ) : AdvancementRewardDto() {
        override fun toDomain(): AdvancementReward =
            AdvancementReward.GrantItem(ItemId(itemId), count)
    }

    @Serializable
    @SerialName("grant_title")
    public data class GrantTitle(@SerialName("title_id") public val titleId: String) :
        AdvancementRewardDto() {
        override fun toDomain(): AdvancementReward =
            AdvancementReward.GrantTitle(TitleId(titleId))
    }

    @Serializable
    @SerialName("grant_achievement")
    public data class GrantAchievement(
        @SerialName("achievement_id") public val achievementId: String,
    ) : AdvancementRewardDto() {
        override fun toDomain(): AdvancementReward =
            AdvancementReward.GrantAchievement(AchievementId(achievementId))
    }
}
