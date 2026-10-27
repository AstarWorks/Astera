# Item hover text (lore) style

One notation for every Astera item's hover card. The hover text is **generated**
from the item's data by `ItemTooltip` (`core/feature-gameplay/.../application/service/item/ItemTooltip.kt`);
the YAML only holds two short authored strings, `role` and (optionally) `flavor`.
Never hand-write skill lines, numbers, rarity or level - they come from the data
and cannot go stale.

## 1. Section order (fixed)

```
<name>                              rarity colour (builder applies it)
<rarity> - Lv.<n>                   header, generated
<role>                              YAML `role`, one line, gray
                                    blank
[RMB] <skill name>                  one block per skill binding, generated
  <skill description>               gray, first sentence of the skill description
  1発 5 | 射程 3m | CT 1.2秒        numbers read from the skill
...
                                    blank
Damage: 2.5                         stats block, generated, fixed order
Attack speed: 2.4/s
                                    blank
<flavor>                            YAML `flavor`, optional, dark gray italic
```

Optional generated lines (only when they apply) sit between the skills and the
stats block: one plain-words line per applied status, the E.G.O gauge note,
and the "locked until Norma Lv N" line (always last, dark gray).

Items that are not gear (blocks, ores, GUI tiles) have no header, skills or
stats: just `role` and `flavor`.

## 2. YAML (`content/items/<id>.yaml`)

```yaml
name:
  ja: "刀"
  en: "Katana"
role:                          # required for every non-GUI item
  ja: "近接 / 素早い斬撃と抜刀斬り"
  en: "Melee / quick slashes and iaido"
flavor:                        # optional
  ja: "鞘走りの音だけが先に届く。"
  en: "You hear the scabbard before the blade."
```

- No `lore:` on gear, tools, armor, blocks or ores. `lore:` stays only for
  `gui-*` and indicator items (plain GUI text, written as `<gray>...</gray>`).
- `role` and `flavor` are plain text: no MiniMessage tags, no `<`, no
  numbers that the data already holds (damage, cooldown, range, cost).
- `name` is plain text too: the builder colours it with the rarity colour.
  A name that carries its own colour tag keeps it; that is reserved for
  Limbus persona weapons (their identity colour).
- ja and en have the same structure: same `role` category, same presence of
  `flavor`, one line each.

### Role line

`<category> / <what it is for>`. Category vocabulary (fixed):

| ja | en | for |
|---|---|---|
| 近接 | Melee | swords, axes, spears, hammers |
| 銃 | Gun | pistols, rifles, shotguns |
| 弓 | Bow | bows, crossbows |
| 魔法 | Magic | staffs, rods, wands |
| 投擲 | Thrown | grenades, bombs, knives |
| 補助 | Support | potions, utility, traps |
| ツール | Tool | pickaxe, axe, shovel |
| 防具 | Armor | armor pieces |
| ブロック | Block | placeable blocks |
| 素材 | Material | ores and other materials |

The part after ` / ` says what the item is *for* in a few words
(e.g. `素早い斬撃と抜刀斬り`, `long-range sniping`). It must not list inputs.

## 3. Input labels (fixed, one way only)

No font glyph mechanism exists for key icons, so the label is a short bracketed
text, yellow, always at the start of the skill line.

| trigger | ja | en |
|---|---|---|
| `left_click` | `[左]` | `[LMB]` |
| `right_click` | `[右]` | `[RMB]` |
| `sneak_left` | `[Shift+左]` | `[Shift+LMB]` |
| `sneak_right` | `[Shift+右]` | `[Shift+RMB]` |
| `hold` | `[右長押し]` | `[Hold RMB]` |
| `drop` | `[Q]` | `[Q]` |
| `sneak_drop` | `[Shift+Q]` | `[Shift+Q]` |
| `shoot` | `[発射]` | `[Release]` |

## 4. Colours

One rarity table (the rarity enum is `common..legendary`; vanilla named
colours, the same ones `RarityGlowTooltipProvider` uses). Used for the item
name, the header rarity word and the tooltip frame accent.

| rarity | MiniMessage | hex |
|---|---|---|
| common | `<white>` | `#FFFFFF` |
| uncommon | `<green>` | `#55FF55` |
| rare | `<aqua>` | `#55FFFF` |
| epic | `<light_purple>` | `#FF55FF` |
| legendary | `<gold>` | `#FFAA00` |

Per section: header level `Lv.n` gray, separator dark gray; role gray; input
label yellow; skill name white; skill description gray; skill numbers keep the
`astera.skillbrief.*` colours (damage red, reach aqua, cooldown gray, cost
yellow, E.G.O light purple, applied status gold); stats label gray, value white;
flavour dark gray italic; locked line dark gray. The builder never sets bold or
underline; italic is cleared on every line except the flavour.

## 5. Width, lines, numbers

- Width: at most **32 full-width characters (ja)** or **40 half-width characters
  (en)** per authored line (about 250 px in the default font). Generated lines
  are held to the same limit (skill descriptions are cut with `...`).
- Line count: at most **24 lines** for the whole card. An item that binds 4 or
  more skills is shown compact (no skill description line, no status lines).
- Authored text: `role` is 1 line; `flavor` is at most 1 line.
- Blank lines separate sections: after the role, after the skills, before the
  flavour. Never two blank lines in a row.
- Numbers always carry their unit and use one decimal only when needed:
  `2.5`, `3m`, `1.2秒` / `1.2s`, `12%`, `/s` for speed.
- Stats block order: damage, attack speed, damage reduction (armor), armor,
  toughness, max health, knockback resistance, move speed, tool, magazine,
  cost per use.

## 6. Lint

`./gradlew contentLint` fails an item that breaks: missing `role` on a
non-GUI item, `lore:` on gear, a `<` tag inside `role` / `flavor`, a line over
the width limit, an en/ja mismatch (key present in only one language).

## 7. Rendered examples (generated, ja / en)

Colours omitted; `[..]` input labels are yellow, section separators are blank lines.

```
Pistol (common)                        ピストル (common)
Common · Lv.1                          コモン · Lv.1
Gun / easy rapid fire with reloads     銃 / 手軽な連射とリロード

[RMB] Pistol Shot                      [右] ピストル射撃
  Fires a fast, flat-flying bullet.      高速で直進する弾を撃つ。
  Ranged shot | Cooldown 0.3s            遠距離射撃 | クールダウン 0.3秒
[Q] Reload                             [Q] リロード

Damage: 1                              ダメージ: 1
Attack speed: 4/s                      攻撃速度: 4/秒
Magazine: 12                           装弾数: 12
Ammo per shot: 1                       1発の消費弾数: 1
```

```
Diamond Pickaxe (rare)                 ダイヤのツルハシ (rare)
Rare · Lv.1                            レア · Lv.1
Tool / tunneling and shattering        ツール / 掘削と粉砕

[RMB] Diamond Bore                     [右] ダイヤの掘削
[Shift+RMB] Diamond Shatter            [Shift+右] ダイヤの粉砕

Tool: Pickaxe (Diamond)                ツール: ツルハシ (ダイヤ)
```

```
Iron Warden Chestplate (uncommon)      アイアンウォーデン チェストプレート (uncommon)
Uncommon · Lv.1                        アンコモン · Lv.1
Armor / Iron Warden set (2/4 bonus)    防具 / アイアンウォーデン、2・4部位でボーナス

Damage reduction: 12%                  ダメージ軽減: 12%
```
