package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IPlayerProfileService
import com.astarworks.astera.core.application.port.outbound.ProfileError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile

/**
 * Recording in-memory [IPlayerProfileService] for cross-plugin tests
 * (Wave 5e-fix).
 *
 * Unlike `InMemoryPlayerProfileService` (production / core wiring), this
 * fake has zero collaborators — it stores profiles, surfaces `createFirstTime`
 * calls + arguments via [calls], and lets the test script the next response
 * via [nextOutcome]. This makes it cheap to assert "the listener delegated
 * correctly" without spinning up an IItemRegistry / ICurrencyLedger /
 * ITutorialRegistry stack.
 *
 * @see com.astarworks.astera.core.application.service.InMemoryPlayerProfileService for
 *   the production impl with starter-grant application.
 */
public class FakePlayerProfileService(
    private val nowEpochMs: () -> Long = { 0L },
) : IPlayerProfileService {

    /** Per-call argument record for assertions. */
    public data class Call(
        val playerId: PlayerId,
        val displayName: String,
        val locale: String?,
    )

    public val calls: MutableList<Call> = mutableListOf()
    public val profiles: MutableMap<PlayerId, PlayerProfile> = linkedMapOf()

    /**
     * If non-null, the next [createFirstTime] returns this **without**
     * inspecting the profile map. Used to script PartialGrant / AlreadyExists
     * paths without seeding state. Consumed after one call.
     */
    public var nextOutcome: Result<PlayerProfile, ProfileError>? = null

    override fun find(playerId: PlayerId): PlayerProfile? = profiles[playerId]

    override fun createFirstTime(
        playerId: PlayerId,
        displayName: String,
        locale: String?,
    ): Result<PlayerProfile, ProfileError> {
        calls += Call(playerId, displayName, locale)

        nextOutcome?.let { scripted ->
            nextOutcome = null
            if (scripted is Result.Success) profiles[playerId] = scripted.value
            return scripted
        }

        if (profiles.containsKey(playerId)) {
            return Result.failure(ProfileError.AlreadyExists(playerId))
        }
        val profile = PlayerProfile(
            id = playerId,
            displayName = displayName.ifBlank { "Player-${playerId.uuid.toString().take(8)}" },
            locale = locale,
            joinedAtEpochMs = nowEpochMs(),
        )
        profiles[playerId] = profile
        return Result.success(profile)
    }

    override fun recordDisplayName(playerId: PlayerId, displayName: String) {
        if (displayName.isBlank()) return
        profiles[playerId]?.let { profiles[playerId] = it.copy(displayName = displayName) }
    }
}
