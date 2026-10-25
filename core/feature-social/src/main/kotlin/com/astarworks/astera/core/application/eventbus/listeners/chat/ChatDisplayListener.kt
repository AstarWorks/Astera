package com.astarworks.astera.core.application.eventbus.listeners.chat

import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerProfileService
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.domain.chat.ChatChannel
import com.astarworks.astera.core.domain.event.ChatMessageSent
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * On [ChatMessageSent], deliver the rendered chat line to its receivers
 * (ADR-0138 — Wave AB 5lobby-ux-complete).
 *
 * # Context
 *
 * Round 18 audit α surfaced that `ChatMessageSent` had two existing
 * subscribers (`AuditLogListener.handleChatMessageSent`,
 * `ReplayCaptureEngine`) — both audit / persistence concerns. **No
 * subscriber actually surfaced the message to other players.** Players
 * sent chat with `/astera chat say "..."` and no one saw it. This listener
 * closes that gap with a single per-event broadcast to the appropriate
 * channel recipients.
 *
 * # Channel routing (Phase 1 PoC)
 *
 *  - [ChatChannel.Global]: every online player from
 *    [IPlayerSessionRepository.all] receives the message.
 *  - [ChatChannel.Private]: the single `recipient` receives it. The
 *    sender does NOT also receive a copy — callers that want the
 *    "echo to sender" UX wire it at the use-case layer (Wave 6b polish).
 *  - [ChatChannel.Team] / [ChatChannel.Party]: log a WARN and skip.
 *    Team / Party routing requires `IMatchTeamLookup` / `IPartyRepository`
 *    inputs that vary by feature plugin; Phase 1 PoC keeps this listener
 *    Core-only. Wave 6b or a follow-up Round wires the channel-aware
 *    routing.
 *
 * # Format
 *
 * Phase 1 format: `<{senderDisplayName}> {content}`. The display name
 * resolves through [IPlayerProfileService.find]; if the profile is missing
 * the format falls back to the raw `playerId.uuid` short prefix. Wave 6b's
 * `PaperChatRenderer` will substitute the real Bukkit display name +
 * MiniMessage formatting; Phase 1 stays vendor-neutral.
 *
 * # Failure handling
 *
 * Per-recipient `IPlayerGateway.sendMessage` failure logs WARN + continues
 * to the next recipient. The chat broadcast is best-effort — one offline
 * recipient should not drop the message for every other recipient.
 */
public class ChatDisplayListener(
    private val playerGateway: IPlayerGateway,
    private val sessions: IPlayerSessionRepository,
    private val profileService: IPlayerProfileService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: ChatMessageSent) {
        val rendered = format(event)
        when (val channel = event.channel) {
            is ChatChannel.Global -> {
                val recipients = sessions.all().map { it.playerId }
                deliverTo(rendered, recipients)
            }
            is ChatChannel.Private -> {
                deliverTo(rendered, listOf(channel.recipient))
            }
            is ChatChannel.Team, is ChatChannel.Party -> {
                log.warn(
                    "ChatDisplayListener: channel routing for {} not wired in Phase 1 PoC; " +
                        "skipped delivery of message from {}",
                    channel::class.simpleName,
                    event.sender,
                )
            }
        }
    }

    private fun deliverTo(rendered: String, recipients: List<PlayerId>) {
        for (recipient in recipients) {
            try {
                playerGateway.sendMessage(recipient, rendered)
            } catch (e: RuntimeException) {
                log.warn(
                    "ChatDisplayListener: sendMessage failed for {} : {}",
                    recipient,
                    e.message,
                )
            }
        }
    }

    private fun format(event: ChatMessageSent): String {
        val senderName = profileService.find(event.sender)?.displayName
            ?: event.sender.uuid.toString().take(8)
        return "<$senderName> ${event.content}"
    }
}
