package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.ComposeError
import com.astarworks.astera.core.application.skillengine.compose.ComposeOptions
import com.astarworks.astera.core.application.skillengine.compose.ComposedSkill
import com.astarworks.astera.core.application.skillengine.compose.ExpansionDump
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.SkillDef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tier templates (ADR-0303 9.3 "Derived documents", design-tier-and-detach 1.2-1.6): a tier is `extends: <base>` + `item` + `meta` + `with:`
 * (param -> literal). Every row of the rules table has a case, every load error of 1.3 is asserted by its exact text, and the shipped
 * ladders (pistol / sword / tools) are checked through the real composer on `content/skills`.
 */
class ExtendsTierScenarioTest : SkillEngineScenario() {
    private val base = """
        apiVersion: astera/v3
        kind: Skill
        id: blade
        item: couture-rapier
        meta: {name: {ja: 刃, en: Blade}, cooldown_ticks: 10}
        params:
          dmg:  {type: number, default: 40.0}
          tone: {type: string, default: "astera:weapons/blade-hit"}
          loud: {type: bool, default: false}
          need: {type: number, required: true}
        anchors:
          - {id: heart, anchor: victim.root, offset: {up: 1.2}, mode: follow}
        machine:
          start: swing
          states:
            swing:
              for: 2
              then: end
              do:
                - id: swing
                  op: hit
                  at: 0
                  anchor: caster.eye
                  shape: {sector: {deg: 100, range: 3.5, height: 2.0, forward: look}}
                  targets: enemies
                  ignore_invulnerability: false
                  damage: {physical: "${'$'}{dmg}"}
                  window: once
                  on:
                    hit:
                      - {id: snd_hit, op: sound, sound: "${'$'}{tone}", anchor: contact}
                - {id: ring, op: sound, sound: block.stone.break, anchor: caster, volume: 1.0, pitch: "${'$'}{need}", when: "= ${'$'}{loud}"}
            end: {final: true}
    """.trimIndent()

    private fun tier(id: String = "blade-t2", with: String = "  need: 1.0\n  dmg: 52.0\n  tone: \"astera:weapons/blade-hit-t2\"\n  loud: true", extra: String = "", extends: String = "blade", meta: String? = "meta: {name: {ja: 刃II, en: Blade II}, cooldown_ticks: 8}", item: String? = "item: couture-rapier"): String =
        listOfNotNull("apiVersion: astera/v3", "kind: Skill", "id: $id", "extends: $extends", item, meta, extra.ifBlank { null }, with.takeIf { it.isNotBlank() }?.let { "with:\n$it" }).joinToString("\n") + "\n"

    private fun write(dir: Path, rel: String, text: String) {
        Files.createDirectories(dir.resolve(rel).parent)
        Files.writeString(dir.resolve(rel), text)
    }

    private fun errors(dir: Path, options: ComposeOptions = ComposeOptions()): List<ComposeError> =
        (SkillComposer(dir, options).composeAll() as? Result.Failure)?.error ?: throw AssertionError("expected a load error but the content composed")

    private fun composed(dir: Path, options: ComposeOptions = ComposeOptions()): Map<String, ComposedSkill> = when (val r = SkillComposer(dir, options).composeAll()) {
        is Result.Success -> r.value
        is Result.Failure -> throw AssertionError("compose: ${r.error}")
    }

    private fun defsOf(all: Map<String, ComposedSkill>): Map<String, SkillDef> = all.mapValues { (id, c) ->
        when (val d = decodeTree(id, c.tree, all.keys)) {
            is Result.Success -> d.value
            is Result.Failure -> throw AssertionError("$id: ${d.error}")
        }
    }

    private fun dumpOf(def: SkillDef, defs: Map<String, SkillDef>): String = when (val r = compileAgainst(def, defs)) {
        is Result.Success -> ExpansionDump.render(r.value.table).lines().drop(1).joinToString("\n")
        is Result.Failure -> throw AssertionError("${def.id}: ${r.error}")
    }

    private fun only(e: List<ComposeError>): ComposeError.Extends = e.single() as ComposeError.Extends

    // ---- rows of the rules table ------------------------------------------------------------------------------------

    @Test
    fun tierTableEqualsBaseWithSubstitutedParams(@TempDir tmp: Path) {
        write(tmp, "blade/skill.yaml", base)
        write(tmp, "blade-t2/skill.yaml", tier())
        // the same skill written out by hand with the tier's values as defaults
        val hand = base.replace("id: blade", "id: hand")
            .replace("dmg:  {type: number, default: 40.0}", "dmg:  {type: number, default: 52.0}")
            .replace("default: \"astera:weapons/blade-hit\"", "default: \"astera:weapons/blade-hit-t2\"")
            .replace("loud: {type: bool, default: false}", "loud: {type: bool, default: true}")
            .replace("need: {type: number, required: true}", "need: {type: number, default: 1.0}")
        write(tmp, "hand/skill.yaml", hand)
        val defs = defsOf(composed(tmp))
        assertThat(dumpOf(defs.getValue("blade-t2"), defs)).isEqualTo(dumpOf(defs.getValue("hand"), defs))
        assertThat(dumpOf(defs.getValue("blade-t2"), defs)).contains("astera:weapons/blade-hit-t2")
    }

    @Test
    fun tierKeepsOwnItemMetaAndIdentity(@TempDir tmp: Path) {
        write(tmp, "blade/skill.yaml", base)
        write(tmp, "blade-t2/skill.yaml", tier(item = "item: knight-sword", with = "  need: 1.0"))
        val all = composed(tmp)
        assertThat(all.keys).containsExactlyInAnyOrder("blade", "blade-t2")
        val defs = defsOf(all)
        assertThat(defs.getValue("blade-t2").item).isEqualTo("knight-sword")
        assertThat(defs.getValue("blade-t2").meta.cooldownTicks).isEqualTo(8)
        assertThat(defs.getValue("blade").item).isEqualTo("couture-rapier")
        assertThat(defs.getValue("blade").meta.cooldownTicks).isEqualTo(10)
        assertThat(all.getValue("blade-t2").tree).doesNotContainKeys("extends", "with")
        assertThat(all.getValue("blade-t2").sources).containsExactlyInAnyOrder("blade-t2/skill.yaml", "blade/skill.yaml")
        // with: may be left out when no value differs, but a required param of the base still needs its value
        write(tmp, "blade-t3/skill.yaml", tier(id = "blade-t3", with = ""))
        assertThat(only(errors(tmp)).reason).isEqualTo("extends 'blade': required param 'need' has no value in with:")
    }

    @Test
    fun noValueInWithKeepsTheBaseDefault(@TempDir tmp: Path) {
        write(tmp, "blade/skill.yaml", base)
        write(tmp, "blade-t2/skill.yaml", tier(with = "  need: 1.0"))
        val defs = defsOf(composed(tmp))
        assertThat(defs.getValue("blade-t2").params.getValue("dmg").default).isEqualTo(40.0)
        assertThat(defs.getValue("blade-t2").params.getValue("need").required).isFalse()
    }

    @Test
    fun patchOnTheBaseIsNotAppliedToTiersAndAPatchOnATierComposesWithItsWith(@TempDir tmp: Path) {
        write(tmp, "blade/skill.yaml", base)
        write(tmp, "blade-t2/skill.yaml", tier())
        write(tmp, "base-skin.yaml", "apiVersion: astera/v3\nkind: Patch\nid: base-skin\ntarget: blade\nvariant: red\npatchKind: skin\nops:\n  - {path: \"machine.states.swing.do[ring].volume\", \$patch: replace, value: 0.5}\n")
        write(tmp, "tier-skin.yaml", "apiVersion: astera/v3\nkind: Patch\nid: tier-skin\ntarget: blade-t2\nvariant: blue\npatchKind: skin\nops:\n  - {path: \"machine.states.swing.do[ring].volume\", \$patch: replace, value: 0.25}\n")
        val all = composed(tmp)
        assertThat(all.keys).containsExactlyInAnyOrder("blade", "blade@red", "blade-t2", "blade-t2@blue")
        fun volume(id: String): Any? = (((all.getValue(id).tree["machine"] as Map<*, *>)["states"] as Map<*, *>)["swing"] as Map<*, *>)
            .let { (it["do"] as List<*>).map { r -> r as Map<*, *> }.single { r -> r["id"] == "ring" }["volume"] }
        assertThat(volume("blade-t2")).isEqualTo(1.0)
        assertThat(volume("blade-t2@blue")).isEqualTo(0.25)
        assertThat(volume("blade@red")).isEqualTo(0.5)
        val variant = all.getValue("blade-t2@blue").tree
        assertThat(((variant["params"] as Map<*, *>)["dmg"] as Map<*, *>)["default"]).isEqualTo(52.0)
        assertThat(all.getValue("blade-t2@blue").patch.kind).isEqualTo("skin")
        // the patched tier decodes and compiles like any skill
        val defs = defsOf(all)
        assertThat(dumpOf(defs.getValue("blade-t2@blue"), defs)).contains("0.25")
    }

    @Test
    fun editingTheBaseRecomposesItsTiers(@TempDir tmp: Path) {
        write(tmp, "blade/skill.yaml", base)
        write(tmp, "blade-t2/skill.yaml", tier())
        write(tmp, "lance/skill.yaml", base.replace("id: blade", "id: lance"))
        val composer = SkillComposer(tmp)
        assertThat(composer.composeAll()).isInstanceOf(Result.Success::class.java)
        assertThat(composer.dependents("blade/skill.yaml")).contains("blade", "blade-t2")
        write(tmp, "blade/skill.yaml", base.replace("range: 3.5", "range: 4.5"))
        val changed = (composer.recompose(listOf(tmp.resolve("blade/skill.yaml"))) as Result.Success).value
        assertThat(changed).containsExactlyInAnyOrder("blade", "blade-t2")
        assertThat(composer.composed().getValue("blade-t2").tree.toString()).contains("range=4.5")
        assertThat(composer.composed().getValue("lance").tree.toString()).contains("range=3.5")
    }

    // ---- the load errors of 1.3 ------------------------------------------------------------------------------------

    @Test
    fun tierRefusesMissingBase(@TempDir tmp: Path) {
        write(tmp, "blade-t2/skill.yaml", tier(extends = "ghost"))
        assertThat(only(errors(tmp)).reason)
            .isEqualTo("extends 'ghost': no skill document 'ghost' in content/skills (a tier extends a composed base id, never a Part, a Patch variant or a tier)")
    }

    @Test
    fun tierRefusesStructuralKeys(@TempDir tmp: Path) {
        write(tmp, "blade/skill.yaml", base)
        write(tmp, "blade-t2/skill.yaml", tier(extra = "anchors: []"))
        val e = only(errors(tmp))
        assertThat(e.reason).isEqualTo("extends: a tier document takes only apiVersion, kind, id, extends, item, meta, with; 'anchors' belongs to the base 'blade' (make what differs a param there)")
        assertThat(e.file).isEqualTo("blade-t2/skill.yaml")
    }

    @Test
    fun tierRefusesUnknownParam(@TempDir tmp: Path) {
        write(tmp, "blade/skill.yaml", base)
        write(tmp, "blade-t2/skill.yaml", tier(with = "  need: 1.0\n  damage: 3.0"))
        assertThat(only(errors(tmp)).reason).isEqualTo("with 'damage': base 'blade' declares no param 'damage' (params: dmg, tone, loud, need)")
    }

    @Test
    fun tierRefusesTypeMismatch(@TempDir tmp: Path) {
        write(tmp, "blade/skill.yaml", base)
        write(tmp, "blade-t2/skill.yaml", tier(with = "  need: 1.0\n  dmg: \"32\""))
        assertThat(only(errors(tmp)).reason).isEqualTo("with 'dmg': expected number, got string \"32\"")
        write(tmp, "blade-t2/skill.yaml", tier(with = "  need: 1.0\n  loud: 1"))
        assertThat(only(errors(tmp)).reason).isEqualTo("with 'loud': expected bool, got number 1")
    }

    @Test
    fun tierRefusesNonLiteralValues(@TempDir tmp: Path) {
        write(tmp, "blade/skill.yaml", base)
        write(tmp, "blade-t2/skill.yaml", tier(with = "  need: 1.0\n  dmg: \"= 2 * 3\""))
        assertThat(only(errors(tmp)).reason).isEqualTo("with 'dmg': a tier value is a literal; '= 2 * 3' is not (write the number)")
        write(tmp, "blade-t2/skill.yaml", tier(with = "  need: 1.0\n  tone: \"\${base_tone}\""))
        assertThat(only(errors(tmp)).reason).isEqualTo("with 'tone': a tier value is a literal; '\${base_tone}' is not (write the number)")
    }

    @Test
    fun tierRefusesChain(@TempDir tmp: Path) {
        write(tmp, "blade/skill.yaml", base)
        write(tmp, "blade-t2/skill.yaml", tier())
        write(tmp, "blade-t3/skill.yaml", tier(id = "blade-t3", extends = "blade-t2"))
        assertThat(only(errors(tmp)).reason)
            .isEqualTo("extends 'blade-t2': a base must not itself extend (blade-t3 -> blade-t2 -> blade); extend 'blade' directly")
    }

    @Test
    fun tierRefusesMissingMeta(@TempDir tmp: Path) {
        write(tmp, "blade/skill.yaml", base)
        write(tmp, "blade-t2/skill.yaml", tier(meta = null))
        val e = only(errors(tmp))
        assertThat(e.reason).isEqualTo("extends 'blade': 'meta' is required in a tier (it is not inherited)")
        assertThat(e.path).isEqualTo("meta")
        // a partial meta is the tier's whole meta: nothing is merged in from the base
        write(tmp, "blade-t2/skill.yaml", tier(meta = "meta: {cooldown_ticks: 8}"))
        val defs = defsOf(composed(tmp))
        assertThat(defs.getValue("blade-t2").meta.cooldownTicks).isEqualTo(8)
        assertThat(defs.getValue("blade-t2").meta.name).isEmpty()
    }

    @Test
    fun tierRefusesMissingItemWhenTheBaseHasOne(@TempDir tmp: Path) {
        write(tmp, "blade/skill.yaml", base)
        write(tmp, "blade-t2/skill.yaml", tier(item = null))
        assertThat(only(errors(tmp)).reason).isEqualTo("extends 'blade': 'item' is required in a tier (it is not inherited)")
    }

    @Test
    fun plainSkillRefusesWith() {
        val text = base.replace("anchors:", "with: {dmg: 3.0}\nanchors:")
        val r = compileText("blade", text)
        val err = (r as Result.Failure).error
        assertThat(err).isInstanceOf(SkillDecodeError.UnknownKey::class.java)
        assertThat(err.path).isEqualTo("")
        assertThat((err as SkillDecodeError.UnknownKey).key).isEqualTo("with")
    }

    // ---- the shipped ladders -----------------------------------------------------------------------------------------

    @Test
    fun shippedTiersAreDerivedFromTheirBases() {
        val all = composed(contentDir.resolve("skills"))
        fun default(id: String, param: String): Any? = ((all.getValue(id).tree["params"] as Map<*, *>)[param] as Map<*, *>)["default"]
        for ((id, dmg) in listOf("pistol-shot" to 25.0, "pistol-shot-t2" to 32.0, "pistol-shot-t3" to 41.0, "pistol-shot-t4" to 51.0)) {
            assertThat(default(id, "dmg")).describedAs(id).isEqualTo(dmg)
        }
        for ((id, dmg) in listOf("sword-slash" to 40.0, "sword-slash-t2" to 52.0, "sword-slash-t3" to 66.0, "sword-slash-t4" to 82.0)) {
            assertThat(default(id, "dmg")).describedAs(id).isEqualTo(dmg)
        }
        for (family in listOf("axe-fell", "axe-plank-wall", "pickaxe-bore", "pickaxe-shatter", "shovel-earth-bridge", "shovel-excavate")) {
            for (tier in listOf("stone", "iron", "diamond")) {
                val tree = all.getValue("$family-$tier").tree
                assertThat(tree).describedAs("$family-$tier").doesNotContainKeys("extends", "with").containsKey("machine")
            }
            assertThat(default("$family-diamond", "spark").toString()).endsWith("diamond")
            assertThat(default("$family-stone", "spark").toString()).endsWith("stone")
        }
        assertThat(all.getValue("pickaxe-bore-iron").sources).contains("pickaxe-bore-stone/skill.yaml")
    }
}
