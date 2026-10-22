package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

public object PlayerTutorialStateTable : Table("player_tutorial_state") {
    public val playerId = javaUUID("player_id")
    public val scriptId = text("script_id")
    public val stepIndex = integer("step_index")
    public val startedAtTick = long("started_at_tick")
    public val paused = bool("paused")
    public val completed = bool("completed")
    public val rewardGranted = bool("reward_granted")
    override val primaryKey: PrimaryKey = PrimaryKey(playerId)
}
