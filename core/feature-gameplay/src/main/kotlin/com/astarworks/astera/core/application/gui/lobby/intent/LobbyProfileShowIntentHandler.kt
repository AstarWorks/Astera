package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.gui.lobby.ProfileCardGuiSpec
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerLevelView
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stats.PlayerStats
import org.slf4j.LoggerFactory

/**
 * Opens the player profile card in response to `lobby.profile.show`.
 *
 * The card is built per click from live data: account level + total XP
 * ([IPlayerLevelView]), combat and match stats ([IPlayerStatsRepository]) and
 * the wallet ([ICurrencyLedger]). Each source is a lazy provider because the
 * publishing plugins enable after core; a missing source renders zeros.
 */
public class LobbyProfileShowIntentHandler(
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
    private val levels: () -> IPlayerLevelView? = { null },
    private val stats: () -> IPlayerStatsRepository? = { null },
    private val unlocks: () -> com.astarworks.astera.core.application.port.outbound.IPlayerUnlockView? = { null },
    private val wallet: () -> ICurrencyLedger? = { null },
    private val cosmetics: () -> com.astarworks.astera.core.application.service.cosmetic.CosmeticService? = { null },
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_SHOW) {
            log.warn(
                "Unknown lobby.profile intent '{}' from player {} — payload={}",
                intent.key, player.id, intent.payload,
            )
            return
        }
        val mcPlayer = mcServer.findPlayer(player.id) ?: run {
            log.debug("Player {} disappeared before profile card open", player.id)
            return
        }
        if (!guiAdapter.open(mcPlayer, ProfileCardGuiSpec.build(snapshotOf(player.id)))) {
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_OPEN_REJECTED, emptyMap()),
            )
        }
    }

    private fun snapshotOf(id: PlayerId): ProfileCardGuiSpec.Snapshot {
        val s: PlayerStats? = stats()?.find(id)
        val ledger = wallet()
        val level = levels()
        return ProfileCardGuiSpec.Snapshot(
            unlocks = unlocks()?.unlocks(id)?.sorted() ?: emptyList(),
            equippedCosmetics = cosmetics()?.let { svc ->
                com.astarworks.astera.core.domain.cosmetic.CosmeticKind.entries.mapNotNull { svc.equipped(id, it)?.nameKey }
            } ?: emptyList(),
            level = level?.levelOf(id) ?: 1,
            totalXp = level?.totalXpOf(id) ?: 0L,
            kills = s?.kills ?: 0L,
            deaths = s?.deaths ?: 0L,
            assists = s?.assists ?: 0L,
            matchesPlayed = s?.matchesPlayed ?: 0L,
            wins = s?.wins ?: 0L,
            losses = s?.losses ?: 0L,
            gold = ledger?.balance(id, Currency.GOLD) ?: 0L,
        )
    }

    public companion object {
        public const val INTENT_PREFIX: String = "lobby.profile."
        public const val INTENT_SHOW: String = "lobby.profile.show"

        private val MSG_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
    }
}
