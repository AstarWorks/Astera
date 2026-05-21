package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.entity.EntityCategory
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.mob.MobSpec
import com.astarworks.astera.core.domain.mob.MobTypeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MobContentsTest {

    private fun makeSpec(id: String) = MobSpec(
        id = MobTypeId(id),
        displayName = LocalizedText.EMPTY,
        baseEntityType = EntityCategory.MOB,
        attributes = SimpleAttributeContainer(),
    )

    @Test
    fun `register and find by id round-trips`() {
        val contents = MobContents()
        val spec = makeSpec("astera:wolf")
        contents.register(spec)
        assertThat(contents.find(MobTypeId("astera:wolf"))).isEqualTo(spec)
        assertThat(contents.all()).contains(spec)
    }

    @Test
    fun `categoryName is mobs`() {
        assertThat(MobContents().categoryName).isEqualTo("mobs")
    }

    @Test
    fun `bootstrap loads YAML files from a directory`(@TempDir tmp: Path) {
        Files.writeString(
            tmp.resolve("forest-wolf.yaml"),
            """
            id: astera:forest-wolf
            display_name:
              en: "Forest Wolf"
            attributes:
              mob.max_hp: 25.0
            ai: pathfind
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("cave-skeleton.yaml"),
            """
            id: astera:cave-skeleton
            display_name:
              en: "Cave Skeleton"
            attributes:
              mob.max_hp: 30.0
            ai: stationary
            """.trimIndent(),
        )

        val contents = MobContents()
        val report = contents.bootstrap(tmp)
        assertThat(report.loaded).isEqualTo(2)
        assertThat(report.failed).isEqualTo(0)

        assertThat(contents.find(MobTypeId("astera:forest-wolf"))).isNotNull
        assertThat(contents.find(MobTypeId("astera:cave-skeleton"))).isNotNull
    }

    @Test
    fun `reload is all-or-nothing — bad file preserves previous state`(@TempDir tmp: Path) {
        val good = """
            id: astera:good-mob
            display_name:
              en: "Good"
            attributes:
              mob.max_hp: 10.0
        """.trimIndent()

        Files.writeString(tmp.resolve("good.yaml"), good)
        val contents = MobContents()
        contents.bootstrap(tmp)
        assertThat(contents.find(MobTypeId("astera:good-mob"))).isNotNull

        // Replace with invalid content: ai value is unknown → toSpec fails.
        Files.writeString(
            tmp.resolve("good.yaml"),
            """
            id: astera:good-mob
            display_name:
              en: "Good"
            ai: not-a-real-hint
            """.trimIndent(),
        )

        val report = contents.reload(tmp)
        assertThat(report.failed).isGreaterThan(0)
        // Previous state is preserved (all-or-nothing swap).
        assertThat(contents.find(MobTypeId("astera:good-mob"))).isNotNull
    }
}
