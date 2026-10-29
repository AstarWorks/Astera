package com.astarworks.astera.core.application.service.presentation

import com.astarworks.astera.core.application.config.AnnounceFrame
import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.application.port.outbound.presentation.IAnnouncer
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentationTable
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.AnnounceError
import com.astarworks.astera.core.domain.presentation.AnnounceTarget
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ServerName
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** The same production relay / codec runs in Paper and the two-server game simulator. */
public class CrossServerAnnouncer(
    private val server: ServerName,
    private val messenger: IProxyMessenger,
    private val table: IPresentationTable,
    private val presentation: IPresentation,
    private val onlinePlayers: () -> Collection<PlayerId>,
    private val dispatch: (PlayerId, () -> Unit) -> Unit,
) : IAnnouncer, AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    @Volatile private var active = true
    private val subscription = messenger.subscribe(ProxyChannel.ANNOUNCE) { message ->
        if (active && message.fromServer != server) {
            val frame = runCatching { Json.decodeFromString<AnnounceFrame>(message.payload.decodeToString()) }
                .getOrElse { log.error("Malformed announcement from {}: {}", message.fromServer, it.message); null }
            if (frame != null && targeted(frame)) {
                if (table.entry(frame.moment) == null) log.error("Unknown announcement moment '{}' from {}", frame.moment, message.fromServer)
                else playHere(frame)
            }
        }
    }

    override fun announce(moment: String, target: AnnounceTarget, args: Map<String, String>): Result<Unit, AnnounceError> {
        if (!active) return Result.Failure(AnnounceError.Closed)
        if (table.entry(moment) == null) {
            log.error("Unknown announcement moment '{}'", moment)
            return Result.Failure(AnnounceError.UnknownMoment(moment))
        }
        val frame = AnnounceFrame(moment, args.toMap(), (target as? AnnounceTarget.Server)?.name?.value)
        if (targeted(frame)) playHere(frame)
        if (frame.target == server.value) return Result.Success(Unit)
        val payload = Json.encodeToString(AnnounceFrame.serializer(), frame).encodeToByteArray()
        val result = when (target) {
            AnnounceTarget.All -> messenger.broadcast(ProxyChannel.ANNOUNCE, payload)
            is AnnounceTarget.Server -> messenger.sendMessage(target.name, ProxyChannel.ANNOUNCE, payload)
        }
        return result.mapError {
            log.warn("Announcement '{}' could not be relayed: {} (local delivery retained)", moment, it)
            AnnounceError.Transport(it)
        }
    }

    private fun targeted(frame: AnnounceFrame): Boolean = frame.target == null || frame.target == server.value

    private fun playHere(frame: AnnounceFrame) {
        onlinePlayers().forEach { player ->
            dispatch(player) {
                if (active && !presentation.play(frame.moment, player, frame.args))
                    log.error("Announcement moment '{}' could not be played for {}", frame.moment, player)
            }
        }
    }

    override fun close() { active = false; subscription.cancel() }
}
