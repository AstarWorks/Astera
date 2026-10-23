package com.astarworks.astera.core.application.bots

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol between anything that wants AI bots (lobby menu, `make bots`, tests) and the bot runner
 * service (`tools/bot-runner`, ADR-0247). Both ends share these types; JSON over two Redis pub/sub channels.
 *
 * A bot is a real protocol client: it logs in, queues for [BotRequest.lane] and plays like a player, so the
 * match stack needs no knowledge of it. Bot names start with [BotChannels.NAME_PREFIX].
 */
public object BotChannels {
    /** Publish a [BotRequest] here. */
    public const val REQUEST: String = "astera:bots:request"

    /** The runner publishes [BotStatus] here, one or more per request. */
    public const val STATUS: String = "astera:bots:status"

    /** Prefix of every bot account name (`Bot_Ash`); the lobby can use it to tell bots from players. */
    public const val NAME_PREFIX: String = "Bot_"
}

/**
 * Ask for [count] bots that queue for [lane] (a lane id of `content/matchmaking/queue.yaml`, e.g. `norma`).
 * [requestId] correlates the replies; [requestedBy] is a free label (player name, `make-bots`, ...).
 */
@Serializable
public data class BotRequest(
    val requestId: String,
    val lane: String,
    val count: Int,
    val requestedBy: String = "",
) {
    public fun toJson(): String = BotProtocol.json.encodeToString(serializer(), this)

    public companion object {
        public fun fromJson(text: String): BotRequest = BotProtocol.json.decodeFromString(serializer(), text)
    }
}

/** Progress of a [BotRequest]. [bots] are the account names involved; [reason] explains `failed`. */
@Serializable
public data class BotStatus(
    val requestId: String,
    val state: State,
    val bots: List<String> = emptyList(),
    val reason: String = "",
) {
    @Serializable
    public enum class State {
        /** Accepted; the bots are connecting. */
        @SerialName("spawning")
        SPAWNING,

        /** Every bot is in the lobby and has joined the lane's queue. */
        @SerialName("queued")
        QUEUED,

        /** Nothing (or not everything) was spawned: over the cap, bad request, login or queue failure. */
        @SerialName("failed")
        FAILED,
    }

    public fun toJson(): String = BotProtocol.json.encodeToString(serializer(), this)

    public companion object {
        public fun fromJson(text: String): BotStatus = BotProtocol.json.decodeFromString(serializer(), text)
    }
}

/** Shared JSON settings: unknown fields are ignored so either side can add fields first. */
public object BotProtocol {
    public val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
