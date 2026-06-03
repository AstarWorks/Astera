package com.astarworks.astera.core.adapter.paper.broadcast

import com.astarworks.astera.core.domain.event.DomainEvent
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Polymorphic JSON codec for [DomainEvent] used by the cross-server
 * [LettuceRedisBroadcaster] (Wave 6c / ADR-0160). Closes the Wave 6b-3
 * "wire format deferred" TODO recorded inline in
 * [LettuceRedisBroadcaster.publish].
 *
 * # Wire shape
 *
 * Each encoded event is a single JSON object of the form
 *
 * ```
 * {"type": "<simple-class-name>", "payload": { ... event fields ... }}
 * ```
 *
 * The `type` discriminator is the Kotlin `KClass.simpleName` of the concrete
 * event (e.g. `"BlockBroken"`, `"ChatMessageSent"`, `"CurrencyChanged"`).
 * Subscribers re-hydrate by looking the discriminator up in their own
 * `Map<String, KClass<out DomainEvent>>` registry — the codec deliberately
 * does **not** ship a global subscriber-side dispatch table here because the
 * subscriber lives in a separate adapter (`RedisBroadcastSubscriber`, Wave
 * 6c+) and may evolve its own decoder shape.
 *
 * # Why Jackson, not kotlinx.serialization
 *
 * `DomainEvent` is intentionally **not sealed** (see [DomainEvent] KDoc) —
 * Kotlin's sealed hierarchies are confined to a single module, which would
 * defeat the multi-plugin layout. `kotlinx.serialization` polymorphism
 * needs either `@Serializable` on a closed hierarchy or an explicit
 * `SerializersModule` per concrete type, neither of which composes well
 * across the 7-plugin layout. Jackson + the kotlin module reflects over
 * the data-class structure without per-type registration; we apply a
 * `{type, payload}` envelope manually so the wire format stays stable
 * regardless of which feature plugin contributed the variant.
 *
 * # Boundary
 *
 * Lives in `core.adapter.paper.broadcast` (adapter layer). Jackson is an
 * I/O library and must not appear in `*.domain.*` or `*.application.*`
 * packages (Konsist will catch the import if anyone tries). Application
 * code calls into this class **only** through [LettuceRedisBroadcaster]'s
 * constructor injection — there is no application-layer port for "encode
 * this event".
 *
 * # Threading
 *
 * `ObjectMapper` is thread-safe after configuration; a single instance is
 * shared across publishes. Encoded payloads are immutable strings, so the
 * codec itself is stateless beyond the mapper handle.
 *
 * @param mapper Pre-configured Jackson [ObjectMapper]. Defaults to
 *   `jacksonObjectMapper()` (Kotlin module on) plus the JSR-310 java-time
 *   module so [java.time.Instant] (DomainEvent.occurredAt) round-trips as
 *   ISO-8601 strings rather than a numeric timestamp array. Tests may
 *   inject a stricter mapper if they want to assert field shape.
 */
public class BroadcastEventJsonCodec(
    private val mapper: ObjectMapper = defaultMapper(),
) {

    /**
     * Encode [event] to a single-line JSON string. Returns the envelope
     * `{"type": "<KClass.simpleName>", "payload": {...}}`.
     *
     * Anonymous and local classes have no `simpleName`; those are not
     * expected in this codec's traffic (every concrete [DomainEvent] in
     * `core.domain.event/` is a top-level `data class`). The codec
     * falls back to `qualifiedName` if `simpleName` is null, and finally
     * to `"UnknownEvent"`.
     */
    public fun encode(event: DomainEvent): String {
        val typeTag = event::class.simpleName
            ?: event::class.qualifiedName
            ?: UNKNOWN_TYPE_TAG
        val envelope = Envelope(type = typeTag, payload = event)
        return mapper.writeValueAsString(envelope)
    }

    /**
     * Decode a previously-encoded envelope back to a `(type, payload)`
     * pair. The payload returns as a [Map] because the codec is
     * type-agnostic — the subscriber side owns the
     * `discriminator → concrete class` registry. Returns `null` when
     * [json] does not parse as the expected envelope shape; the caller
     * is expected to log and drop.
     *
     * Provided primarily for the codec's own round-trip tests; production
     * subscribers will typically use a typed `readValue(payload,
     * KClass<out DomainEvent>)` once they have resolved the
     * discriminator.
     */
    public fun decodeEnvelope(json: String): DecodedEnvelope? {
        return runCatching {
            val raw: RawEnvelope = mapper.readValue(json)
            DecodedEnvelope(type = raw.type, payload = raw.payload)
        }.getOrNull()
    }

    /**
     * Decode the `payload` field of a previously-encoded envelope into the
     * concrete [DomainEvent] type [T]. Subscribers call this once they
     * have resolved the `type` discriminator to a Kotlin class via their
     * own registry. Returns `null` on parse failure; the caller logs and
     * drops.
     */
    public inline fun <reified T : DomainEvent> decodePayloadAs(json: String): T? {
        val envelope = decodeEnvelope(json) ?: return null
        return runCatching {
            // Re-serialize the Map payload through the mapper so Jackson
            // can target the concrete data class. Cheaper than holding a
            // JsonNode and avoids exposing Jackson types in the public API.
            val payloadJson = exposeMapper().writeValueAsString(envelope.payload)
            exposeMapper().readValue<T>(payloadJson)
        }.getOrNull()
    }

    /**
     * Internal accessor used by the inline [decodePayloadAs]. Inline +
     * reified functions cannot touch private state directly, so we expose
     * a `@PublishedApi internal` shim. Not part of the public contract.
     */
    @PublishedApi
    internal fun exposeMapper(): ObjectMapper = mapper

    /** Wire-format envelope. Internal — the public contract is the JSON string. */
    private data class Envelope(
        val type: String,
        val payload: DomainEvent,
    )

    /**
     * Mirror of [Envelope] with a generic-map payload so Jackson can decode
     * without knowing the concrete event type up front.
     */
    private data class RawEnvelope(
        val type: String = "",
        val payload: Map<String, Any?> = emptyMap(),
    )

    /**
     * Decoded view of an envelope. The payload is a generic `Map` because
     * this codec does not own the subscriber-side type registry — the
     * subscriber decides how to re-hydrate the payload into a typed
     * `DomainEvent` variant.
     */
    public data class DecodedEnvelope(
        public val type: String,
        public val payload: Map<String, Any?>,
    )

    public companion object {
        /**
         * Sentinel type tag used when a concrete event class has no
         * `simpleName` or `qualifiedName` (e.g. an anonymous inner class
         * — not currently used by any domain event variant, but kept as a
         * defensive fall-back).
         */
        public const val UNKNOWN_TYPE_TAG: String = "UnknownEvent"

        /**
         * Default Jackson mapper: kotlin-module on, java-time module on,
         * ISO-8601 timestamps, tolerant of unknown properties on decode
         * (so a subscriber on an older code revision does not blow up
         * when a publisher adds a field).
         */
        public fun defaultMapper(): ObjectMapper {
            return jacksonObjectMapper()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        }
    }
}
