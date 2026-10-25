package com.astarworks.astera.core.application.eventbus.listeners.onboarding

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * On every [PlayerJoinedServer], send the joining player a single rotating
 * tip from a fixed catalogue (ADR-0139 §A — Wave AC 5onboarding-flow).
 *
 * # Why this listener
 *
 * Round 18 audit α surfaced that Astera had no "tip of the moment" /
 * "rotating help" surface — the only PlayerJoinedServer listeners were
 * inventory restore + first-time profile creation + population HUD push.
 * New + returning players received zero guidance text in chat. This
 * listener closes the gap with a single per-join chat line via
 * `IPlayerGateway.sendMessage` after `IMessageRenderer` resolves the
 * tip key for the player's locale.
 *
 * # Tip selection
 *
 * The catalogue is a fixed list of [MessageKey] tip identifiers (see
 * [HINT_KEYS]). A monotonic per-process counter selects which tip to
 * surface on each join, round-robin modulo the catalogue size. Two
 * properties matter:
 *
 *   1. Determinism for tests: a fresh counter produces a predictable
 *      sequence. Tests assert tip rotation by calling `handle()` N times
 *      and inspecting the recorded messages.
 *   2. No randomness in domain code: per CLAUDE.md §Coding posture, domain
 *      / application code avoids `Math.random()`. Counter rotation
 *      satisfies that.
 *
 * # Idempotency / failure
 *
 * No idempotency required — each `PlayerJoinedServer` is a distinct
 * lifecycle event; re-handling is a separate join. Renderer failures
 * log at WARN and swallow (tip is best-effort UX, not gameplay-critical).
 *
 * # Distinction from first-time onboarding
 *
 * [ShowWelcomeDialogOnFirstTimeProfileCreatedListener] and
 * [StartTutorialOnFirstTimeProfileCreatedListener] target
 * [com.astarworks.astera.core.domain.event.FirstTimeProfileCreated]
 * (one-shot per account). This listener fires on every
 * [PlayerJoinedServer] (every login, even for veteran players), giving
 * even experienced players a chance to discover a feature they don't use.
 */
public class HintOnPlayerJoinedListener(
    private val playerGateway: IPlayerGateway,
    private val messages: IMessageRenderer,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val counter: AtomicInteger = AtomicInteger(0)

    public fun handle(event: PlayerJoinedServer) {
        val index = counter.getAndIncrement().let { Math.floorMod(it, HINT_KEYS.size) }
        val tipKey = HINT_KEYS[index]
        try {
            val rendered = messages.render(playerId = event.playerId, key = tipKey)
            playerGateway.sendMessage(event.playerId, rendered)
        } catch (e: RuntimeException) {
            log.warn(
                "HintOnPlayerJoined: render/send failed for {} key={} : {}",
                event.playerId,
                tipKey.value,
                e.message,
            )
        }
    }

    public companion object {
        public val HINT_KEYS: List<MessageKey> = listOf(
            MessageKey("astera.tip.1"),
            MessageKey("astera.tip.2"),
            MessageKey("astera.tip.3"),
            MessageKey("astera.tip.4"),
            MessageKey("astera.tip.5"),
            MessageKey("astera.tip.6"),
        )
    }
}
