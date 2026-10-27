package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Composes real fixture YAML trees (src/test/resources/skill-compose) through the real composer. */
class ShippedSkillComposeTest {
    private fun fixture(name: String): Path =
        Path.of(javaClass.getResource("/skill-compose/$name")!!.toURI())

    private fun compose(name: String, balance: Boolean = false): Map<String, ComposedSkill> {
        val r = SkillComposer(fixture(name), ComposeOptions(allowBalancePatches = balance)).composeAll()
        check(r is Result.Success) { "compose $name failed: ${(r as Result.Failure).error}" }
        return r.value
    }

    @Test
    fun `one multi-document file and a split directory compose to the same tree`() {
        val one = compose("single")
        val split = compose("split")
        assertThat(one.keys).containsExactly("coin")
        assertThat(split.keys).containsExactly("coin")
        assertThat(split.getValue("coin").tree).isEqualTo(one.getValue("coin").tree)
        assertThat(split.getValue("coin").sources).containsExactly("coin/gameplay.yaml", "coin/presentation.yaml")
        assertThat(one.getValue("coin").tree["id"]).isEqualTo("coin")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `anchors and merge keys expand and a plain on key stays a string`() {
        val tree = compose("anchors").getValue("anchors").tree
        val hit = ((tree["gameplay"] as Map<String, Any?>)["on"] as Map<String, Any?>)["hit"] as List<Map<String, Any?>>
        assertThat(hit[0]).isEqualTo(mapOf("id" to "dmg", "op" to "damage", "amount" to 5))
        assertThat(hit[1]).isEqualTo(mapOf("id" to "second", "op" to "damage", "amount" to 1))
    }

    @Suppress("UNCHECKED_CAST")
    private fun meta(skill: ComposedSkill): Map<String, Any?> = skill.tree["_patch"] as Map<String, Any?>

    @Test
    fun `a skin variant changes one presentation colour by id and leaves the base alone`() {
        val all = compose("skin")
        assertThat(all.keys).containsExactlyInAnyOrder("coin", "coin@red") // balance variant is off by default
        fun colors(id: String) = ((all.getValue(id).tree["presentation"] as Map<String, Any?>)["colors"] as List<Map<String, Any?>>)
        assertThat(colors("coin")).containsExactly(mapOf("id" to "ring", "color" to "#ffffff"), mapOf("id" to "glow", "color" to "#00ff00"))
        assertThat(colors("coin@red")).containsExactly(mapOf("id" to "ring", "color" to "#ff0000")) // glow deleted via $patch
        assertThat(all.getValue("coin@red").tree["id"]).isEqualTo("coin@red")
        assertThat(all.getValue("coin@red").sources).containsExactly("coin.yaml", "coin-red.yaml")
        assertThat(meta(all.getValue("coin"))).isEqualTo(mapOf("kind" to "base", "touched" to emptyList<String>()))
        assertThat(meta(all.getValue("coin@red"))).isEqualTo(
            mapOf("kind" to "skin", "touched" to listOf("presentation.colors[glow]", "presentation.colors[ring].color")),
        )

        val balanced = compose("skin", balance = true)
        assertThat(balanced.keys).contains("coin@hard")
        assertThat(meta(balanced.getValue("coin@hard"))).isEqualTo(
            mapOf("kind" to "balance", "touched" to listOf("gameplay.params.range")),
        )
    }

    @Test
    fun `a skin patch touching gameplay is rejected with ForbiddenPath`() {
        val r = SkillComposer(fixture("forbidden")).composeAll()
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        val errors = (r as Result.Failure).error
        assertThat(errors).containsExactly(ComposeError.PatchError.ForbiddenPath("coin-bad.yaml", "gameplay.params.range"))
    }

    @Test
    fun `recompose of one file reports only the skill it feeds`() {
        val dir = Files.createTempDirectory("skill-compose-recompose")
        for (n in listOf("alpha.yaml", "beta.yaml")) Files.copy(fixture("recompose/$n"), dir.resolve(n), StandardCopyOption.REPLACE_EXISTING)
        val composer = SkillComposer(dir)
        assertThat(composer.composeAll()).isInstanceOf(Result.Success::class.java)
        assertThat(composer.dependents("alpha.yaml")).containsExactly("alpha")

        val alpha = dir.resolve("alpha.yaml")
        Files.writeString(alpha, Files.readString(alpha).replace("range: 1", "range: 7"))
        val changed = composer.recompose(listOf(alpha))
        assertThat((changed as Result.Success).value).containsExactly("alpha")
        assertThat(composer.composed().getValue("beta").tree).isEqualTo(compose("recompose").getValue("beta").tree)

        assertThat((composer.recompose(listOf(alpha)) as Result.Success).value).isEmpty()
    }
}
