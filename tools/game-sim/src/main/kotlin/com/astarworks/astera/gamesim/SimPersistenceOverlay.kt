package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.IPersonalVaultRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerProfileRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.port.outbound.IProfileCardService
import com.astarworks.astera.core.application.profile.InMemoryProfileCardService
import com.astarworks.astera.core.application.service.InMemoryFriendshipRepository
import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.application.service.InMemoryPersonalVaultRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerProfileRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerSessionRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerSettingsRepository
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.stats.application.port.outbound.IPlayerXpRepository
import com.astarworks.astera.stats.application.service.InMemoryPlayerXpRepository

/**
 * Stand-in for Astera-Persistence (ADR-0195): distinct, separately-typed
 * repository instances registered at priority 100 BEFORE any wiring runs,
 * exactly when the real plugin registers its Postgres overlays (its `onLoad`).
 * Every consumer must end up on these instances and none on the priority-0
 * in-memory defaults; a scenario proves it by reading the state back from here.
 */
public class SimPersistenceOverlay private constructor(
    public val sessions: OverlaySessions,
    public val vaults: OverlayVaults,
    public val snapshots: OverlaySnapshots,
    public val settings: OverlaySettings,
    public val profileCards: OverlayProfileCards,
    public val friendships: OverlayFriendships,
    public val parties: OverlayParties,
    public val attributeStore: OverlayAttributeStore,
    public val ledger: OverlayLedger,
    public val profiles: OverlayProfiles,
    public val xp: OverlayXp,
) {
    public class OverlaySessions(delegate: IPlayerSessionRepository = InMemoryPlayerSessionRepository()) :
        IPlayerSessionRepository by delegate

    public class OverlayVaults(delegate: IPersonalVaultRepository = InMemoryPersonalVaultRepository()) :
        IPersonalVaultRepository by delegate

    public class OverlaySnapshots(
        delegate: IPlayerInventorySnapshotRepository = InMemoryPlayerInventorySnapshotRepository(),
    ) : IPlayerInventorySnapshotRepository by delegate

    public class OverlaySettings(delegate: IPlayerSettingsRepository = InMemoryPlayerSettingsRepository()) :
        IPlayerSettingsRepository by delegate

    public class OverlayProfileCards(delegate: IProfileCardService = InMemoryProfileCardService()) :
        IProfileCardService by delegate

    public class OverlayFriendships(delegate: IFriendshipRepository = InMemoryFriendshipRepository()) :
        IFriendshipRepository by delegate

    public class OverlayParties(delegate: IPartyRepository = InMemoryPartyRepository()) :
        IPartyRepository by delegate

    public class OverlayAttributeStore(delegate: IPlayerAttributeContainerStore = InMemoryPlayerAttributeContainer()) :
        IPlayerAttributeContainerStore by delegate

    public class OverlayProfiles(delegate: IPlayerProfileRepository = InMemoryPlayerProfileRepository()) :
        IPlayerProfileRepository by delegate

    public class OverlayXp(delegate: IPlayerXpRepository = InMemoryPlayerXpRepository()) :
        IPlayerXpRepository by delegate

    public class OverlayLedger(store: IPlayerAttributeContainerStore) :
        ICurrencyLedger by InMemoryCurrencyLedger(store)

    public companion object {
        private const val PRIORITY: Int = 100

        /** Registers the overlay the way `AsteraPersistencePlugin.onLoad` does. */
        public fun install(services: ServiceRegistry): SimPersistenceOverlay {
            val store = OverlayAttributeStore()
            val overlay = SimPersistenceOverlay(
                OverlaySessions(), OverlayVaults(), OverlaySnapshots(), OverlaySettings(),
                OverlayProfileCards(), OverlayFriendships(), OverlayParties(), store, OverlayLedger(store),
                OverlayProfiles(), OverlayXp(),
            )
            services.register<IPlayerSessionRepository>(overlay.sessions, PRIORITY)
            services.register<IPersonalVaultRepository>(overlay.vaults, PRIORITY)
            services.register<IPlayerInventorySnapshotRepository>(overlay.snapshots, PRIORITY)
            services.register<IPlayerSettingsRepository>(overlay.settings, PRIORITY)
            services.register<IProfileCardService>(overlay.profileCards, PRIORITY)
            services.register<IFriendshipRepository>(overlay.friendships, PRIORITY)
            services.register<IPartyRepository>(overlay.parties, PRIORITY)
            services.register<IPlayerAttributeContainerStore>(overlay.attributeStore, PRIORITY)
            services.register<ICurrencyLedger>(overlay.ledger, PRIORITY)
            services.register<IPlayerProfileRepository>(overlay.profiles, PRIORITY)
            services.register<IPlayerXpRepository>(overlay.xp, PRIORITY)
            return overlay
        }
    }
}
