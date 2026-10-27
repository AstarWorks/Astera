package com.astarworks.astera.core.application.bots

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire contract between the lobby ("Play vs bots") and the bot runner (ADR-0247 / ADR-0248).
 *
 * Both sides speak JSON over two plain Redis pub/sub channels, shared by the lobby plugin and the runner:
 *
 *  - [BotMatchWire.REQUEST_CHANNEL] `astera:bots:request` : lobby -> runner, one [BotRequest] per click.
 *  - [BotMatchWire.STATUS_CHANNEL]  `astera:bots:status`  : runner -> lobby, any number of [BotStatus] per request
 *    (`spawning`, then `queued`, or `failed`).
 *  - [BotMatchWire.RELEASE_CHANNEL] `astera:bots:release` : lobby -> runner, one [BotRelease] when the player the
 *    bots came for left the queue before the match formed; the runner logs those bots out.
 *
 * The runner is expected to log its bots in, queue them in [BotRequest.lane] and answer with the bot names.
 */
@Serializable
public data class BotRequest(
    /** Correlation id; echoed in every [BotStatus] answering this request. */
    val requestId: String,
    /** Queue lane id (`carry-tnt`, `annihilation`, ...): the lane the requesting player is queued in. */
    val lane: String,
    /** How many bots to bring. */
    val count: Int,
    /** User name of the player who asked (for runner logs only). */
    val requestedBy: String,
)

/** "The player who asked is gone": the runner disconnects every bot of [requestId] that is not in a match yet. */
@Serializable
public data class BotRelease(
    /** The [BotRequest.requestId] whose bots are no longer wanted. */
    val requestId: String,
    /** Why (for runner logs only). */
    val reason: String = "",
)

/** The runner's answer to a [BotRequest]. */
@Serializable
public data class BotStatus(
    val requestId: String,
    val state: BotState,
    /** User names of the bots involved so far. */
    val bots: List<String> = emptyList(),
    /** Human-readable cause when [state] is [BotState.FAILED]. */
    val reason: String? = null,
)

/** Lifecycle of one bot request. */
@Serializable
public enum class BotState {
    /** Bots are being created / logged in. */
    @SerialName("spawning")
    SPAWNING,

    /** All bots are in the lane's queue. */
    @SerialName("queued")
    QUEUED,

    /** The request cannot be served. */
    @SerialName("failed")
    FAILED,
}

/** Channel names and JSON codec of the bot-request protocol. */
public object BotMatchWire {
    public const val REQUEST_CHANNEL: String = "astera:bots:request"
    public const val STATUS_CHANNEL: String = "astera:bots:status"
    public const val RELEASE_CHANNEL: String = "astera:bots:release"

    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    public fun encode(request: BotRequest): String = json.encodeToString(BotRequest.serializer(), request)

    public fun encode(status: BotStatus): String = json.encodeToString(BotStatus.serializer(), status)

    public fun encode(release: BotRelease): String = json.encodeToString(BotRelease.serializer(), release)

    public fun decodeRelease(payload: String): BotRelease? =
        runCatching { json.decodeFromString(BotRelease.serializer(), payload) }.getOrNull()

    public fun decodeRequest(payload: String): BotRequest? =
        runCatching { json.decodeFromString(BotRequest.serializer(), payload) }.getOrNull()

    public fun decodeStatus(payload: String): BotStatus? =
        runCatching { json.decodeFromString(BotStatus.serializer(), payload) }.getOrNull()
}
