# content/

User-editable game content. **Anything in here can be added/changed by non-Kotlin contributors** — open a PR with a YAML file and you're done. See [docs/contributing/add-an-item-yaml.md](../docs/contributing/add-an-item-yaml.md).

## Layout

```
content/
├── items/          アイテム定義 (YAML 1 ファイル = 1 アイテム、Wave _items-1 / ADR-0073)
│                   material + inline i18n (name/lore) + attributes + skill bindings
├── skills/         スキル定義 (再利用可能、trigger は item 側 binding が持つ)
├── status/         ステータス効果定義
├── armor/          装甲 (per-attribute damage reduction)
├── stages/         マップ・ステージ
├── modes/          ゲームモード設定
├── shops/          ショップ定義
├── balance/        バランス調整 (block-durability 等)
├── gui/            GUI レイアウト定義
├── particles/      パーティクル定義
├── animations/     プレイヤーのスキンリグ・キーフレームアニメ (ADR-0250、`play_animation` / `/astera emote`)
├── pack-assets/    リソースパックへそのまま入れる手書きファイル (スキンリグのシェーダ等、`assets/...`)
├── sounds/         サウンド定義
├── models/         3D model JSON
├── textures/       32×32 PNG アセット (items / blocks / entities / animations)
├── tutorials/      チュートリアル定義
├── schemas/        JSON Schema (tools/schema-gen で生成)
└── languages/      cross-cutting UI i18n (cooldown msg, command help, etc.)
```

### 3-layer concept (Wave _items-1 / ADR-0073)

1. **Items** (`content/items/<id>.yaml`) — 武器・防具・消耗品など、世界に存在
   する物の定義。material / custom_model_data / 表示名 / lore / 装備スロット /
   PvP attributes / **複数 skill binding** を抱える。1 アイテム = 1 ファイル。
2. **Skills** (`content/skills/<id>.yaml`) — 何かを起こす行動の定義。projectile /
   melee / aoe / buff / teleport / summon といった archetype と damage / status /
   particles / sounds 等を持つ。trigger は持たない (binding の関心事)。
3. **Bindings** — Item YAML の `skills:` 配列で `{trigger, skill_id,
   cooldown_override_ticks?}` のペアを宣言する。同じ Skill を別の Item に異なる
   trigger で bind 可。

### i18n 集約戦略

- **Content-specific** (item name / lore, skill name / description) は inline
  `{ja, en}` で各 content YAML 内に書く。1 ファイル完結。
- **Cross-cutting UI** (cooldown msg, command help, GUI button, rarity label
  等) は `content/languages/{ja,en}.yaml` に集約。Loader が boot 時に inline
  文字列を IMessageBundle に auto-register、runtime API は MessageKey 経由で
  統一。

### Textures

`textures/` 配下の作成は **AsteraStudio repo** の texture-gen scaffolding 経由
が前提 (`AsteraStudio/tools/texture-gen/`、`STUDIO_TEXTURES_DIR` で本 repo の
`content/textures/` に書き込み)。詳細は
[content/textures/README.md](textures/README.md) と
[[adr/0079-extract-studio-to-own-repo]] を参照。

## Schema policy

Phase 1 PoC closing 中のため**後方互換性は提供しない** (CLAUDE.md §Pre-release
posture)。schema 変更時は `content/<category>/` 配下の既存 YAML を手で書き換える。
dual-decoder / fallback shim は禁止。正式 schema reference は将来
[docs/reference/config-yaml-reference.md](../docs/reference/config-yaml-reference.md)
(Phase 後半で生成) に固定する。

`content/schemas/*.schema.json` は `./gradlew :tools:schema-gen:generateSchemas`
で `@Serializable` Kotlin DTO から自動生成 (ADR-0016 part 4)。

## Reload

`/astera reload <category>` で各カテゴリを atomic swap reload 可能 (Wave
_infra-2b / ADR-0065)。`/astera reload items`, `/astera reload skills` 等。
