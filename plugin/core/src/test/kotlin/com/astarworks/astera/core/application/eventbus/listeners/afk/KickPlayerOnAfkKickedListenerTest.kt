package com.astarworks.astera.core.application.eventbus.listeners.afk

import com.astarworks.astera.core.domain.event.PlayerAfkKicked
import com.astarworks.astera.core.domain.moderation.ModerationAction
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeModerationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5complete (ADR-0117 §Axis 3+5) — verify the AFK→Kick bridge
 * records a `ModerationAction.Kick` with the system issuer.
 */
class KickPlayerOnAfkKickedListenerTest {

    @Test
    fun `handle records a system-issued Kick on PlayerAfkKicked`() {
        val player = PlayerId(UUID.randomUUID())
        val moderation = FakeModerationService()
        val listener = KickPlayerOnAfkKickedListener(moderation)

        listener.handle(PlayerAfkKicked(playerId = player, durationSec = 130, atTick = 5000L))

        val applied = moderation.appliedActions
        assertThat(applied).hasSize(1)
        val action = applied.single()
        assertThat(action).isInstanceOf(ModerationAction.Kick::class.java)
        assertThat(action.playerId).isEqualTo(player)
        assertThat(action.issuedBy).isEqualTo(KickPlayerOnAfkKickedListener.SYSTEM_ISSUER_ID)
        assertThat(action.reasonKey).isEqualTo(KickPlayerOnAfkKickedListener.REASON_KEY)
    }
}
