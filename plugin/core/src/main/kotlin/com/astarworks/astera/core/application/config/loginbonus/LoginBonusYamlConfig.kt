package com.astarworks.astera.core.application.config.loginbonus

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.loginbonus.LoginBonusReward
import com.astarworks.astera.core.domain.loginbonus.LoginBonusSpec
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/login-bonus/<name>.yaml` files
 * (Wave J 5server-content-ops, ADR-0127).
 *
 * Each file describes a complete (or partial) day-by-day rotation. Multiple
 * files may be present; their `days` lists are merged at load time and the
 * last-write-wins on duplicate `day`.
 *
 * # Wire format
 *
 * ```yaml
 * days:
 *   - day: 1
 *     reward: { currency: STAR, amount: 10 }
 *   - day: 7
 *     reward: { currency: STAR, amount: 100, items: [welcome-pack] }
 * ```
 */
@Serializable
public data class LoginBonusYamlConfig(
    public val days: List<LoginBonusDayDto>,
)

@Serializable
public data class LoginBonusDayDto(
    public val day: Int,
    public val reward: LoginBonusRewardDto,
) {
    public fun toDomain(): LoginBonusSpec = LoginBonusSpec(
        day = day,
        reward = reward.toDomain(),
    )
}

@Serializable
public data class LoginBonusRewardDto(
    public val currency: Currency,
    public val amount: Long,
    public val items: List<String> = emptyList(),
) {
    public fun toDomain(): LoginBonusReward = LoginBonusReward(
        currency = currency,
        amount = amount,
        items = items.map(::ItemId),
    )
}
