package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.Result
import com.astarworks.astera.domain.model.damage.DamageSource
import com.astarworks.astera.domain.model.damage.ResolvedDamage
import com.astarworks.astera.domain.model.i18n.MessageKey
import com.astarworks.astera.domain.model.player.PlayerId

/**
 * Outbound port: apply a [ResolvedDamage] to a player's hit points.
 *
 * The application layer computes damage via pure functions (`DamageRule.resolve`
 * etc.) and hands the result here. The Paper adapter translates to Bukkit's
 * damage system (via `Damageable.damage`), invokes any post-hit hooks, and
 * may refuse the damage (target offline / immune / already dead).
 *
 * Result failure modes — these are *expected* outcomes, not bugs.
 */
public interface IDamageGateway {
    public fun apply(
        target: PlayerId,
        resolved: ResolvedDamage,
        source: DamageSource,
    ): Result<Unit, DamageApplicationError>
}

public sealed class DamageApplicationError {
    public abstract val messageKey: MessageKey

    public data class TargetOffline(val targetId: PlayerId) : DamageApplicationError() {
        override val messageKey: MessageKey = MessageKey("astera.damage.target_offline")
    }
    public data class TargetAlreadyDead(val targetId: PlayerId) : DamageApplicationError() {
        override val messageKey: MessageKey = MessageKey("astera.damage.target_already_dead")
    }
    public data class Immune(val targetId: PlayerId) : DamageApplicationError() {
        override val messageKey: MessageKey = MessageKey("astera.damage.target_immune")
    }
}
