# Adding a Crafting Recipe

Astera Recipe は `content/recipes/<id>.yaml` 1 ファイルで定義します
(Wave 5s / ADR-0091)。Vanilla recipe は Wave 5server-harden
(`VanillaFeature.VanillaRecipe`) で抑制されるので、Astera Item を作るには
ここに登録する必要があります。Kotlin コードは触りません。

## 最小例 (shaped sword)

```yaml
# content/recipes/iron-sword.yaml
id: iron-sword
output_item: knight-sword
output_count: 1
crafting_station: AsteraWorkbench
ingredients:
  type: shaped
  pattern:
    - " A "
    - " A "
    - " S "
  key:
    'A': iron-ingot
    'S': stick
```

## Shapeless example

```yaml
id: golden-apple
output_item: golden-apple
output_count: 1
crafting_station: AsteraWorkbench
ingredients:
  type: shapeless
  items:
    - { item: apple, count: 1 }
    - { item: gold-nugget, count: 8 }
```

## Schema reference

`plugin/core/.../domain/recipe/CustomRecipe.kt` が SSOT。

| Field | Type | Required | 説明 |
|---|---|---|---|
| `id` | `String` | ✅ | `RecipeId`。`^[a-z][a-z0-9-]*$` |
| `output_item` | `String` | ✅ | `ItemId` (`content/items/` 参照) |
| `output_count` | `Int` | optional | default 1, ≥ 1 |
| `crafting_station` | `enum` | ✅ | `AsteraWorkbench` / `Smelter` / `Brewstand` / `Anvil` |
| `ingredients.type` | `enum` | ✅ | `shaped` / `shapeless` / `smelting` |

### Shaped ingredients

| Field | Type | Required |
|---|---|---|
| `ingredients.pattern` | `List<String>` | ✅ (3 行、各行 3 文字) |
| `ingredients.key` | `Map<Char, ItemId>` | ✅ |

`pattern` の各セルは `key` 内の char を参照。スペース `' '` は empty。

### Shapeless ingredients

| Field | Type | Required |
|---|---|---|
| `ingredients.items` | `List<IngredientStack>` | ✅ |
| `ingredients.items[].item` | `String` | ✅ |
| `ingredients.items[].count` | `Int?` | default 1 |

### Smelting ingredients

| Field | Type | Required |
|---|---|---|
| `ingredients.input_item` | `String` | ✅ |
| `ingredients.cook_time_ticks` | `Long` | optional (default 200) |
| `ingredients.experience` | `Float?` | optional |

## Validation rule

- `output_item` が `IItemRegistry` に未登録 = `MissingReference`
- `ingredients.key` の値 (item id) が未登録 = `MissingReference`
- shaped の `pattern` 行数 != 3 = parse error
- shaped の各行長さ != 3 = parse error
- shaped で `pattern` に出てくる char が `key` に未定義 = parse error

## Reload command

```
/astera reload recipes
```

atomic swap。crafting GUI 中の player はその場で再判定 (新 recipe set
に対し再 match)。

## Crafting flow

- Player が `crafting_station` 種別の block を interact → `OpenCraftingGuiUseCase`
- GUI に inventory + grid。grid 入力ごとに `CraftRecipeUseCase.match`
- 一致 → output preview。take 操作で `CraftRecipeUseCase.execute`
- `RecipeCrafted` event publish
- HUD / chat 通知は Wave H `NotifyOnRecipeCraftedListener` (Core 側)

`recipe.cooldown_ticks` のような per-recipe cooldown は **無い**: cooldown
は Item binding 側 (`ItemSkillBinding.cooldownOverrideTicks`) で扱う設計。
Recipe 自体は idempotent crafting op。

## Vanilla recipe との関係

`SuppressVanillaFeaturesOnServerBootListener` (Wave L) が起動時に
`VanillaFeature.VanillaRecipe` を suppress するので、`crafting_table` で
vanilla recipe (`iron_sword` 等) は使えない。**Astera Item を作るには
本 doc の recipe を登録する必要がある**。

## 参考

- ADR-0091 (Quest chain + crafting recipe foundation)
- ADR-0125 (Content baseline expansion)
- ADR-0129 (Vanilla feature suppression default suppress)
- [[contributing/add-an-item-yaml]]
- [[contributing/add-a-quest-yaml]]
