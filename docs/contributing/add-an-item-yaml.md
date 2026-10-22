# Adding an Item

Astera Item は `content/items/<id>.yaml` 1 ファイルで完結します
(Wave _items-1 / ADR-0073)。Kotlin コードは触りません。Skill は別ファイル
(`content/skills/<id>.yaml`) で定義し、Item から id で複数参照します。

## 最小例 (sword)

```yaml
# content/items/example-sword.yaml
id: example-sword

material: IRON_SWORD

name:
  ja: "<gold>サンプルソード</gold>"
  en: "<gold>Example Sword</gold>"
lore:
  ja:
    - "<gray>Phase 1 のテスト用武器</gray>"
    - "<gray>左クリックで攻撃</gray>"
  en:
    - "<gray>Phase 1 demo weapon</gray>"
    - "<gray>Left-click to attack</gray>"

rarity: common
level_requirement: 1

attributes:
  attack_damage: 8.0
  attack_speed: 1.6

equip_slot: mainhand

skills:
  - trigger: left_click
    skill_id: example-charge
```

## フル例 (gun, skill 複数 bind, custom model, overrides)

```yaml
# content/items/lightning-blade.yaml
id: lightning-blade

# Vendor template
material: IRON_SWORD                    # Bukkit Material 名 (xor external_key)
custom_model_data: 1001                 # 任意 (resource pack 対応)
# external_key: oraxen:my_blade         # Oraxen / ItemAdder / MythicItems の場合

# Inline i18n (1 ファイル完結。content/languages/ は触らない)
name:
  ja: "<gold>雷の刃</gold>"
  en: "<gold>Lightning Blade</gold>"
lore:
  ja:
    - "<gray>雷を纏う剣</gray>"
    - "<gray>ヘッドショットで麻痺付与</gray>"
  en:
    - "<gray>A sword wreathed in lightning</gray>"
    - "<gray>Headshot causes paralysis</gray>"

# Rarity / progression
rarity: rare                            # common | uncommon | rare | epic | legendary
level_requirement: 3

# Vanilla AttributeModifier 由来の PvP 必須項目 (すべて optional、null は vanilla default)
attributes:
  attack_damage: 9.0                    # ATTACK_DAMAGE (vanilla IRON_SWORD = 7.0)
  attack_speed: 1.6                     # ATTACK_SPEED (vanilla sword = 1.6)
  knockback_resistance: 0.1             # 0.0-1.0
  movement_speed_multiplier: 1.0
  # armor / armor_toughness / max_health_bonus は装備品で使用

# 装備スロット
equip_slot: mainhand                    # mainhand | offhand | helmet | chestplate | leggings | boots

# Durability / behavior flags (すべて optional)
max_durability: null                    # null = material default
unbreakable: false
drop_on_death: true                     # キル時にドロップ
keep_on_death: false                    # 死亡しても inventory に残す (drop_on_death と排他)
enchantable: true                       # /enchant 等で付与可能
glint: null                             # null=follow vanilla; true/false=force

# Skill bindings - 複数可。`trigger` は player input、`skill_id` は
# content/skills/<id>.yaml を id 参照。`cooldown_override_ticks` で binding
# 単位の cooldown 上書き可。
# trigger: left_click | right_click | sneak_left | sneak_right | hold | drop | shoot
#   - sneak_* は無ければ通常の left/right_click に fallback (具体的な方が優先)
#   - hold = 右クリック押しっぱなしの 2 回目以降 (無ければ right_click に fallback)
#   - drop = Q キー。bind すると drop は cancel される (リロード用)
#   - shoot = 弓/クロスボウの発射
skills:
  - trigger: left_click
    skill_id: lightning-strike
  - trigger: right_click
    skill_id: thunder-aoe
    cooldown_override_ticks: 200        # 10s @ 20 TPS
  - trigger: sneak_right
    skill_id: paralysis-chance

# 追加 PDC tags (`astera:item_id` は自動付与)
pdc_tags:
  astera:rarity: rare
```

## Skill (`content/skills/<id>.yaml`) - Item から参照される側

```yaml
# content/skills/lightning-strike.yaml
id: lightning-strike

# 1 ファイル完結の inline i18n
name:
  ja: "<aqua>雷撃</aqua>"
  en: "<aqua>Lightning Strike</aqua>"
description:
  ja: "電気属性の弾を投射する"
  en: "Projects an electric projectile"

archetype: projectile                   # melee | projectile | aoe | buff | teleport | summon
cooldown_ticks: 20                      # default; item binding 側で override 可
mana_cost: 10                           # optional
charge_ticks: null                      # null = instant cast (else: charged variant)

params:
  type: projectile                      # archetype と整合する必要あり
  launch_speed: 1.8
  max_lifetime_ticks: 60
  damage:
    base: 8.0
    attribute: electric                 # physical | fire | electric | wind | water | earth
```

**Skill から `trigger:` は削除されている**点に注意 (Wave _items-1 で binding
側に移行)。同じ Skill を別 Item に異なる trigger で bind できる。

## Trigger 一覧

`SkillTrigger` enum で定義 (`plugin/core/.../domain/skill/SkillTrigger.kt`):

`left_click`, `right_click`, `sneak_left`, `sneak_right`, `left_click_air`,
`left_click_block`, `right_click_air`, `right_click_block`, `interact`,
`shoot`, `block_break`, `consume`, `crouch`, `jump`, `sprint`, `drop`,
`stack_drop`, `pickup`, `hyper`, `attack`, `shield`, `damaged`, `death`,
`hold` (sustained press, channel-style), `auto` (scheduled), `system`
(server-side fires)

Phase 1 では `left_click` 系が確実に動く。他は Wave 6 で Paper-side
BukkitEventAdapter に listener が追加される。

## Vendor template

- `material:` (vanilla) - Bukkit Material 名 (`IRON_SWORD`, `BLAZE_ROD`,
  `CROSSBOW` 等)。`Material.matchMaterial` で解決、miss 時は `STONE`
  fallback + warning。
- `external_key:` (Oraxen / ItemAdder / MythicItems) - `oraxen:my_blade` 等。
  Adapter modules が解決。

**両方指定 / 両方未指定は parse error**。

## i18n の集約方針

- **Item の name / lore は inline で完結**: `content/languages/` を開かなくて
  済む。Phase 1 では `ja` / `en` のみ。
- **UI chrome strings** (cooldown msg, command help, GUI button labels, rarity
  ラベル等) は `content/languages/{ja,en}.yaml` に集約 (cross-cutting)。
- Loader が boot 時に inline 文字列を IMessageBundle に auto-register、runtime
  は `MessageKey` ベースで統一。

## Player への配布

```
/astera-items give <player> <item-id>
```

例: `/astera-items give @s lightning-blade`

配布された ItemStack の PDC には `astera:item_id=lightning-blade` が stamp
される。`ItemSkillTriggerListener` がこの tag を読んで適切な skill を発火。

## Reload

```
/astera reload items
/astera reload skills
```

atomic swap で再読込 (parse error が 1 件でも出れば全 rollback)。

## Cooldown の二重宣言

- `SkillSpec.cooldownTicks` - Skill 自体のデフォルト cooldown
- `ItemSkillBinding.cooldownOverrideTicks` - Item の binding 単位での上書き

両方非 null の場合、binding の上書きが優先。同じ Skill が item ごとに違う
cooldown を持つ運用用 (例: pistol は 6 ticks、shotgun は 40 ticks)。

## Item と Skill の責務境界

| 項目 | どこに書く | 理由 |
|---|---|---|
| material / custom_model_data | Item | 物 = vendor representation |
| name / lore | Item | 物固有 |
| attack_damage / attack_speed / armor 等 (vanilla AttributeModifier) | Item | 装備時に発動 |
| equip_slot, durability, drop_on_death 等 | Item | 物の挙動 |
| projectile speed / range / damage 内訳 (element) | Skill | 行動の特性 |
| crit_chance / headshot_multiplier / accuracy (non-vanilla stat) | Skill (`params`) | 行動の特性 |
| status_effects on hit | Skill | 行動の効果 |
| trigger | binding (Item の `skills:` 配列) | Item と Skill の繋ぎ |

## 参考

- ADR: [[adr/0073-item-centric-content-model-and-skill-binding]]
- 関連: [[adr/0016-content-schema-ssot]] (SSOT + multi-wire),
  [[adr/0017-adopt-datadriven-contents-pattern]] (substrate),
  [[adr/0022-multi-plugin-isolation]] (sibling matrix),
  [[adr/0065-per-category-reload-dispatcher]] (`/astera reload <category>`)
