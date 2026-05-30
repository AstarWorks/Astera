package com.astarworks.astera.core.application.eventbus.listeners.lifecycle

import com.astarworks.astera.core.domain.event.lifecycle.ServerReady
import com.astarworks.astera.core.domain.vanilla.VanillaFeature
import com.astarworks.astera.testfixtures.vanilla.FakeVanillaFeatureSuppressor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Wave L 5hardening-complete (ADR-0129) — pins the default-suppress
 * inventory and the listener's idempotent re-fire behavior.
 */
public class SuppressVanillaFeaturesOnServerBootListenerTest {

    @Test
    public fun `handle seeds suppressor with the 7 default-suppress features`() {
        val suppressor = FakeVanillaFeatureSuppressor()
        val listener = SuppressVanillaFeaturesOnServerBootListener(suppressor)

        listener.handle(ServerReady(readyAt = Instant.parse("2026-05-30T00:00:00Z")))

        assertThat(suppressor.suppressCalls)
            .`as`("every default-suppress feature must be suppressed exactly once")
            .containsExactlyInAnyOrderElementsOf(SuppressVanillaFeaturesOnServerBootListener.DEFAULT_SUPPRESSED)
        assertThat(suppressor.suppressed())
            .`as`("registry must hold every default-suppress feature after handle")
            .containsExactlyInAnyOrderElementsOf(SuppressVanillaFeaturesOnServerBootListener.DEFAULT_SUPPRESSED)
    }

    @Test
    public fun `default-suppress inventory excludes VanillaDeathMessage`() {
        assertThat(SuppressVanillaFeaturesOnServerBootListener.DEFAULT_SUPPRESSED)
            .`as`("Killfeed (ADR-0105) owns death-message cancellation; double-suppress would be redundant")
            .doesNotContain(VanillaFeature.VanillaDeathMessage)
    }

    @Test
    public fun `handle is idempotent across two ServerReady events`() {
        val suppressor = FakeVanillaFeatureSuppressor()
        val listener = SuppressVanillaFeaturesOnServerBootListener(suppressor)

        val first = ServerReady(readyAt = Instant.parse("2026-05-30T00:00:00Z"))
        val second = ServerReady(readyAt = Instant.parse("2026-05-30T00:00:01Z"))

        listener.handle(first)
        listener.handle(second)

        // The fake records every call; the *state* still holds only the
        // declared set (suppress is idempotent at the port-contract level).
        assertThat(suppressor.suppressCalls)
            .`as`("each fire re-invokes suppress per the contract — idempotency is on the impl, not the listener")
            .hasSize(SuppressVanillaFeaturesOnServerBootListener.DEFAULT_SUPPRESSED.size * 2)
        assertThat(suppressor.suppressed())
            .`as`("state remains exactly the seeded set after the second fire")
            .containsExactlyInAnyOrderElementsOf(SuppressVanillaFeaturesOnServerBootListener.DEFAULT_SUPPRESSED)
    }
}
