package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.compose.ComposeError
import com.astarworks.astera.core.application.skillengine.compose.ComposeOptions
import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.load.SkillEngineRegistries
import com.astarworks.astera.core.application.skillengine.load.SkillLoadReport
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

    // The default presentation op set comes from the registered specs (SkillEngineSpecs); an empty registry makes these cases fail.
    private val skinOnly = ComposeOptions(allowBalancePatches = false)
    private val balanceOn = ComposeOptions(allowBalancePatches = true)

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

    /** Presentation timeline, a group, and a projectile with `on.<event>` handlers (W1-26 skin scope). */
    private val bolt = """
        apiVersion: astera/v3
        kind: Skill
        id: bolt
        params:
          range: 8.0
        presentation:
          timeline:
            - id: aura
              op: particle
              color: "#ffffff"
              at: 2
            - id: pack
              do:
                - id: spark
                  op: sound
                  sound: block.note_block.bell
                  volume: 0.5
                - id: dmg
                  op: damage
                  amount: 1
        machine:
          start: cast
          states:
            cast:
              do:
                - id: shot
                  op: projectile
                  speed: 1.0
                  on:
                    hit:
                      - id: boom
                        op: particle
                        count: 3
                      - id: pain
                        op: damage
                        amount: 4
              on:
                - when: "true"
                  goto: done
            done:
              final: true
    """.trimIndent()

    private val flashPart = """
        apiVersion: astera/v3
        kind: Part
        id: flash
        machine:
          start: run
          states:
            run:
              for: 4
              then: end
              enter:
                - id: glow
                  op: particle
                  color: "#ffffff"
            end:
              final: true
    """.trimIndent()

    private val caller = """
        apiVersion: astera/v3
        kind: Skill
        id: caller
        machine:
          start: step
          states:
            step:
              use: flash
              on:
                - event: step.end
                  to: done
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

    private fun patch(patchKind: String, variant: String?, body: String, target: String = "rapier"): String = buildString {
        appendLine("apiVersion: astera/v3")
        appendLine("kind: Patch")
        appendLine("target: $target")
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

    @Test
    fun `skin patch reaches presentation timeline ops group children and on-handler lists with their timing`(@TempDir tmp: Path) {
        write(tmp, "bolt.yaml", bolt)
        write(tmp, "skin.yaml", patch("skin", "gold", """
            presentation:
              timeline:
                - id: aura
                  color: "#ffcc00"
                  at: 5
                - id: pack
                  do:
                    - id: spark
                      volume: 1.0
            machine:
              states:
                cast:
                  do:
                    - id: shot
                      on:
                        hit:
                          - id: boom
                            count: 9
        """, target = "bolt"))
        val all = compose(SkillComposer(tmp, skinOnly))
        assertThat(diff(all.getValue("bolt"), all.getValue("bolt@gold"))).containsExactlyInAnyOrder(
            "presentation.timeline[aura].color",
            "presentation.timeline[aura].at",
            "presentation.timeline[pack].do[spark].volume",
            "machine.states.cast.do[shot].on.hit[boom].count",
        )
        assertThat(all.getValue("bolt@gold").patch.kind).isEqualTo("skin")
    }

    @Test
    fun `skin patch outside the presentation scope is a ForbiddenPath`(@TempDir tmp: Path) {
        write(tmp, "bolt.yaml", bolt)
        fun forbidden(body: String): String {
            write(tmp, "skin.yaml", patch("skin", "x", body, target = "bolt"))
            return (errorsOf(SkillComposer(tmp, skinOnly)).single() as ComposeError.PatchError.ForbiddenPath).path
        }
        // a gameplay op inside an on.<event> list
        assertThat(forbidden("""
            machine:
              states:
                cast:
                  do:
                    - id: shot
                      on:
                        hit:
                          - id: pain
                            amount: 99
        """)).startsWith("machine.states.cast.do[shot].on.hit[pain]")
        // the projectile's own fields
        assertThat(forbidden("""
            machine:
              states:
                cast:
                  do:
                    - id: shot
                      speed: 5.0
        """)).startsWith("machine.states.cast.do[shot].speed")
        // a new event key is `on` structure
        assertThat(forbidden("""
            machine:
              states:
                cast:
                  do:
                    - id: shot
                      on:
                        bounce:
                          - id: ding
                            op: sound
                            sound: block.anvil.land
        """)).startsWith("machine.states.cast.do[shot].on.bounce")
        // group own timing, gameplay child of a group
        assertThat(forbidden("""
            presentation:
              timeline:
                - id: pack
                  at: 3
        """)).startsWith("presentation.timeline[pack].at")
        assertThat(forbidden("""
            presentation:
              timeline:
                - id: pack
                  do:
                    - id: dmg
                      amount: 50
        """)).startsWith("presentation.timeline[pack].do[dmg]")
        // params / anchors / a new state / state structure / use
        assertThat(forbidden("params: {range: 20.0}")).startsWith("params")
        assertThat(forbidden("anchors: [{id: hand, anchor: caster.hand_r}]")).startsWith("anchors")
        assertThat(forbidden("""
            machine:
              states:
                extra:
                  final: true
        """)).startsWith("machine.states.extra")
        assertThat(forbidden("""
            machine:
              states:
                cast:
                  for: 9
        """)).startsWith("machine.states.cast.for")
        assertThat(forbidden("""
            machine:
              states:
                cast:
                  use: flash
        """)).startsWith("machine.states.cast.use")
    }

    @Test
    fun `part patch overlays the part in place and keeps the same scope`(@TempDir tmp: Path) {
        write(tmp, "flash.yaml", flashPart)
        write(tmp, "recolour.yaml", patch("skin", null, """
            machine:
              states:
                run:
                  enter:
                    - id: glow
                      color: "#00ffcc"
        """, target = "part:flash"))
        val c = SkillComposer(tmp, skinOnly)
        assertThat(c.composeAll()).isInstanceOf(Result.Success::class.java)
        val part = c.composedParts().getValue("flash")
        assertThat(part.patch.kind).isEqualTo("skin")
        val run = ((part.tree["machine"] as Map<*, *>)["states"] as Map<*, *>)["run"] as Map<*, *>
        assertThat(((run["enter"] as List<*>).single() as Map<*, *>)["color"]).isEqualTo("#00ffcc")
        assertThat(c.dependents("recolour.yaml")).containsExactly("part:flash")

        // state wiring of a part is forbidden, and a part patch takes no variant
        write(tmp, "recolour.yaml", patch("skin", null, "machine: {states: {run: {then: run}}}", target = "part:flash"))
        assertThat(errorsOf(SkillComposer(tmp, skinOnly)).single()).isInstanceOf(ComposeError.PatchError.ForbiddenPath::class.java)
        write(tmp, "recolour.yaml", patch("skin", "blue", "machine: {states: {run: {enter: [{id: glow, color: \"#fff\"}]}}}", target = "part:flash"))
        assertThat(errorsOf(SkillComposer(tmp, skinOnly)).single()).isInstanceOf(ComposeError.InvalidDocument::class.java)
    }

    @Test
    fun `a part change recompiles the skills that inline it and leaves the others`(@TempDir tmp: Path) {
        write(tmp, "flash.yaml", flashPart)
        write(tmp, "caller.yaml", caller)
        val c = SkillComposer(tmp, ComposeOptions(allowBalancePatches = false))
        assertThat(c.composeAll()).isInstanceOf(Result.Success::class.java)
        val catalog = SkillEngineRegistries.catalog(emptyMap(), emptyMap())
        fun load(keys: Collection<String>): SkillLoadReport {
            val composed = c.composed()
            val parts = c.composedParts()
            return catalog.reload(keys.mapNotNull { if (it.startsWith("part:")) parts[it.removePrefix("part:")] else composed[it] })
        }
        val report = load(listOf("part:flash", "caller"))
        assertThat(report.failed).isEmpty()
        val before = catalog.get("caller")!!
        assertThat(before.usedParts).containsExactly("flash")
        assertThat(before.usedSkills).isEmpty()

        // edit the part file: only the part is reloaded, the caller is recompiled by the catalog
        write(tmp, "flash.yaml", flashPart.replace("for: 4", "for: 9"))
        val changed = (c.recompose(listOf(tmp.resolve("flash.yaml"))) as Result.Success).value
        assertThat(changed).containsExactly("part:flash")
        val second = load(changed)
        assertThat(second.failed).isEmpty()
        assertThat(second.loaded).containsExactly("caller")
        assertThat(catalog.get("caller")).isNotSameAs(before)
        assertThat(catalog.get("caller")!!.table).isNotEqualTo(before.table)
    }
}
