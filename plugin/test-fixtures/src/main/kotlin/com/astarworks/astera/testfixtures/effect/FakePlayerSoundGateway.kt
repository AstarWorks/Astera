package com.astarworks.astera.testfixtures.effect

import com.astarworks.astera.core.application.port.outbound.effect.IPlayerSoundGateway
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Recording [IPlayerSoundGateway] for tests (Wave 5effects / ADR-0109).
 */
public class FakePlayerSoundGateway : IPlayerSoundGateway {

    public data class Call(
        public val playerId: PlayerId,
        public val sound: SoundSpec,
        public val position: Vec3?,
        public val world: WorldId?,
    )

    public val calls: MutableList<Call> = mutableListOf()

    override fun playForViewer(playerId: PlayerId, sound: SoundSpec, position: Vec3?, world: WorldId?) {
        calls += Call(playerId, sound, position, world)
    }

    public fun clear() {
        calls.clear()
    }
}
