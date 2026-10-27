package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IPersonalVaultRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IProfileCardService
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.usecase.vault.SaveVaultUseCase
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.core.domain.vault.PersonalVault
import com.astarworks.astera.stats.application.port.outbound.ILevelService
import com.astarworks.astera.stats.domain.level.Xp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0195: with the Persistence overlay registered before any wiring (as the
 * plugin's `onLoad` does), every consumer — Core's join listener, the wallet,
 * the vault use cases, the profile service — lands on the overlay instances.
 * No port may be overridden after a consumer resolved it.
 */
class PersistenceOverlayScenarioTest {

    @Test
    fun `one store serves every consumer when the overlay is registered first`() {
        scenario("persistence-overlay-one-store", persistenceOverlay = true) {
            val overlay = checkNotNull(sim.persistenceOverlay)
            val services = sim.services

            // The registry hands every consumer the overlay, never the priority-0 default.
            assertThat(services.get<IPlayerSessionRepository>()).isSameAs(overlay.sessions)
            assertThat(services.get<IPersonalVaultRepository>()).isSameAs(overlay.vaults)
            assertThat(services.get<ICurrencyLedger>()).isSameAs(overlay.ledger)
            assertThat(services.get<IProfileCardService>()).isSameAs(overlay.profileCards)

            val newcomer = player("newcomer")
            advance(1)

            // session: Core's join listener wrote to the overlay store.
            assertThat(overlay.sessions.find(newcomer.id)).isNotNull

            // wallet: a credit lands in the overlay ledger, whichever reader asks.
            val before = overlay.ledger.balance(newcomer.id, Currency.GOLD) // the first-join starter GOLD
            overlay.ledger.credit(newcomer.id, Currency.GOLD, 150L)
            assertThat(overlay.ledger.balance(newcomer.id, Currency.GOLD)).isEqualTo(before + 150L)
            assertThat(services.getOrThrow<ICurrencyLedger>().balance(newcomer.id, Currency.GOLD)).isEqualTo(before + 150L)

            // vault: the registered use case persists into the overlay.
            val vault = PersonalVault(newcomer.id, listOf(PersonalVault.VaultItem(slot = 0, itemId = "pistol", count = 1)))
            services.getOrThrow<SaveVaultUseCase>().save(vault)
            assertThat(overlay.vaults.find(newcomer.id)).isEqualTo(vault)

            // profile: the onboarding fact is in the overlay, so a restart cannot re-grant.
            assertThat(overlay.profiles.find(newcomer.id)).isNotNull

            // level: XP granted through the level service lands in the overlay store.
            services.getOrThrow<ILevelService>().grantXp(newcomer.id, Xp(250L), XpSource.AdminGrant)
            assertThat(overlay.xp.totalXp(newcomer.id)).isEqualTo(250L)

            // guard: nothing registered an override after a consumer resolved the port.
            assertThat(services.lateOverrides).isEmpty()
        }
    }

    @Test
    fun `stages are content and resolve with the persistence overlay present`() {
        scenario("persistence-overlay-stages", persistenceOverlay = true) {
            val stages = sim.services.getOrThrow<IStageRegistry>()
            assertThat(stages.find(StageId("example-arena"))).isNotNull
            assertThat(stages.all()).isNotEmpty
            assertThat(sim.services.getOrThrow<IMatchLifecycle>().start(StageId("example-arena")))
                .isInstanceOf(com.astarworks.astera.core.domain.Result.Success::class.java)
        }
    }

    @Test
    fun `plain boot without overlay also has no late override`() {
        scenario("persistence-overlay-none-no-late-override") {
            player("solo")
            advance(1)
            assertThat(sim.services.lateOverrides).isEmpty()
        }
    }
}
