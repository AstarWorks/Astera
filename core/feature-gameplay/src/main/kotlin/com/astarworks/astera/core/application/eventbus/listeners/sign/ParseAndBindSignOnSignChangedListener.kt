package com.astarworks.astera.core.application.eventbus.listeners.sign

import com.astarworks.astera.core.application.port.outbound.sign.ISignActionService
import com.astarworks.astera.core.application.service.sign.SignTextParser
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import org.slf4j.LoggerFactory

/**
 * Listener skeleton for the Wave 6b Bukkit `SignChangeEvent` adapter
 * (Wave 5block-embed, ADR-0120).
 *
 * # Status
 *
 * The Bukkit `SignChangeEvent` → domain-event bridge lands in Wave 6b
 * (see [adr/0111] for the event-adapter contract). Until that event
 * exists in `core.domain.event`, this listener is **publicly callable**
 * via [onSignChanged] but has no upstream event subscription wired in
 * `AsteraCorePlugin.onEnable`. Use cases / Wave 6b adapters call
 * [onSignChanged] directly with the parsed `(world, position, lines)`
 * tuple.
 *
 * # Why ship a skeleton in Wave 5block-embed
 *
 * Committing the listener + port now lets the texture / stage authoring
 * scripts wire up `[ASTERA] / teleport / world / x,y,z` magic signs
 * against [SignTextParser] today. When the Paper-side event surface
 * lands in Wave 6b, the wiring change is a single `eventBus.subscribe<SignChangedEvent>`
 * call in `AsteraCorePlugin.onEnable` — the parsing + binding logic
 * is already on disk and tested.
 *
 * # Behaviour
 *
 * On every sign-change call:
 *
 *  1. Parse the four lines via [SignTextParser.parse].
 *  2. If non-`null` — bind the action via [ISignActionService.bindSignAction].
 *  3. If `null` — call [ISignActionService.unbindSign] so a sign that
 *     used to be Astera-magic but has been edited back to plain text
 *     stops dispatching. Defensive: also covers the case where a sign
 *     was never magic to begin with (unbind returns false, no-op).
 */
public class ParseAndBindSignOnSignChangedListener(
    private val signActionService: ISignActionService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun onSignChanged(
        world: WorldId,
        position: Vec3,
        lines: List<String>,
    ) {
        val action = SignTextParser.parse(lines)
        if (action != null) {
            signActionService.bindSignAction(world, position, action)
            log.debug("Sign at {} in {} bound to {}", position, world, action::class.simpleName)
        } else {
            val removed = signActionService.unbindSign(world, position)
            if (removed) {
                log.debug("Sign at {} in {} unbound (no longer Astera-magic)", position, world)
            }
        }
    }
}
