package com.astarworks.astera.core.application.port.outbound.bots

import com.astarworks.astera.core.application.bots.BotRelease
import com.astarworks.astera.core.application.bots.BotRequest
import com.astarworks.astera.core.application.bots.BotStatus
import com.astarworks.astera.core.domain.Result

/**
 * Outbound port: ask the bot runner to fill a queue lane and hear back about it (ADR-0248).
 *
 * The Redis impl (priority 100) is registered when `ASTERA_BOTS_REDIS_URI` / `ASTERA_REDIS_URI` is
 * reachable; otherwise [UnavailableBotRequestGateway] answers every publish with [BotRequestError.Unavailable]
 * so the lobby can tell the player straight away.
 *
 * Status callbacks are delivered on the server main thread.
 */
public interface IBotRequestGateway {

    /** Publishes [request] on `astera:bots:request`. Success means "published", not "served". */
    public fun publish(request: BotRequest): Result<Unit, BotRequestError>

    /** Publishes [release] on `astera:bots:release`: the bots of that request are no longer wanted. */
    public fun release(release: BotRelease): Result<Unit, BotRequestError>

    /** Registers [handler] for every [BotStatus] seen on `astera:bots:status`. */
    public fun subscribe(handler: (BotStatus) -> Unit): Subscription

    public interface Subscription {
        public fun cancel()
    }
}

/** Why a [BotRequest] could not be published. */
public sealed class BotRequestError {
    /** No Redis connection is configured / reachable. */
    public data object Unavailable : BotRequestError()

    public data class PublishFailed(public val detail: String) : BotRequestError()
}

/** Default when Redis is not configured. */
public class UnavailableBotRequestGateway : IBotRequestGateway {
    override fun publish(request: BotRequest): Result<Unit, BotRequestError> =
        Result.Failure(BotRequestError.Unavailable)

    override fun release(release: BotRelease): Result<Unit, BotRequestError> =
        Result.Failure(BotRequestError.Unavailable)

    override fun subscribe(handler: (BotStatus) -> Unit): IBotRequestGateway.Subscription =
        object : IBotRequestGateway.Subscription {
            override fun cancel() = Unit
        }
}
