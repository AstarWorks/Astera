package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.contents.TutorialContents
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.ProfileError
import com.astarworks.astera.core.application.port.outbound.TransferResult
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.DurabilityPolicy
import com.astarworks.astera.core.domain.item.DropPolicy
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.StarterGrant
import com.astarworks.astera.core.domain.tutorial.TutorialScript
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.core.domain.tutorial.TutorialStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPlayerProfileServiceTest {

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `createFirstTime applies the full starter grant on the happy path`() {
        val grant = StarterGrant(
            starterItems = listOf(ItemId("example-sword"), ItemId("example-wand")),
            walletGrants = mapOf(Currency.GOLD to 100L),
            tutorialAutoEnroll = true,
            tutorialScriptId = "getting-started",
            welcomeDialogId = "astera-welcome",
        )
        val itemRegistry = ItemContents().also {
            it.register(ItemId("example-sword"), itemSpec("example-sword"))
            it.register(ItemId("example-wand"), itemSpec("example-wand"))
        }
        val tutorials = TutorialContents().also {
            it.register(
                TutorialScriptId("getting-started"),
                tutorialScript("getting-started"),
            )
        }
        val ledger = RecordingLedger()

        val service = InMemoryPlayerProfileService(
            starterGrant = grant,
            itemRegistry = itemRegistry,
            ledgerProvider = { ledger },
            tutorialRegistry = tutorials,
            nowEpochMs = { 1_700_000_000_000L },
        )

        val outcome = service.createFirstTime(playerId, displayName = "Alice", locale = "en")

        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        val profile = (outcome as Result.Success).value
        assertThat(profile.id).isEqualTo(playerId)
        assertThat(profile.displayName).isEqualTo("Alice")
        assertThat(profile.locale).isEqualTo("en")
        assertThat(profile.joinedAtEpochMs).isEqualTo(1_700_000_000_000L)

        assertThat(service.starterItemsFor(playerId)).hasSize(2)
        assertThat(ledger.credits).containsExactly(Credit(playerId, Currency.GOLD, 100L))
        assertThat(service.findTutorialEnrollment(playerId)).isEqualTo("getting-started")
        assertThat(service.findWelcomeDialog(playerId)).isEqualTo("astera-welcome")
    }

    @Test
    fun `createFirstTime returns AlreadyExists on the second call for the same player`() {
        val service = InMemoryPlayerProfileService(
            starterGrant = StarterGrant.EMPTY,
            itemRegistry = ItemContents(),
            ledgerProvider = { RecordingLedger() },
            tutorialRegistry = TutorialContents(),
        )

        val first = service.createFirstTime(playerId, "Alice", null)
        val second = service.createFirstTime(playerId, "Alice", null)

        assertThat(first).isInstanceOf(Result.Success::class.java)
        assertThat(second).isInstanceOf(Result.Failure::class.java)
        assertThat((second as Result.Failure).error)
            .isInstanceOf(ProfileError.AlreadyExists::class.java)
    }

    @Test
    fun `createFirstTime reports PartialGrant when a loadout item is unknown`() {
        val grant = StarterGrant(
            starterItems = listOf(ItemId("known"), ItemId("missing")),
            walletGrants = emptyMap(),
            tutorialAutoEnroll = false,
            tutorialScriptId = null,
            welcomeDialogId = null,
        )
        val itemRegistry = ItemContents().also {
            it.register(ItemId("known"), itemSpec("known"))
        }

        val service = InMemoryPlayerProfileService(
            starterGrant = grant,
            itemRegistry = itemRegistry,
            ledgerProvider = { RecordingLedger() },
            tutorialRegistry = TutorialContents(),
        )

        val outcome = service.createFirstTime(playerId, "Bob", null)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        val err = (outcome as Result.Failure).error
        assertThat(err).isInstanceOf(ProfileError.PartialGrant::class.java)
        assertThat((err as ProfileError.PartialGrant).failedSteps)
            .containsExactly("starter_items:missing")
        // The known item must still be granted.
        assertThat(service.starterItemsFor(playerId)).hasSize(1)
        // The profile must still exist — the join completes.
        assertThat(service.find(playerId)).isNotNull
    }

    @Test
    fun `createFirstTime reports failed wallet step when the ledger is missing`() {
        val grant = StarterGrant(
            starterItems = emptyList(),
            walletGrants = mapOf(Currency.GOLD to 50L),
            tutorialAutoEnroll = false,
            tutorialScriptId = null,
            welcomeDialogId = null,
        )

        val service = InMemoryPlayerProfileService(
            starterGrant = grant,
            itemRegistry = ItemContents(),
            ledgerProvider = { null },
            tutorialRegistry = TutorialContents(),
        )

        val outcome = service.createFirstTime(playerId, "Carol", null)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        val err = (outcome as Result.Failure).error as ProfileError.PartialGrant
        assertThat(err.failedSteps).containsExactly("wallet_grants:GOLD")
        // Profile is still created so the join completes.
        assertThat(service.find(playerId)).isNotNull
    }

    @Test
    fun `createFirstTime reports failed tutorial step when the script is unknown`() {
        val grant = StarterGrant(
            starterItems = emptyList(),
            walletGrants = emptyMap(),
            tutorialAutoEnroll = true,
            tutorialScriptId = "never-registered",
            welcomeDialogId = null,
        )

        val service = InMemoryPlayerProfileService(
            starterGrant = grant,
            itemRegistry = ItemContents(),
            ledgerProvider = { RecordingLedger() },
            tutorialRegistry = TutorialContents(),
        )

        val outcome = service.createFirstTime(playerId, "Dan", null)

        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        val err = (outcome as Result.Failure).error as ProfileError.PartialGrant
        assertThat(err.failedSteps).containsExactly("tutorial:never-registered")
        assertThat(service.findTutorialEnrollment(playerId)).isNull()
    }

    @Test
    fun `createFirstTime falls back to a placeholder displayName when blank`() {
        val service = InMemoryPlayerProfileService(
            starterGrant = StarterGrant.EMPTY,
            itemRegistry = ItemContents(),
            ledgerProvider = { RecordingLedger() },
            tutorialRegistry = TutorialContents(),
        )

        val outcome = service.createFirstTime(playerId, "   ", null)

        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        assertThat((outcome as Result.Success).value.displayName)
            .startsWith("Player-")
            .hasSize("Player-".length + 8)
    }

    private fun itemSpec(id: String): ItemSpec = ItemSpec(
        id = ItemId(id),
        template = ItemTemplate.Vanilla(material = "STONE"),
        displayName = LocalizedText(mapOf(LocaleCode.EN to id)),
        lore = LocalizedLore.EMPTY,
        rarity = Rarity.COMMON,
        levelRequirement = 1,
        attributes = ItemAttributes.EMPTY,
        equipSlot = EquipSlot.MAINHAND,
        durability = DurabilityPolicy.DEFAULT,
        dropPolicy = DropPolicy.DEFAULT,
        enchantable = true,
        glint = false,
        skillBindings = emptyList(),
        pdcTags = emptyMap(),
    )

    private fun tutorialScript(id: String): TutorialScript = TutorialScript(
        id = TutorialScriptId(id),
        displayKey = MessageKey("astera.tutorial.$id.display"),
        steps = listOf(
            TutorialStep.SendMessage(MessageKey("astera.tutorial.$id.step1")),
        ),
    )

    private data class Credit(val playerId: PlayerId, val currency: Currency, val amount: Long)

    /** Records ledger calls without any persistent state. */
    private class RecordingLedger : ICurrencyLedger {
        val credits: MutableList<Credit> = mutableListOf()
        override fun balance(playerId: PlayerId, currency: Currency): Long = 0L
        override fun wallet(playerId: PlayerId): Wallet = error("not used")
        override fun debit(playerId: PlayerId, currency: Currency, amount: Long): Result<Wallet, WalletError> =
            error("not used")
        override fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet {
            credits += Credit(playerId, currency, amount)
            // Return a fresh wallet — production impls would mutate persistent
            // state; the test only asserts the (playerId, currency, amount)
            // tuple, not the returned snapshot.
            return Wallet(SimpleAttributeContainer())
        }
        override fun transfer(
            from: PlayerId,
            to: PlayerId,
            currency: Currency,
            amount: Long,
        ): Result<TransferResult, WalletError> = error("not used")
    }
}
