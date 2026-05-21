package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.entity.EntityCategory
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.mob.MobAiHint
import com.astarworks.astera.core.domain.mob.MobBackendId
import com.astarworks.astera.core.domain.mob.MobError
import com.astarworks.astera.core.domain.mob.MobSkillBinding
import com.astarworks.astera.core.domain.mob.MobSpec
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MythicMobsMobAdapterTest {

    private val adapter = MythicMobsMobAdapter()

    @Test
    fun `backendId is the canonical MYTHICMOBS constant`() {
        assertThat(adapter.backendId()).isEqualTo(MobBackendId.MYTHICMOBS)
        assertThat(adapter.backendId().value).isEqualTo("mythicmobs")
    }

    @Test
    fun `Phase 1 skeleton reports unavailable`() {
        assertThat(adapter.isAvailable()).isFalse()
    }

    @Test
    fun `spawn returns BackendError in Phase 1 skeleton`() {
        val spec = MobSpec(
            id = MobTypeId("astera:test"),
            displayName = LocalizedText.EMPTY,
            baseEntityType = EntityCategory.MOB,
            attributes = SimpleAttributeContainer(),
        )
        val result = adapter.spawn(WorldId("test"), Vec3.ZERO, spec)
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isInstanceOf(MobError.BackendError::class.java)
    }

    @Test
    fun `applySkill and despawn return BackendError in Phase 1 skeleton`() {
        val ghost = EntityHandle(id = UUID.randomUUID(), backendId = MobBackendId.MYTHICMOBS.value)
        val skill = adapter.applySkill(ghost, MobSkillBinding.OnDeath(SkillId("x")))
        val despawn = adapter.despawn(ghost)
        assertThat(skill.isFailure).isTrue()
        assertThat(despawn.isFailure).isTrue()
    }

    @Test
    fun `supportsAi advertises full coverage so Wave 6b subclass needs no override`() {
        MobAiHint.entries.forEach { hint ->
            assertThat(adapter.supportsAi(hint)).withFailMessage("hint=$hint should be supported").isTrue()
        }
    }
}
