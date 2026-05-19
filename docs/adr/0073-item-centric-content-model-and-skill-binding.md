---
status: Proposed
date: 2026-05-19
deciders: Astera workspace (Wave _items-1)
supersedes: null
superseded_by: null
---

# ADR-0073: Item-centric content model + multi-skill binding + inline i18n

## Status

Proposed

## Context

Phase 1 PoC closing 中の Astera において、`content/weapons/` と
`content/items/` が二重に存在し、しかも weapon 側が item を `item:` field で
**逆参照**する構造 (Stage 1 D の経過状態) になっていた。同類 OSS plugin
(MythicCrucible / Oraxen / ItemAdder) との比較で以下の欠落が露呈した:

1. **Item が first-class でない**: weapon が item template (material /
   custom_model_data / pdc_tags) を「持つ」関係になっていて、authoring 単位が
   `WeaponSpec` 中心。Item を independent に定義し item から skill を引く
   MythicCrucible 流の shape と逆向き。
2. **Skill が再利用不能**: `SkillSpec` が `trigger: SkillTrigger` を field と
   して抱え込んでおり、同じ skill を別の item に異なる trigger で bind できない
   (1 trigger / 1 skill の固定束縛)。
3. **多 skill binding が存在しない**: `WeaponYamlConfig` は単一の `damage:` +
   `cooldown_ticks` を持つのみで、「left_click で skill A、right_click で skill
   B、attack で skill C」という MythicCrucible 流の表現ができない。
4. **i18n が 3 ファイル散布**: 1 item 作るのに `content/items/<id>.yaml` +
   `content/languages/ja.yaml` + `content/languages/en.yaml` を行き来する必要が
   ある。Contributor doc (`add-a-weapon-yaml.md`) は **既に inline `{ja, en}`
   形式を推奨**しており、実装側がそれに揃っていなかった。
5. **`plugin/weapons` の役割消失**: `WeaponSpec` は実質 "Item + 単発 skill
   inline" で、Item first-class 化すると weapons plugin が抱える domain は
   消滅する。projectile-related types (`GunSpec`, `ProjectileSpec`, `HitProfile`)
   は combat の `SkillParams.ProjectileParams` と意味的に重複。

CLAUDE.md §Pre-release posture により、Phase 1 PoC closing 中の今は破壊的
変更を積極的に取る方針 (リリース後だと dual-decoder 負債化)。`@Deprecated`
や fallback shim は書かず forward-only commit で済ます。

## Decision

以下の re-shape を **Wave _items-1** として単一 commit で実施する:

1. **Item を first-class SSoT に昇格**。`content/items/<id>.yaml` が 1 item の
   全定義 (template + i18n + attributes + skill bindings + behavior flags) を
   保持。`content/weapons/` は廃止。

2. **Item YAML schema** (新):
   - `material` xor `external_key` (vendor template — vanilla vs Oraxen/ItemAdder)
   - inline `name: {ja, en}` / `lore: {ja: [...], en: [...]}` (i18n 自己完結)
   - `rarity`, `level_requirement`
   - `attributes:` block — vanilla AttributeModifier 由来 (attack_damage,
     attack_speed, armor, armor_toughness, knockback_resistance,
     movement_speed_multiplier, max_health_bonus)
   - `equip_slot: mainhand | offhand | helmet | chestplate | leggings | boots`
   - `max_durability`, `unbreakable`, `drop_on_death`, `keep_on_death`,
     `enchantable`, `glint` (PvP-common behavior flags)
   - `skills: List<ItemSkillBinding>` — **複数 skill 可**、各 binding は
     `{trigger: SkillTrigger, skill_id: SkillId, cooldown_override_ticks?: Int}`
   - `pdc_tags: Map<String, String>` (追加メタ; `astera:item_id` は adapter
     が自動付与)

3. **Skill YAML schema** (変更):
   - `trigger:` field を **削除** (trigger は binding 側の関心事)。Skill は
     reusable な action 定義として独立。
   - `display_name_key` / `description_key` String → inline `name: {ja, en}` /
     `description: {ja, en}` に shape 変更。

4. **`plugin/weapons` を `plugin/items` に rename**:
   - Gradle module `:plugin:weapons` → `:plugin:items`
   - Paper plugin name `Astera-Weapons` → `Astera-Items`
   - 全 plugin.yml の `softdepend: [..., Astera-Weapons, ...]` を `Astera-Items`
     に置換 (combat / stats / persistence loadbefore)
   - Package `com.astarworks.astera.weapons.*` → `com.astarworks.astera.items.*`

5. **Cross-plugin call は `IDomainEventBus.SkillCastRequested` 経由**:
   - 新 domain event `SkillCastRequested(actorId, skillId, origin, sourceItemId,
     cooldownOverrideTicks)` を `core.domain.event` に追加
   - Items plugin の `ItemSkillTriggerListener` が paper event を `SkillTrigger`
     enum 値に翻訳し、PDC `astera:item_id` を介して `ItemSpec.skillBindings` を
     lookup、cooldown gate を通してから event を publish
   - Combat plugin の `AsteraCombatPlugin.onEnable` で `IDomainEventBus.subscribe(
     SkillCastRequested::class) { CastSkillUseCase.execute(...) }`
   - これにより Combat が Items に依存せず、Items も Combat に依存しない
     (sibling-isolation 維持; 共通 event の vocabulary だけが core に集約)。

6. **`SkillTrigger` / `SkillId` を combat → core に昇格移動**:
   - Item の binding が SkillId / SkillTrigger を field に持つ以上、core 側に
     置かないと sibling-isolation matrix を破る (`plugin:core` ← nothing)。
   - `combat.domain.skill.SkillTrigger` → `core.domain.skill.SkillTrigger`
   - `combat.domain.skill.SkillId` → `core.domain.skill.SkillId`
   - combat 側は import 変更のみ。

7. **`DamageProfile` を weapons → core へ移動**:
   - `combat.application.config.SkillYamlConfig` が
     `weapons.domain.weapon.DamageProfile` を import している既存依存を解消。
   - 移動先: `core.domain.damage.DamageProfile` (既にある `DamageAttribute`
     と同居)。

8. **`content/languages/` は cross-cutting UI strings 専用に縮退**:
   - Content-specific (item name / lore, skill name / description) は inline
   - Cross-cutting (cooldown msg, command help, GUI titles, rarity label,
     error msg) は `content/languages/{ja,en}.yaml` に残置
   - Loader (`ItemContents` / `SkillContents`) が inline 文字列を boot 時に
     `IMessageBundle.put("astera.item.<id>.name", locale, text)` で auto-register。
     Runtime API は `MessageKey` ベースで統一 (renderer / GUI / lore 出力の
     変更不要)。

9. **PvP attributes と SkillParams の責務分割**:
   - Item `attributes:` block は **vanilla AttributeModifier 由来** のみ。
   - Non-vanilla stat (`crit_chance`, `headshot_multiplier`, `accuracy`,
     `recoil` 等) は **`SkillParams` 側** に閉じ込める (item ではなく skill の
     特性として表現)。

## Consequences

### Positive

- **1 item = 1 file**: contributor が `content/items/<id>.yaml` だけ編集
  すれば item の全定義が完結。i18n / attributes / skill binding が同一視野。
- **Skill reusability**: 同じ `lightning-strike` skill を sword の left_click
  にも wand の sneak_right にも bind できる。binding 側で
  `cooldown_override_ticks` を上書きすれば item ごとの差別化も可能。
- **MythicCrucible / Oraxen / ItemAdder と概念的に対等**: PvP server 運用者が
  既存知識のまま Astera content を author できる。
- **plugin/weapons の意味的負債を解消**: Item / Skill 2 系統が「物 + 行動」
  の自然な対で揃い、weapons という中間概念が消える。
- **i18n authoring が単純化**: 翻訳者は item YAML を見るだけで翻訳できる。
  Phase 1 の ja/en に対する translator = developer なので分散しても誰も困らない。

### Negative / Trade-offs

- **`plugin/weapons` ディレクトリ削除は破壊的**: 5 副 plugin.yml の softdepend
  / loadbefore 行、Konsist の module-name string、Studio の `/weapons` route、
  IDE bookmarks 等が一斉に動く。
- **`SkillTrigger` を core に昇格** = core が gameplay-specific vocabulary
  (`SHOOT`, `BLOCK_BREAK`, `HYPER`, ...) を抱える形。代替として "core は
  生 input event だけ持ち skill mapping を combat に置く" は採れるが、それだと
  item の binding が `IMcEvent.PlayerLeftClickAir` 等 adapter type を握ること
  になり層が逆転する。trade-off として core ownership を取る。
- **後方互換性ゼロ**: `content/weapons/` を使っていた既存 content / 既存テスト
  はすべて手で書き換え。CLAUDE.md §Pre-release posture どおり dual-decoder
  は書かない。
- **`SkillSpec.trigger` 削除は cascading**: combat の `CastSkillUseCase` や
  `SkillCast` event が skill から trigger を読んでいる箇所すべて修正。
- **Inline i18n のスケール限界**: 5+ 言語に拡張する時に YAML が肥大化する。
  Phase 1 では問題ないが、ADR の References に "Future: locale-override
  side-file (`content/items/<id>.<locale>.yaml`)" を Extension Point として
  記録 (本 ADR では実装しない)。

### Mitigations

- **astera-reviewer subagent**: Phase C close で Konsist green / no Bukkit
  imports in domain / no hardcoded versions / i18n parity を自動 audit。
- **`./gradlew :tools:architecture-test:test`**: sibling-isolation matrix の
  `Items ← Core; Combat ← Core, Items` を Konsist で機械的に enforce。
- **Skill auto-register i18n**: inline 化に伴う `MessageKey` 整合性は
  `IMessageBundle.put` 経由で実行時に保証。`astera.item.<id>.name` 形式の
  key naming convention を ADR-0073 で固定し、runtime 側を MessageKey ベース
  に保つ。

## Alternatives Considered

- **Alt A — `WeaponSpec.skills: List<SkillBinding>` を追加し weapons plugin を
  残す**: 最小破壊だが、`weapons` 概念が中間レイヤーとして残り Item と二重定義
  が解消されない。MythicCrucible の "item-centric" shape を達成できない。
- **Alt B — i18n は keys のままで `add-a-weapon-yaml.md` を実装に合わせて
  書き直す**: contributor 体験を悪化させる方向への妥協で、user 指摘の根幹を
  外す。
- **Alt C — `plugin/items` ではなく `plugin/weapons` を残して中身だけ rename**:
  history は楽だが、plugin 名が概念と乖離して新規 contributor が混乱する。
  破壊的変更を取るなら名前ごと正す。
- **Alt D — Skill 側に `default_trigger: SkillTrigger` を残し、binding 側で
  override 可能にする**: 一見柔軟だが、結局「2 箇所で trigger を見る」必要が
  あり推論が複雑化。Skill は trigger を持たず binding 1 箇所に集約する方が
  clean。
- **Alt E — Item の `attributes:` を vanilla AttributeModifier に閉じず
  Astera 独自 stats を含める**: 拡張性は高いが、stat 体系の SSoT が複数化
  (Item / Skill / Stats plugin) して責務境界が崩れる。本 ADR では vanilla
  AttributeModifier 由来のみに限定し、独自 stat は SkillParams 側に閉じる。

## References

- 関連 ADR:
  - [[adr/0016]] Content schema SSoT + multi-format wire pluggability
    — 本 ADR は ADR-0016 の SSoT 原則に乗りつつ Item DTO の shape を更新する。
    本 ADR から Updated-by 参照を入れる。
  - [[adr/0017]] DataDriven Contents Pattern — substrate (`YamlDynamicContents`)
    は本 ADR で再利用、変更なし。
  - [[adr/0022]] Multi-plugin sibling-isolation — sibling matrix の Weapons
    行 → Items 行に rename。依存方向は同じ。
  - [[adr/0029]] RTM empirical catalog — Items / Skills / Skill-binding 該当
    行を Phase C で rtm-sync が flip。
  - [[adr/0030]] Combat depth + damage event decomposition — `SkillCast` /
    `DamageDealt` event chain は不変。trigger 削除の影響範囲のみ調整。
  - [[adr/0061]] Studio `/weapons` preview route — route 自体は本 ADR で
    `/items` に rename。ADR-0061 の本文は immutable、Status 不変、Updated-by
    参照を本 ADR から記録。
  - [[adr/0065]] Per-category reload dispatcher — items / skills の reload
    分離は不変。
  - [[adr/0066]] Promote studio to mainline — studio が `/items` route を
    持つことを本 ADR で確定。
- 関連 docs:
  - [[contributing/add-an-item-yaml]] (本 ADR で `add-a-weapon-yaml.md` から
    rename + 全面書き直し)
  - [[architecture/principles]] (Item / Skill 責務境界)
- 外部リンク:
  - MythicMobs `MythicCrucible` item schema reference
  - Oraxen `Items` configuration docs
  - ItemAdder `Items` and `Custom Items` docs

## Future extension points (not implemented in this ADR)

- **Locale-override side-file**: `content/items/<id>.<locale>.yaml` で base
  YAML の `name` / `lore` だけを上書きする mechanism。5+ 言語化時に必要。
- **`@ref:<path>` cross-file reuse** (ADR-0016 Part 4): item の `skills:`
  リストで `@ref:bindings/sword-base.yaml` 等で binding 一式を引き込み再利用。
- **Item set bonus** (`set_id` + `set_bonus`): MythicCrucible-style set 効果。
- **Item recipe / crafting registration**: Oraxen 流の自動 recipe 登録。
- **Furniture / custom block** (ItemAdder 範囲): Phase 3 stage geometry と
  統合した非 item オブジェクト。
