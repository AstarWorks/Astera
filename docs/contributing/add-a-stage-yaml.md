# Adding a Stage

Astera Stage は `content/stages/<id>.yaml` 1 ファイルで定義します
(Wave 5d / ADR-0033, Wave 5n / ADR-0080)。Match が立ち上がる「アリーナ」の
形 (regions / spawn points / per-match overlay) を宣言的に書きます。
Kotlin コードは触りません。

> **ADR-0246 以降**: 出荷される Stage はすべて、サーバーが `arenas` (void) ワールドに自動で建てる空島アリーナです。
> `layout:` (islands / bridges / landmarks)、`world: arenas`、スポーン・目標リージョンの書き方は
> `content/stages/README.md` が最新です。以下の最小例 (boss-arena) は削除済みの旧形式 (手置き AABB のみ) です。

## 最小例 (boss-arena)

```yaml
# content/stages/boss-arena.yaml
id: boss-arena

display_name_key: astera.stage.boss-arena.name

regions:
  - name: full-arena
    min: { x: -40.0, y: 64.0, z: -40.0 }
    max: { x:  40.0, y: 90.0, z:  40.0 }

  - name: boss-platform
    min: { x:  -8.0, y: 68.0, z:  -8.0 }
    max: { x:   8.0, y: 70.0, z:   8.0 }

  - name: player-ring
    min: { x: -30.0, y: 64.0, z: -30.0 }
    max: { x:  30.0, y: 66.0, z:  30.0 }

spawn_points:
  - faction: attacker
    position: { x: -25.0, y: 65.0, z: 0.0 }
    yaw: 90.0
  - faction: defender
    position: { x:  25.0, y: 65.0, z: 0.0 }
    yaw: 270.0
  - faction: lobby
    position: { x:   0.0, y: 75.0, z: 0.0 }
  - faction: spectator
    position: { x:   0.0, y: 90.0, z: 0.0 }
    pitch: 75.0
```

## Schema reference

`plugin/match/.../application/config/StageYamlConfig.kt` が SSOT。

| Field | Type | Required | 説明 |
|---|---|---|---|
| `id` | `String` | ✅ | `StageId`。`^[a-z][a-z0-9-]*$` |
| `display_name_key` | `String` | optional | i18n key (`content/languages/{ja,en}.yaml`) |
| `regions` | `List<RegionSpec>` | optional | AABB 領域定義 |
| `regions[].name` | `String` | ✅ | 領域名 (重複不可) |
| `regions[].min` | `Vec3` | ✅ | 最小角 (x/y/z double) |
| `regions[].max` | `Vec3` | ✅ | 最大角 (x/y/z double、`min` より大きい) |
| `spawn_points` | `List<SpawnPointSpec>` | ✅ | match 立ち上げ時のテレポート先 |
| `spawn_points[].faction` | `enum` | ✅ | `attacker` / `defender` / `lobby` / `spectator` |
| `spawn_points[].position` | `Vec3` | ✅ | 位置 |
| `spawn_points[].yaw` | `Float?` | optional | 0–360 (default 0) |
| `spawn_points[].pitch` | `Float?` | optional | -90 to 90 (default 0) |

Per-match overlay (weather / time / pvp 等) は `content/stages/<id>.match-overlay.yaml`
別ファイル (Wave 5n / ADR-0080)。デフォルトは `IMatchConfigService` の
組み込み値を継承。

## Validation rule

- `regions[].max.{x,y,z}` < `regions[].min.{x,y,z}` の場合 = parse error
- `spawn_points[]` で同じ `faction` が複数 = 最後のものが優先 (warning ログ)
- `faction: attacker` か `defender` が 1 つもない = parse error
- `display_name_key` が `content/languages/` に存在しない = renderer
  fallback に "missing key" を出して continue

## Per-match world

Wave 5d (`MatchWorld`) で Match 単位の世界を持ちます。
`CreateMatchWorldUseCase` が `MatchStarted` を受けて Stage の
`sourceSchematicPath` (= `content/stages/<id>.schem` 想定) を `ISchematicLoader`
で読み、`IMatchWorldRepository` に登録。Match 終了時に `DestroyMatchWorldUseCase`
が cleanup。

```
content/stages/boss-arena.yaml      # stage spec (本ファイル)
content/stages/boss-arena.schem     # schematic (Phase 1 はオプション; なくても regions だけで動く)
```

## Reload command

```
/astera reload stages
```

atomic swap。読み込み中の Match に影響は出ない (既存 Match は元の Stage
copy を保持; 新 Match から新 schema)。

## Region 名規約

`region.name` は domain-level Trigger / damage rule から参照されます:

- `barrier_drop_when_in: player-ring` — Wave 5d Barrier gateway
- `pvp_allowed_in: full-arena` — Wave 5n RegionPermissionService

無効な region name 参照は `BootValidateContentLifecycleListener` が
`MissingReference` として catch。

## 参考

- ADR-0033 (Stage / region foundation)
- ADR-0080 (Per-match world environment overlay)
- ADR-0090 (Custom block durability / region embed)
- [[contributing/add-an-item-yaml]]
- [[contributing/add-a-skill-yaml]]
