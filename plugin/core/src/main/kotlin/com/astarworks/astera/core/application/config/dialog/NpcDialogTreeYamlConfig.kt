package com.astarworks.astera.core.application.config.dialog

import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.dialog.DialogChoice
import com.astarworks.astera.core.domain.dialog.DialogEndReason
import com.astarworks.astera.core.domain.dialog.DialogNode
import com.astarworks.astera.core.domain.dialog.DialogNodeId
import com.astarworks.astera.core.domain.dialog.DialogPredicate
import com.astarworks.astera.core.domain.dialog.DialogTreeId
import com.astarworks.astera.core.domain.dialog.NpcDialogTree
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.permission.Permission
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * YAML-shaped DTO for `content/dialogs/` files (Wave 5p, ADR-0088).
 *
 * # Wire format
 *
 * ```yaml
 * id: shopkeeper-greeting
 * root_node: greet
 * nodes:
 *   greet:
 *     type: text
 *     message_key: astera.dialog.shopkeeper.greet
 *     next_node: ask_purpose
 *   ask_purpose:
 *     type: choice
 *     prompt_key: astera.dialog.shopkeeper.ask_purpose
 *     options:
 *       - { label_key: astera.dialog.shopkeeper.option.shop, next_node: open_shop }
 *       - { label_key: astera.dialog.shopkeeper.option.leave, next_node: end_polite }
 *   open_shop:
 *     type: intent
 *     intent:
 *       key: shop.open
 *       payload: { shop_id: example-shop }
 *     next_node: end_polite
 *   end_polite:
 *     type: end
 *     reason: COMPLETED
 * ```
 */
@Serializable
public data class NpcDialogTreeYamlConfig(
    public val id: String,
    @SerialName("root_node") public val rootNode: String,
    public val nodes: Map<String, NpcDialogNodeDto>,
) {
    public fun toDomain(): NpcDialogTree = NpcDialogTree(
        id = DialogTreeId(id),
        rootNodeId = DialogNodeId(rootNode),
        nodes = nodes.entries.associate { (k, v) -> DialogNodeId(k) to v.toDomain() },
    )
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class NpcDialogNodeDto {
    public abstract fun toDomain(): DialogNode

    @Serializable
    @SerialName("text")
    public data class Text(
        @SerialName("message_key") public val messageKey: String,
        @SerialName("next_node") public val nextNode: String? = null,
    ) : NpcDialogNodeDto() {
        override fun toDomain(): DialogNode =
            DialogNode.Text(
                messageKey = MessageKey(messageKey),
                nextNodeId = nextNode?.let { DialogNodeId(it) },
            )
    }

    @Serializable
    @SerialName("choice")
    public data class Choice(
        @SerialName("prompt_key") public val promptKey: String,
        public val options: List<ChoiceOptionDto>,
    ) : NpcDialogNodeDto() {
        override fun toDomain(): DialogNode =
            DialogNode.Choice(
                promptKey = MessageKey(promptKey),
                options = options.map { it.toDomain() },
            )
    }

    @Serializable
    @SerialName("condition")
    public data class Condition(
        public val predicate: DialogPredicateDto,
        @SerialName("if_true") public val ifTrue: String,
        @SerialName("if_false") public val ifFalse: String,
    ) : NpcDialogNodeDto() {
        override fun toDomain(): DialogNode =
            DialogNode.Condition(
                predicate = predicate.toDomain(),
                ifTrue = DialogNodeId(ifTrue),
                ifFalse = DialogNodeId(ifFalse),
            )
    }

    @Serializable
    @SerialName("intent")
    public data class Intent(
        public val intent: GuiIntentDto,
        @SerialName("next_node") public val nextNode: String? = null,
    ) : NpcDialogNodeDto() {
        override fun toDomain(): DialogNode =
            DialogNode.Intent(
                intent = intent.toDomain(),
                nextNodeId = nextNode?.let { DialogNodeId(it) },
            )
    }

    @Serializable
    @SerialName("end")
    public data class End(public val reason: DialogEndReason = DialogEndReason.COMPLETED) :
        NpcDialogNodeDto() {
        override fun toDomain(): DialogNode = DialogNode.End(reason)
    }
}

@Serializable
public data class ChoiceOptionDto(
    @SerialName("label_key") public val labelKey: String,
    @SerialName("next_node") public val nextNode: String,
) {
    public fun toDomain(): DialogChoice = DialogChoice(
        labelKey = MessageKey(labelKey),
        nextNodeId = DialogNodeId(nextNode),
    )
}

@Serializable
public data class GuiIntentDto(
    public val key: String,
    public val payload: Map<String, String> = emptyMap(),
) {
    public fun toDomain(): GuiIntent = GuiIntent(key = key, payload = payload)
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class DialogPredicateDto {
    public abstract fun toDomain(): DialogPredicate

    @Serializable
    @SerialName("has_item")
    public data class HasItem(
        @SerialName("item_id") public val itemId: String,
        public val count: Int = 1,
    ) : DialogPredicateDto() {
        override fun toDomain(): DialogPredicate =
            DialogPredicate.HasItem(ItemId(itemId), count)
    }

    @Serializable
    @SerialName("has_currency")
    public data class HasCurrency(
        public val currency: Currency,
        public val amount: Long,
    ) : DialogPredicateDto() {
        override fun toDomain(): DialogPredicate =
            DialogPredicate.HasCurrency(currency, amount)
    }

    @Serializable
    @SerialName("has_advancement")
    public data class HasAdvancement(
        @SerialName("advancement_id") public val advancementId: String,
    ) : DialogPredicateDto() {
        override fun toDomain(): DialogPredicate =
            DialogPredicate.HasAdvancement(AdvancementId(advancementId))
    }

    @Serializable
    @SerialName("permission_check")
    public data class PermissionCheck(public val permission: String) : DialogPredicateDto() {
        override fun toDomain(): DialogPredicate =
            DialogPredicate.PermissionCheck(Permission(permission))
    }
}
