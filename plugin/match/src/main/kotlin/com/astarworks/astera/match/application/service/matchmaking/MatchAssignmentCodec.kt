package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.domain.matchmaking.AssignmentStatus
import com.astarworks.astera.match.domain.matchmaking.MatchAssignment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Binary wire format of the lobby / game protocol (ADR-0179), carried as the
 * payload of `ProxyChannel.MATCH_FOUND` (assignment) and `MATCH_STATUS`.
 * Decoders return `null` for anything malformed so a bad frame is dropped.
 */
public object MatchAssignmentCodec {

    private const val VERSION: Int = 2
    private const val STATUS_ACCEPTED: Int = 0
    private const val STATUS_REJECTED: Int = 1
    private const val STATUS_ENDED: Int = 2

    public fun encode(assignment: MatchAssignment): ByteArray = write {
        it.writeInt(VERSION)
        it.writeUTF(assignment.id.value)
        it.writeUTF(assignment.stageId.value)
        it.writeBoolean(assignment.modeKey != null)
        assignment.modeKey?.let(it::writeUTF)
        it.writeInt(assignment.teams.size)
        assignment.teams.forEach { team ->
            it.writeInt(team.size)
            team.forEach { player -> it.writeUTF(player.uuid.toString()) }
        }
        it.writeBoolean(assignment.forced)
    }

    public fun decodeAssignment(bytes: ByteArray): MatchAssignment? = read(bytes) { input ->
        require(input.readInt() == VERSION)
        val id = MatchId(input.readUTF())
        val stage = StageId(input.readUTF())
        val modeKey = if (input.readBoolean()) input.readUTF() else null
        val teamCount = input.readInt().also { n -> require(n in 1..MAX_TEAMS) }
        val teams = List(teamCount) {
            val size = input.readInt().also { n -> require(n in 0..MAX_TEAM_SIZE) }
            List(size) { PlayerId(UUID.fromString(input.readUTF())) }
        }
        MatchAssignment(id, stage, modeKey, teams, forced = input.readBoolean())
    }

    public fun encode(status: AssignmentStatus): ByteArray = write {
        it.writeInt(VERSION)
        it.writeUTF(status.assignmentId.value)
        when (status) {
            is AssignmentStatus.Accepted -> it.writeInt(STATUS_ACCEPTED)
            is AssignmentStatus.Rejected -> {
                it.writeInt(STATUS_REJECTED)
                it.writeUTF(status.reason)
            }
            is AssignmentStatus.Ended -> it.writeInt(STATUS_ENDED)
        }
    }

    public fun decodeStatus(bytes: ByteArray): AssignmentStatus? = read(bytes) {
        require(it.readInt() == VERSION)
        val id = MatchId(it.readUTF())
        when (it.readInt()) {
            STATUS_ACCEPTED -> AssignmentStatus.Accepted(id)
            STATUS_REJECTED -> AssignmentStatus.Rejected(id, it.readUTF())
            STATUS_ENDED -> AssignmentStatus.Ended(id)
            else -> error("unknown status")
        }
    }

    private const val MAX_TEAMS = 16
    private const val MAX_TEAM_SIZE = 64

    private fun write(block: (DataOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use(block)
        return out.toByteArray()
    }

    private fun <T> read(bytes: ByteArray, block: (DataInputStream) -> T): T? =
        runCatching { DataInputStream(ByteArrayInputStream(bytes)).use(block) }.getOrNull()
}
