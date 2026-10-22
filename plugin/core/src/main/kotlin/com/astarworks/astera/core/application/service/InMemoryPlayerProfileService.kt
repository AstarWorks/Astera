package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerProfileRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerProfileService
import com.astarworks.astera.core.application.port.outbound.ITutorialRegistry
import com.astarworks.astera.core.application.port.outbound.ProfileError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile
import com.astarworks.astera.core.domain.player.StarterGrant
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe in-memory [IPlayerProfileService] (Wave 5e-fix).
 *
 * Holds profiles in a [ConcurrentHashMap] keyed by [PlayerId]. The Postgres
 * overlay that will replace this lives in `plugin:persistence` (deferred to
 * a later wave per CLAUDE.md §Pre-release posture — no shim until the
 * persistence layer is ready).
 *
 * # Starter grant application
 *
 * On [createFirstTime] success, the implementation walks the configured
 * [StarterGrant] and:
 *
 *   1. **Default loadout** — for every `ItemId`, queries [itemRegistry].
 *      Hits go into [grantedLoadout]; misses are logged and folded into
 *      [ProfileError.PartialGrant.failedSteps]. The profile itself is kept
 *      regardless — failing to grant a starter item must NOT block the
 *      join (CLAUDE.md says "the join must complete"). A future
 *      Wave-6b inventory adapter will consume [grantedLoadout] when
 *      handing the player into the lobby.
 *   2. **Wallet grants** — for each currency entry, calls
 *      [ledger.credit]. Negative amounts were rejected in YAML
 *      validation; the ledger credit path itself cannot fail (per
 *      [ICurrencyLedger] contract).
 *   3. **Tutorial enrollment** — when [StarterGrant.tutorialAutoEnroll]
 *      is `true`, the configured script id is resolved against
 *      [tutorialRegistry]. A miss is logged + folded into
 *      `failedSteps`; the actual per-player enrollment state repository
 *      is **deferred** (no `IPlayerTutorialStateRepository` exists on
 *      main yet — Wave 5e shipped the registry but not the per-player
 *      state). When that repo lands, this service flips to call
 *      `enrollDefault(playerId)` here.
 *   4. **Welcome dialog** — captured into [welcomeDialogId] and surfaced
 *      via [findWelcomeDialog] for adapters that gain an
 *      `IDialogGateway` (post-Wave-5i). Today this is a record-only
 *      field.
 *
 * # Idempotency
 *
 * A second [createFirstTime] for the same [PlayerId] returns
 * [ProfileError.AlreadyExists] without re-running the grant. This matches
 * the listener contract: "first-time = profile creation; returning =
 * Wave-5e inventory restore path".
 */
public class InMemoryPlayerProfileService(
    private val starterGrant: StarterGrant,
    private val itemRegistry: IItemRegistry,
    private val ledgerProvider: () -> ICurrencyLedger?,
    private val tutorialRegistry: ITutorialRegistry,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    /** Durable "already onboarded" store; Persistence overlays it (ADR-0187). */
    private val repository: IPlayerProfileRepository = InMemoryPlayerProfileRepository(),
) : IPlayerProfileService {

    private val log = LoggerFactory.getLogger(javaClass)


    /**
     * Items actually granted (post-registry-lookup) per player. The Phase 2
     * inventory adapter (Wave 6b) consumes this. Stored separately from
     * [profiles] so the profile record stays a small identity row.
     */
    private val grantedLoadout: ConcurrentHashMap<PlayerId, List<com.astarworks.astera.core.domain.item.ItemSpec>> =
        ConcurrentHashMap()

    /** Recorded welcome dialog id per player (when configured). */
    private val welcomeDialog: ConcurrentHashMap<PlayerId, String> = ConcurrentHashMap()

    /** Recorded tutorial enrollment per player (when [StarterGrant.tutorialAutoEnroll] is true and the script resolves). */
    private val tutorialEnrollment: ConcurrentHashMap<PlayerId, String> = ConcurrentHashMap()

    /** Monotonic counter used solely as a tie-breaker in [createFirstTime] / tests. */
    private val seq = AtomicLong(0)

    override fun find(playerId: PlayerId): PlayerProfile? = repository.find(playerId)

    override fun createFirstTime(
        playerId: PlayerId,
        displayName: String,
        locale: String?,
    ): Result<PlayerProfile, ProfileError> {
        val now = nowEpochMs()
        val placeholder = PlayerProfile(
            id = playerId,
            displayName = displayName.ifBlank { defaultDisplayName(playerId) },
            locale = locale,
            joinedAtEpochMs = now,
        )

        // putIfAbsent guarantees first-writer-wins under concurrent joins.
        if (!repository.insertIfAbsent(placeholder)) {
            return Result.failure(ProfileError.AlreadyExists(playerId))
        }
        seq.incrementAndGet()

        val failed = mutableListOf<String>()

        // 1. Default loadout — resolve every item id; skip unknowns.
        val resolvedLoadout = mutableListOf<com.astarworks.astera.core.domain.item.ItemSpec>()
        for (itemId in starterGrant.defaultLoadout) {
            val spec = itemRegistry.find(itemId)
            if (spec == null) {
                log.warn(
                    "Starter loadout item '{}' is not registered; skipping for player {}",
                    itemId,
                    playerId,
                )
                failed += "default_loadout:$itemId"
            } else {
                resolvedLoadout += spec
            }
        }
        if (resolvedLoadout.isNotEmpty()) {
            grantedLoadout[playerId] = resolvedLoadout.toList()
        }

        // 2. Wallet grants — credit each non-zero currency. The ledger
        //    provider is a lazy lookup because `ICurrencyLedger` is owned by
        //    the economy plugin and is registered after core's `onEnable`;
        //    if it is still null at first-join time (e.g. a stripped-down
        //    test wiring), the wallet step is recorded as failed.
        val ledger = ledgerProvider()
        if (ledger == null && starterGrant.walletGrants.any { it.value > 0 }) {
            log.warn(
                "Starter wallet grant skipped for {}: ICurrencyLedger is not registered.",
                playerId,
            )
            starterGrant.walletGrants
                .filterValues { it > 0 }
                .keys
                .forEach { currency -> failed += "wallet_grants:$currency" }
        } else if (ledger != null) {
            for ((currency, amount) in starterGrant.walletGrants) {
                if (amount <= 0) continue
                runCatching { ledger.credit(playerId, currency, amount) }.onFailure {
                    log.warn(
                        "Starter wallet grant {} {} failed for player {}: {}",
                        amount,
                        currency,
                        playerId,
                        it.message,
                    )
                    failed += "wallet_grants:$currency"
                }
            }
        }

        // 3. Tutorial enrollment — record only when the script resolves.
        if (starterGrant.tutorialAutoEnroll) {
            val scriptIdRaw = starterGrant.tutorialScriptId
            if (scriptIdRaw == null) {
                failed += "tutorial:missing_script_id"
            } else {
                val resolved = try {
                    tutorialRegistry.find(TutorialScriptId(scriptIdRaw))
                } catch (e: IllegalArgumentException) {
                    // Defensive — TutorialScriptId may validate at construction.
                    log.warn(
                        "Starter tutorial script id '{}' rejected by TutorialScriptId ctor for {}: {}",
                        scriptIdRaw,
                        playerId,
                        e.message,
                    )
                    null
                }
                if (resolved == null) {
                    log.warn(
                        "Starter tutorial script '{}' is not registered; skipping enrollment for player {}",
                        scriptIdRaw,
                        playerId,
                    )
                    failed += "tutorial:$scriptIdRaw"
                } else {
                    tutorialEnrollment[playerId] = scriptIdRaw
                }
            }
        }

        // 4. Welcome dialog — record the id; a future IDialogGateway will
        //    consume it. No-op when null.
        starterGrant.welcomeDialogId?.let { welcomeDialog[playerId] = it }

        return if (failed.isEmpty()) {
            Result.success(placeholder)
        } else {
            Result.failure(ProfileError.PartialGrant(playerId, failed.toList()))
        }
    }

    /** Test / adapter helper: items resolved for a created profile. Empty list when nothing was granted. */
    public fun loadoutFor(playerId: PlayerId): List<com.astarworks.astera.core.domain.item.ItemSpec> =
        grantedLoadout[playerId].orEmpty()

    /** Test / adapter helper: welcome dialog id recorded for a player, or `null`. */
    public fun findWelcomeDialog(playerId: PlayerId): String? = welcomeDialog[playerId]

    /** Test / adapter helper: tutorial script id the player was auto-enrolled in, or `null`. */
    public fun findTutorialEnrollment(playerId: PlayerId): String? = tutorialEnrollment[playerId]

    private fun defaultDisplayName(playerId: PlayerId): String {
        val short = playerId.uuid.toString().take(8)
        return "Player-$short"
    }
}
