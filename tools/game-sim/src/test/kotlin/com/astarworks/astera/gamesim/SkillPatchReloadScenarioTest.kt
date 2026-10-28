package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.compose.ComposeError
import com.astarworks.astera.core.application.skillengine.compose.ComposeOptions
import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * W1-26 (ADR-0303 rev4.1 section 10, 0301 Decision 7): `kind: Patch` composition on `astera/v3` documents, and per-skill
 * reload. Everything is inline YAML in a scratch dir, composed by the real [SkillComposer]; the assertions are on the
 * composed tree (which paths differ), the patch metadata, the errors, and what a recompose touches.
 */
class SkillPatchReloadScenarioTest {

    private val presentation = setOf("display", "particle", "sound")
    private val skinOnly = ComposeOptions(allowBalancePatches = false, presentationOps = presentation)
    private val balanceOn = ComposeOptions(allowBalancePatches = true, presentationOps = presentation)

    private val rapier = """
        apiVersion: astera/v3
        kind: Skill
        id: rapier
        params:
          range: 3.0
        machine:
          states:
            cast:
              enter:
                - id: glow
                  op: particle
                  color: "#ffffff"
                - id: swing
                  op: display
                  model: rapier_swing
              do:
                - id: hit
                  op: damage
                  amount: 6
                  range: 3.0
              on:
                - when: "true"
                  goto: done
            done:
              final: true
    """.trimIndent()

    private val other = rapier.replace("id: rapier", "id: lance")

    private fun write(dir: Path, name: String, text: String) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(name), text)
    }

    private fun compose(c: SkillComposer): Map<String, ComposedSkill> {
        val r = c.composeAll()
        assertThat(r).isInstanceOf(Result.Success::class.java)
        return c.composed()
    }

    private fun errorsOf(c: SkillComposer): List<ComposeError> = (c.composeAll() as Result.Failure).error

    private fun patch(patchKind: String, variant: String?, body: String): String = buildString {
        appendLine("apiVersion: astera/v3")
        appendLine("kind: Patch")
        appendLine("target: rapier")
        if (variant != null) appendLine("variant: $variant")
        appendLine("patchKind: $patchKind")
        append(body.trimIndent())
    }

    private fun flatten(path: String, v: Any?, out: MutableMap<String, Any?>) {
        when (v) {
            is Map<*, *> -> v.forEach { (k, x) -> flatten(if (path.isEmpty()) "$k" else "$path.$k", x, out) }
            is List<*> -> if (v.isNotEmpty() && v.all { it is Map<*, *> && it["id"] is String }) {
                v.forEach { e -> flatten("$path[${(e as Map<*, *>)["id"]}]", e, out) }
            } else {
                out[path] = v
            }
            else -> out[path] = v
        }
    }

    private fun diff(a: ComposedSkill, b: ComposedSkill): Set<String> {
        val fa = LinkedHashMap<String, Any?>().also { flatten("", a.tree, it) }
        val fb = LinkedHashMap<String, Any?>().also { flatten("", b.tree, it) }
        return (fa.keys + fb.keys).filter { fa[it] != fb[it] && it != "id" }.toSet()
    }

    @Test
    fun `skin patch variants change only presentation ops and carry patch metadata beside the tree`(@TempDir tmp: Path) {
        write(tmp, "rapier.yaml", rapier)
        write(tmp, "recolour.yaml", patch("skin", "blue", """
            machine:
              states:
                cast:
                  enter:
                    - id: glow
                      color: "#3a7bff"
        """))
        write(tmp, "swap.yaml", patch("skin", "ghost", """
            machine:
              states:
                cast:
                  enter:
                    - id: swing
                      model: ghost_swing
                    - id: glow
                      ${'$'}patch: delete
        """))
        val all = compose(SkillComposer(tmp, skinOnly))
        assertThat(all.keys).containsExactlyInAnyOrder("rapier", "rapier@blue", "rapier@ghost")
        val base = all.getValue("rapier")
        assertThat(base.patch.kind).isEqualTo("base")
        assertThat(base.tree).doesNotContainKey("_patch")
        assertThat(base.tree["apiVersion"]).isEqualTo("astera/v3")
        assertThat(base.tree["kind"]).isEqualTo("Skill")

        val blue = all.getValue("rapier@blue")
        assertThat(blue.patch.kind).isEqualTo("skin")
        assertThat(diff(base, blue)).containsExactly("machine.states.cast.enter[glow].color")
        assertThat(blue.tree["id"]).isEqualTo("rapier@blue")

        val ghost = all.getValue("rapier@ghost")
        assertThat(diff(base, ghost)).containsExactlyInAnyOrder(
            "machine.states.cast.enter[swing].model",
            "machine.states.cast.enter[glow].id",
            "machine.states.cast.enter[glow].op",
            "machine.states.cast.enter[glow].color",
        )
    }

    @Test
    fun `balance patch changes gameplay ops and loads only with the dev flag`(@TempDir tmp: Path) {
        write(tmp, "rapier.yaml", rapier)
        write(tmp, "tune.yaml", patch("balance", null, """
            params:
              range: 4.5
            machine:
              states:
                cast:
                  do:
                    - id: hit
                      range: 4.5
                      amount: 8
        """))
        assertThat(compose(SkillComposer(tmp, skinOnly)).getValue("rapier").patch.kind).isEqualTo("base")

        val tuned = compose(SkillComposer(tmp, balanceOn)).getValue("rapier")
        assertThat(tuned.patch.kind).isEqualTo("balance")
        assertThat(tuned.patch.touched).contains("params.range", "machine.states.cast.do[hit].amount")
        assertThat((tuned.tree["params"] as Map<*, *>)["range"]).isEqualTo(4.5)
    }

    @Test
    fun `unsupported patches are load errors`(@TempDir tmp: Path) {
        write(tmp, "rapier.yaml", rapier)

        // skin touching a gameplay op
        write(tmp, "bad.yaml", patch("skin", "x", """
            machine:
              states:
                cast:
                  do:
                    - id: hit
                      amount: 99
        """))
        val forbidden = errorsOf(SkillComposer(tmp, skinOnly))
        assertThat(forbidden.single()).isInstanceOf(ComposeError.PatchError.ForbiddenPath::class.java)
        assertThat((forbidden.single() as ComposeError.PatchError.ForbiddenPath).path).startsWith("machine.states.cast.do[hit]")

        // skin touching state wiring
        write(tmp, "bad.yaml", patch("skin", "x", """
            machine:
              states:
                cast:
                  then: done
        """))
        assertThat(errorsOf(SkillComposer(tmp, skinOnly)).single()).isInstanceOf(ComposeError.PatchError.ForbiddenPath::class.java)

        // delete of a missing entry
        write(tmp, "bad.yaml", patch("balance", null, """
            machine:
              states:
                cast:
                  do:
                    - id: nope
                      ${'$'}patch: delete
        """))
        assertThat(errorsOf(SkillComposer(tmp, balanceOn)).single()).isInstanceOf(ComposeError.PatchError::class.java)

        // unknown target
        write(tmp, "bad.yaml", patch("skin", null, "machine: {}").replace("target: rapier", "target: ghostblade"))
        assertThat(errorsOf(SkillComposer(tmp, skinOnly)).single()).isInstanceOf(ComposeError.PatchError.TargetNotFound::class.java)
    }

    @Test
    fun `id-less transition lists and parts compose`(@TempDir tmp: Path) {
        write(tmp, "rapier.yaml", rapier + "\n---\n" + """
            apiVersion: astera/v3
            kind: Part
            id: flash
            machine:
              states:
                run:
                  final: true
        """.trimIndent())
        write(tmp, "rewire.yaml", patch("balance", null, """
            machine:
              states:
                cast:
                  on:
                    - when: "false"
                      goto: cast
        """))
        val c = SkillComposer(tmp, balanceOn)
        val skills = compose(c)
        assertThat(c.composedParts().keys).containsExactly("flash")
        assertThat(skills.keys).containsExactly("rapier")
        val on = (((skills.getValue("rapier").tree["machine"] as Map<*, *>)["states"] as Map<*, *>)["cast"] as Map<*, *>)["on"] as List<*>
        assertThat(on).hasSize(1)
        assertThat((on.single() as Map<*, *>)["goto"]).isEqualTo("cast")
    }

    @Test
    fun `yaml anchors work inside one document and a cross-file alias is rejected`(@TempDir tmp: Path) {
        write(tmp, "a.yaml", rapier.replace("color: \"#ffffff\"", "color: &c \"#ffffff\"").replace("model: rapier_swing", "model: rapier_swing\n      tint: *c"))
        assertThat(compose(SkillComposer(tmp, skinOnly))).containsKey("rapier")

        write(tmp, "b.yaml", other.replaceFirst("  range: 3.0\nmachine:", "  range: *c\nmachine:"))
        val errs = errorsOf(SkillComposer(tmp, skinOnly))
        assertThat(errs.joinToString { it.reason }).contains("undefined alias")
    }

    @Test
    fun `reload recomposes only the dependents and a broken file leaves state untouched until fixed`(@TempDir tmp: Path) {
        write(tmp, "rapier.yaml", rapier)
        write(tmp, "lance.yaml", other)
        write(tmp, "recolour.yaml", patch("skin", "blue", """
            machine:
              states:
                cast:
                  enter:
                    - id: glow
                      color: "#3a7bff"
        """))
        val c = SkillComposer(tmp, skinOnly)
        compose(c)
        assertThat(c.dependents("recolour.yaml")).containsExactlyInAnyOrder("rapier", "rapier@blue")
        assertThat(c.filesOf("rapier")).contains("rapier.yaml", "recolour.yaml")

        // edit the patch: only the variant changes
        write(tmp, "recolour.yaml", patch("skin", "blue", """
            machine:
              states:
                cast:
                  enter:
                    - id: glow
                      color: "#00ff88"
        """))
        val changed = (c.recompose(listOf(tmp.resolve("recolour.yaml"))) as Result.Success).value
        assertThat(changed).containsExactly("rapier@blue")

        // break it: state is untouched
        val before = c.composed()
        write(tmp, "recolour.yaml", "apiVersion: astera/v3\nkind: Patch\ntarget: rapier\n")
        assertThat(c.recompose(listOf(tmp.resolve("recolour.yaml")))).isInstanceOf(Result.Failure::class.java)
        assertThat(c.composed()).isEqualTo(before)

        // fix it: recovers
        write(tmp, "recolour.yaml", patch("skin", "blue", """
            machine:
              states:
                cast:
                  enter:
                    - id: glow
                      color: "#112233"
        """))
        assertThat(c.recompose(listOf(tmp.resolve("recolour.yaml"))) is Result.Success).isTrue()
        assertThat(c.composed().getValue("rapier@blue")).isNotEqualTo(before.getValue("rapier@blue"))
    }
}
