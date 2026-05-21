package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.config.AmplifyActionConfig
import com.astarworks.astera.combat.application.config.StatusEffectInteractionConfig
import com.astarworks.astera.combat.application.config.StatusEffectInteractionRuleConfig
import com.astarworks.astera.combat.application.config.StatusInteractionActionConfig
import com.astarworks.astera.combat.application.config.StatusInteractionTriggerConfig
import com.astarworks.astera.combat.application.config.SuppressActionConfig
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.combat.domain.status.interaction.StatusInteraction
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class YamlStatusEffectInteractionRuleTest {

    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-0000000000ff"))

    @Test
    fun `Pass when no rule matches the incoming effect`() {
        val resolver = YamlStatusEffectInteractionRule(StatusEffectInteractionConfig())
        val result = resolver.resolve(effect("burn"), listOf(effect("wet")))
        assertThat(result).isEqualTo(StatusInteraction.Pass)
    }

    @Test
    fun `Cancel rule fires when incoming + required existing both present`() {
        val resolver = YamlStatusEffectInteractionRule(
            StatusEffectInteractionConfig(
                rules = listOf(
                    StatusEffectInteractionRuleConfig(
                        id = "fire-cancels-frost",
                        trigger = StatusInteractionTriggerConfig(incoming = "burn", existing = listOf("freeze")),
                        action = StatusInteractionActionConfig(cancel = "freeze"),
                    ),
                ),
            ),
        )
        val result = resolver.resolve(effect("burn"), listOf(effect("freeze")))
        assertThat(result).isEqualTo(StatusInteraction.Cancel(StatusEffectId("freeze")))
    }

    @Test
    fun `Suppress rule yields a Suppress interaction with declared ticks`() {
        val resolver = YamlStatusEffectInteractionRule(
            StatusEffectInteractionConfig(
                rules = listOf(
                    StatusEffectInteractionRuleConfig(
                        id = "ice-immunity",
                        trigger = StatusInteractionTriggerConfig(incoming = "freeze"),
                        action = StatusInteractionActionConfig(
                            suppress = SuppressActionConfig(effect = "freeze", durationTicks = 60),
                        ),
                    ),
                ),
            ),
        )
        val result = resolver.resolve(effect("freeze"), emptyList())
        assertThat(result).isEqualTo(
            StatusInteraction.Suppress(StatusEffectId("freeze"), durationTicks = 60),
        )
    }

    @Test
    fun `Amplify rule scales the target effect's magnitude`() {
        val resolver = YamlStatusEffectInteractionRule(
            StatusEffectInteractionConfig(
                rules = listOf(
                    StatusEffectInteractionRuleConfig(
                        id = "shock-amplifies-wet",
                        trigger = StatusInteractionTriggerConfig(incoming = "shock", existing = listOf("wet")),
                        action = StatusInteractionActionConfig(
                            amplify = AmplifyActionConfig(effect = "wet", multiplier = 2.0),
                        ),
                    ),
                ),
            ),
        )
        val result = resolver.resolve(effect("shock"), listOf(effect("wet")))
        assertThat(result).isEqualTo(StatusInteraction.Amplify(StatusEffectId("wet"), 2.0))
    }

    @Test
    fun `first matching rule wins - later rules do not run`() {
        val resolver = YamlStatusEffectInteractionRule(
            StatusEffectInteractionConfig(
                rules = listOf(
                    StatusEffectInteractionRuleConfig(
                        id = "first",
                        trigger = StatusInteractionTriggerConfig(incoming = "burn", existing = listOf("freeze")),
                        action = StatusInteractionActionConfig(cancel = "freeze"),
                    ),
                    StatusEffectInteractionRuleConfig(
                        id = "second",
                        trigger = StatusInteractionTriggerConfig(incoming = "burn", existing = listOf("freeze")),
                        action = StatusInteractionActionConfig(
                            amplify = AmplifyActionConfig(effect = "freeze", multiplier = 5.0),
                        ),
                    ),
                ),
            ),
        )
        val result = resolver.resolve(effect("burn"), listOf(effect("freeze")))
        assertThat(result).isInstanceOf(StatusInteraction.Cancel::class.java)
    }

    @Test
    fun `rule without required existing matches regardless of current effects`() {
        val resolver = YamlStatusEffectInteractionRule(
            StatusEffectInteractionConfig(
                rules = listOf(
                    StatusEffectInteractionRuleConfig(
                        id = "global-burn-suppress",
                        trigger = StatusInteractionTriggerConfig(incoming = "burn"),
                        action = StatusInteractionActionConfig(
                            suppress = SuppressActionConfig(effect = "burn", durationTicks = 40),
                        ),
                    ),
                ),
            ),
        )
        val result = resolver.resolve(effect("burn"), emptyList())
        assertThat(result).isInstanceOf(StatusInteraction.Suppress::class.java)
    }

    @Test
    fun `multi-action config throws at construction`() {
        assertThatThrownBy {
            YamlStatusEffectInteractionRule(
                StatusEffectInteractionConfig(
                    rules = listOf(
                        StatusEffectInteractionRuleConfig(
                            id = "bad",
                            trigger = StatusInteractionTriggerConfig(incoming = "burn"),
                            action = StatusInteractionActionConfig(
                                cancel = "freeze",
                                suppress = SuppressActionConfig(effect = "burn", durationTicks = 20),
                            ),
                        ),
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must set exactly one of")
    }

    @Test
    fun `no-action config throws at construction`() {
        assertThatThrownBy {
            YamlStatusEffectInteractionRule(
                StatusEffectInteractionConfig(
                    rules = listOf(
                        StatusEffectInteractionRuleConfig(
                            id = "empty",
                            trigger = StatusInteractionTriggerConfig(incoming = "burn"),
                            action = StatusInteractionActionConfig(),
                        ),
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ruleIds returns rules in declaration order`() {
        val resolver = YamlStatusEffectInteractionRule(
            StatusEffectInteractionConfig(
                rules = listOf(
                    rule("first", "burn", cancel = "freeze"),
                    rule("second", "shock", cancel = "wet"),
                ),
            ),
        )
        assertThat(resolver.ruleIds()).containsExactly("first", "second")
    }

    private fun effect(id: String): StatusEffectInstance = StatusEffectInstance(
        spec = StatusEffectSpec(
            id = StatusEffectId(id),
            displayNameKey = MessageKey("astera.status.$id.name"),
            kind = StatusKind.BURN,
            magnitude = 1.0,
            stackable = false,
            maxStacks = 1,
        ),
        targetPlayerId = playerId,
        remainingTicks = 100,
        currentStacks = 1,
        appliedAtTick = 0L,
    )

    private fun rule(id: String, incoming: String, cancel: String): StatusEffectInteractionRuleConfig =
        StatusEffectInteractionRuleConfig(
            id = id,
            trigger = StatusInteractionTriggerConfig(incoming = incoming),
            action = StatusInteractionActionConfig(cancel = cancel),
        )
}
