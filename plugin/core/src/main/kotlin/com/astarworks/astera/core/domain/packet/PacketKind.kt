package com.astarworks.astera.core.domain.packet

/**
 * Limited, Astera-curated enumeration of packet kinds (Wave 5y, ADR-0090).
 *
 * The full PacketEvents `PacketType` surface is hundreds of entries; Astera's
 * application layer only needs the kinds that map to its existing domain
 * concerns:
 *
 * - movement (anti-cheat in / anti-cheat out, Wave 5q),
 * - damage animation (combat HUD, Wave 5l),
 * - chat (chat-channel routing, Wave 5w),
 * - entity spawn / destroy (per-viewer visibility, this wave).
 *
 * Keeping this enumeration narrow is deliberate: any wave that wants to
 * intercept a new packet category MUST extend [PacketKind] *and* land a new
 * ADR clause explaining the broadened surface, so the core stays an
 * intentional API rather than a leak.
 *
 * Sealed so the compiler enforces exhaustive handling on consumer side; the
 * full set is small enough that adding a new variant always lights up every
 * `when` site in tests and Wave 6b adapters.
 */
public sealed interface PacketKind {

    /** Server → client position / look / teleport / velocity. */
    public data object MovementClientbound : PacketKind

    /** Client → server position / look update; Wave 5q anti-cheat tap. */
    public data object MovementServerbound : PacketKind

    /** Server → client entity damage animation / hurt sound. */
    public data object DamageAnimation : PacketKind

    /** Server → client chat / system message frame. */
    public data object ChatClientbound : PacketKind

    /** Server → client entity spawn (mob / player / display); per-viewer hide. */
    public data object EntitySpawn : PacketKind

    /** Server → client entity destroy / remove; per-viewer hide. */
    public data object EntityDestroy : PacketKind
}
