package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** UI-12: a status landing on a player is silent in chat (founder 2026-10-28); `/astera status` returns the description; the codex GUI opens. */
class StatusLookupScenarioTest {

    private fun ScenarioScope.apply(target: SimEntity, id: String) {
        val registry = sim.services.getOrThrow<IStatusEffectRegistry>()
        sim.services.getOrThrow<IStatusEffectContainer>().apply(
            StatusEffectInstance(
                spec = checkNotNull(registry.find(StatusEffectId(id))),
                targetPlayerId = target.id,
                remainingTicks = 60,
                currentStacks = 1,
                appliedAtTick = sim.tick,
            ),
        )
    }

    @Test
    fun `a status landing on a player sends no chat line`() {
        scenario("status-lookup-applied") {
            val target = player("target", team = "red")
            val chat = checkNotNull(sim.server.view(target.id)).messages
            apply(target, "burn")
            apply(target, "freeze")
            assertThat(chat.filter { "/astera status" in it }).isEmpty()
        }
    }

    @Test
    fun `the status command lists, describes and opens the codex`() {
        scenario("status-lookup-command") {
            val p = player("reader")
            val chat = checkNotNull(sim.server.view(p.id)).messages
            val burnDesc = sim.services.getOrThrow<IMessageRenderer>().render(p.id, MessageKey("astera.statusdesc.burn"))
            assertThat(sim.command("reader", "status").succeeded).isTrue()
            assertThat(chat.joinToString("\n")).contains("/astera status burn", "/astera status silence")
            chat.clear()
            assertThat(sim.command("reader", "status burn").succeeded).isTrue()
            assertThat(chat.single()).contains(burnDesc)
            assertThat(sim.command("reader", "status nonsense").succeeded).isFalse()
            assertThat(sim.command("reader", "status codex").succeeded).isTrue()
            val view = checkNotNull(sim.server.view(p.id))
            assertThat(sim.services.getOrThrow<IGuiAdapter>().currentSpec(view)?.id?.value).isEqualTo("status-codex")
        }
    }
}
