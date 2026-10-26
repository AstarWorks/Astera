package com.astarworks.astera.core.application.service.instance

import com.astarworks.astera.core.domain.game.InstanceId

/** Identity is allocated on Global; lifecycle mutations belong exclusively to this instance's strand. */
public class GameInstance(public val id: InstanceId, public val slot: Int?, public val seed: Long) {
    public enum class State { Allocating, Building, Ready, Running, Draining, Resetting, Free }
    @Volatile public var state: State = State.Allocating
        private set

    internal fun transition(next: State) {
        val allowed = when (state) {
            State.Allocating -> next == State.Building
            State.Building -> next == State.Ready || next == State.Draining
            State.Ready -> next == State.Running || next == State.Draining
            State.Running -> next == State.Draining
            State.Draining -> next == State.Resetting
            State.Resetting -> next == State.Free
            State.Free -> next == State.Allocating
        }
        check(allowed) { "Invalid instance transition: $state -> $next ($id)" }
        state = next
    }
}
