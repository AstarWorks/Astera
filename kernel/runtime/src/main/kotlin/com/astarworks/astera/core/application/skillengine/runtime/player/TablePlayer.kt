package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.application.skillengine.runtime.CastError
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CancelScope
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill

/** Stub: W1-19 implements the player (cursors, event queue, sub-table pasting, Val.Live resolution, ends, cancel). */
public class TablePlayer(
    private val visual: ISkillVisualGateway,
    private val particles: ISkillParticleGateway,
    private val sounds: ISkillSoundGateway,
    private val gameplay: ISkillGameplayGateway,
    private val query: ISkillEntityQuery,
    private val live: LiveValueSource,
) : ISkillTablePlayer {
    override fun start(request: CastRequest, skill: CompiledSkill): Result<CastHandle, CastError> = TODO("W1-19: TablePlayer.start")

    override fun tick(now: Int): Unit = TODO("W1-19: TablePlayer.tick")

    override fun deliver(cast: CastHandle, event: RuntimeEvent): Unit = TODO("W1-19: TablePlayer.deliver")

    override fun cancel(cast: CastHandle, scope: CancelScope): Unit = TODO("W1-19: TablePlayer.cancel")

    override fun live(): List<CastHandle> = TODO("W1-19: TablePlayer.live")
}
