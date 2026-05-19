package com.astarworks.astera.core.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.TutorialStarted
import com.astarworks.astera.core.domain.tutorial.PlayerTutorialState
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import org.slf4j.LoggerFactory

/**
 * Auto-start the configured default tutorial on a player's first join (Wave
 * 5h, ADR-0074 §3).
 *
 * Trigger rule (intentionally narrow so re-joins don't re-prompt):
 *
 *  1. Load (or default-construct) the player's [PlayerTutorialState].
 *  2. If `completedTutorialScriptIds` is empty **and** [defaultTutorialId] is
 *     not in `skippedTutorialScriptIds`, mark the default as "currently
 *     running step 0", upsert, and publish [TutorialStarted].
 *  3. Otherwise no-op — the player has either finished at least one tutorial
 *     before (returning veteran) or explicitly skipped the default (deliberate
 *     opt-out).
 *
 * The actual per-step rendering is `RunTutorialUseCase`'s job; this listener
 * only mutates the durable state slice + emits the lifecycle signal. Phase C
 * wiring is expected to subscribe `RunTutorialUseCase.start` to
 * [TutorialStarted] so the runner takes over.
 *
 * Wave 6b's `BukkitEventAdapter.onPlayerJoin` publishes [PlayerJoinedServer]
 * after the Bukkit `PlayerJoinEvent`; today there is no such publisher, so
 * this listener is wired-but-dormant. Driving from a domain event (rather
 * than the adapter-level `IMcEvent.PlayerJoined`) keeps the listener free of
 * Bukkit imports — the same `application/` Konsist rule that bans Paper from
 * the use cases applies here.
 */
public class StartTutorialOnFirstJoinListener(
    private val repo: IPlayerTutorialStateRepository,
    private val events: IDomainEventBus,
    private val defaultTutorialId: TutorialScriptId,
) {

    private val log = LoggerFactory.getLogger(StartTutorialOnFirstJoinListener::class.java)

    public fun handle(event: PlayerJoinedServer) {
        val playerId = event.playerId
        val current = repo.find(playerId) ?: PlayerTutorialState(playerId = playerId)

        if (current.completedTutorialScriptIds.isNotEmpty()) {
            // Returning veteran — don't re-prompt.
            return
        }
        if (defaultTutorialId in current.skippedTutorialScriptIds) {
            // Player previously skipped the default — respect the opt-out.
            return
        }
        if (current.currentTutorial == defaultTutorialId) {
            // Already running the default (e.g., started in a prior session and
            // persisted before completing). Don't reset the cursor.
            return
        }

        repo.upsert(current.startTutorial(defaultTutorialId))
        events.publish(TutorialStarted(playerId = playerId, tutorialId = defaultTutorialId))
        log.info("Auto-started default tutorial '{}' for player {}", defaultTutorialId, playerId)
    }
}
