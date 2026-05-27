package com.astarworks.astera.core.application.eventbus.listeners.quest

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.domain.event.QuestStageCompleted
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * On [QuestStageCompleted], surface a stub HUD update to the player's
 * action bar and log the chain-advance hop (Wave H 5content-depth, ADR-0125).
 *
 * # Why this listener
 *
 * `ProgressQuestUseCase` publishes [QuestStageCompleted] (Wave 5s, ADR-0091)
 * but Phase 1 PoC had no subscriber, so a stage clearing was a silent drop
 * from the player's perspective. This listener closes the gap:
 *
 *   1. **HUD update** — pushes a `HudLayout.ActionBar` stub through
 *      `IHudGateway`. `InMemoryHudGateway` records the call and emits
 *      `HudShown`; Wave 6b's `PaperHudGateway` carries the same payload
 *      through to the screen unchanged.
 *   2. **Chain audit log** — at `INFO` when the stage advanced (next stage
 *      present) so operators can correlate quest pacing without enabling
 *      `DEBUG`. Terminal-stage hops also publish [com.astarworks.astera.core.domain.event.QuestChainCompleted]
 *      from the use case; this listener stays scope-pure (no extra publish).
 *
 * # Idempotency
 *
 * `QuestStageCompleted` is published once per actual stage transition
 * by the use case, so the listener never sees duplicate handles for the
 * same (player, chain, stage) triple. Re-handling the same event is
 * harmless — HUD `showFor` upserts an actionbar handle for the player.
 *
 * # MessageKey
 *
 * `astera.quest.progress.stage-completed` is the HUD placeholder key the
 * renderer resolves with `{chainId}` / `{stageId}` substitutions once
 * placeholder support lands.
 */
public class UpdateQuestProgressOnQuestStageCompletedListener(
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: QuestStageCompleted) {
        val key = if (event.nextStageId == null) {
            MessageKey("astera.quest.progress.chain-completed")
        } else {
            MessageKey("astera.quest.progress.stage-completed")
        }
        val layout = HudLayout.ActionBar(
            component = HudComponent.Text(
                messageKey = key,
                color = HudColor.GOLD,
            ),
            ttlTicks = 80,
        )
        hudGateway.showFor(event.playerId, layout).also { result ->
            if (result is com.astarworks.astera.core.domain.Result.Failure) {
                log.debug(
                    "HUD quest-progress show failed for {} ({} → {}): {}",
                    event.playerId,
                    event.chainId,
                    event.completedStageId,
                    result.error,
                )
            }
        }

        if (event.nextStageId != null) {
            log.info(
                "Quest stage advanced: player={} chain={} cleared={} next={}",
                event.playerId,
                event.chainId,
                event.completedStageId,
                event.nextStageId,
            )
        } else {
            log.info(
                "Quest chain terminal stage cleared: player={} chain={} cleared={}",
                event.playerId,
                event.chainId,
                event.completedStageId,
            )
        }
    }
}
