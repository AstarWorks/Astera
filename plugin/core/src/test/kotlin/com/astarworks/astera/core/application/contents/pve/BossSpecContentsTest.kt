package com.astarworks.astera.core.application.contents.pve

import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.raid.RaidLockPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BossSpecContentsTest {

    @Test
    fun `bootstrap loads two-phase boss spec from YAML`(@TempDir tmp: Path) {
        Files.writeString(
            tmp.resolve("scorched-titan.yaml"),
            """
            id: astera:scorched-titan
            display_name:
              en: "Scorched Titan"
            base_mob_type: astera:scorched-titan-base
            phases:
              - index: 0
                name_key: boss.scorched_titan.phase.molten
                periodic_skills: [titan-stomp]
              - index: 1
                name_key: boss.scorched_titan.phase.enraged
                immunity_ticks: 60
                transition_cast: titan-roar
                periodic_skills: [titan-stomp, titan-meteor]
                on_hit_skills: [titan-counter]
            transitions:
              - type: hp_threshold
                from_phase: 0
                to_phase: 1
                hp_fraction: 0.5
            raid_lock:
              type: party_lock
              max_party_size: 4
            loot_table: astera:scorched-titan-loot
            record_kill: true
            """.trimIndent(),
        )
        val contents = BossSpecContents()
        val report = contents.bootstrap(tmp)
        assertThat(report.loaded).isEqualTo(1)
        val spec = contents.get(BossId("astera:scorched-titan"))!!
        assertThat(spec.phaseCount).isEqualTo(2)
        assertThat(spec.raidLock).isInstanceOf(RaidLockPolicy.PartyLock::class.java)
        assertThat(spec.lootTable.value).isEqualTo("astera:scorched-titan-loot")
        assertThat(contents.size()).isEqualTo(1)
    }

    @Test
    fun `failed YAML reported and good entries register`(@TempDir tmp: Path) {
        Files.writeString(
            tmp.resolve("good.yaml"),
            """
            id: astera:good-boss
            base_mob_type: astera:base
            phases:
              - index: 0
                name_key: p0
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("bad.yaml"),
            // No phases — BossSpec.init rejects empty phase list
            """
            id: astera:bad-boss
            base_mob_type: astera:base
            """.trimIndent(),
        )
        val contents = BossSpecContents()
        val report = contents.bootstrap(tmp)
        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.get(BossId("astera:good-boss"))).isNotNull
        assertThat(contents.get(BossId("astera:bad-boss"))).isNull()
    }
}
