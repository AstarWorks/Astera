package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.IGameModeScheduler
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.IGameMode

/**
 * Default [IGameModeScheduler] backed by Core's [IScheduler] (ADR-0106,
 * Wave 5gamemode).
 *
 * One [IScheduler.TaskHandle] per active match. A registration with
 * [IGameMode.tickPeriodTicks] returning `0L` tracks the (matchId → context)
 * pair but does not schedule a tick — useful for modes whose logic is purely
 * event-driven (e.g. BreakCore-style: react to BlockBroken, no polling).
 *
 * Tracks the most recent [GameModeContext] per match so callers that observe
 * a phase transition out-of-band can update the context without re-registering
 * (Phase 1 PoC: not yet exposed; the orchestrator re-registers on phase
 * transitions, which cancels + reschedules atomically).
 *
 * Thread-safety: single-threaded contract per [IGameModeScheduler]. The
 * orchestrator only calls register/unregister from the Paper main thread.
 */
public class DefaultGameModeScheduler(
    private val scheduler: IScheduler,
) : IGameModeScheduler {

    private data class Entry(
        val mode: IGameMode,
        val context: GameModeContext,
        val handle: IScheduler.TaskHandle?,
    )

    private val active: MutableMap<MatchId, Entry> = mutableMapOf()

    override fun register(matchId: MatchId, mode: IGameMode, context: GameModeContext): IGameModeScheduler.Registration {
        active[matchId]?.handle?.cancel()

        val handle: IScheduler.TaskHandle? = if (mode.tickPeriodTicks() > 0L) {
            scheduler.runRepeatingTicks(
                initialDelayTicks = mode.tickPeriodTicks(),
                periodTicks = mode.tickPeriodTicks(),
            ) {
                val current = active[matchId] ?: return@runRepeatingTicks
                runCatching {
                    current.mode.onTick(current.context, scheduler.currentTick())
                }
                // intentional swallow: a mode crashing during tick must not
                // bring down the match (see IGameMode kdoc Lifecycle contract).
            }
        } else {
            null
        }

        active[matchId] = Entry(mode = mode, context = context, handle = handle)

        return object : IGameModeScheduler.Registration {
            private var cancelled = false
            override fun cancel() {
                if (cancelled) return
                cancelled = true
                handle?.cancel()
                // intentionally NOT removing from `active` here — the orchestrator
                // owns the active-map and clears it via [unregister] for symmetry
                // with register. Handle-cancel is for error paths only.
            }
        }
    }

    override fun unregister(matchId: MatchId) {
        active.remove(matchId)?.handle?.cancel()
    }

    override fun activeMatches(): Set<MatchId> = active.keys.toSet()
}
