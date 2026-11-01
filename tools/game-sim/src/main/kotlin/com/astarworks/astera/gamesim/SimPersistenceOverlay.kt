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
    /** The shared stores this generation was built over; register a further generation with [SimPersistenceBacking.register]. */
    public val backing: SimPersistenceBacking,
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

        /**
         * Registers the overlay the way `AsteraPersistencePlugin.onLoad` does: generation 0 of the Persistence
         * lifecycle, on a registry view stamped with that generation's tag.
         */
        public fun install(services: ServiceRegistry): SimPersistenceOverlay = SimPersistenceBacking().register(services)
    }

    /**
     * The "database" behind the stand-in: the stores that hold the data. Every generation registers NEW wrapper
     * objects over these same stores (what a rewired Persistence does with fresh Postgres repository instances over
     * the same database), so state survives a generation swap while the registered instances change.
     */
    public class SimPersistenceBacking {
        private val store: IPlayerAttributeContainerStore = InMemoryPlayerAttributeContainer()
        private val sessions: IPlayerSessionRepository = InMemoryPlayerSessionRepository()
        private val vaults: IPersonalVaultRepository = InMemoryPersonalVaultRepository()
        private val snapshots: IPlayerInventorySnapshotRepository = InMemoryPlayerInventorySnapshotRepository()
        private val settings: IPlayerSettingsRepository = InMemoryPlayerSettingsRepository()
        private val profileCards: IProfileCardService = InMemoryProfileCardService()
        private val friendships: IFriendshipRepository = InMemoryFriendshipRepository()
        private val parties: IPartyRepository = InMemoryPartyRepository()
        private val profiles: IPlayerProfileRepository = InMemoryPlayerProfileRepository()
        private val xp: IPlayerXpRepository = InMemoryPlayerXpRepository()

        /** Builds one generation of wrappers over the shared stores and registers them at priority 100. */
        public fun register(services: ServiceRegistry): SimPersistenceOverlay {
            val overlay = SimPersistenceOverlay(
                OverlaySessions(sessions), OverlayVaults(vaults), OverlaySnapshots(snapshots), OverlaySettings(settings),
                OverlayProfileCards(profileCards), OverlayFriendships(friendships), OverlayParties(parties),
                OverlayAttributeStore(store), OverlayLedger(store), OverlayProfiles(profiles), OverlayXp(xp),
                this,
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
