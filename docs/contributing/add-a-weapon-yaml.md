# Add a Weapon (YAML only)

Kotlin を一切書かずに、YAML 1 枚で新しい武器を追加する方法。**Astera の non-IT コントリビュータ参加導線の中心**です。

> Phase 1 では YAML スキーマがまだ確定途中です。本 doc は最終形のイメージ。実際のスキーマは Phase 1 完了時に [[reference/config-yaml-reference]] で固定されます。

## 手順

### 1. テンプレートをコピー

```bash
cp content/weapons/example-sword.yaml content/weapons/my-new-sword.yaml
```

### 2. 内容を編集

```yaml
# content/weapons/my-new-sword.yaml
id: lightning-blade
name:
  ja: "雷の刃"
  en: "Lightning Blade"
lore:
  ja:
    - "雷を纏う剣"
    - "ヘッドショットで麻痺付与"
  en:
    - "A sword wreathed in lightning"
    - "Headshot causes paralysis"
archetype: sword            # gun | sword | wand (Phase 2 で追加)
rarity: rare                # common | uncommon | rare | epic | legendary
level_requirement: 3

damage:
  base: 12.0
  attribute: electric       # physical | fire | electric | wind ...
  headshot_multiplier: 2.0

cooldown_ticks: 10          # 1 tick = 50 ms (20 ticks/sec)

trigger:
  - on: left_click          # left_click | right_click | sneak_left | sneak_right
    action:
      type: melee_attack
      range: 3.5
      knockback: 1.2
      status_effects:
        - id: paralysis
          duration_ticks: 40
          chance_on_headshot: 1.0
          chance_normal: 0.1

effects:
  particle:
    - id: vanilla:electric_spark
      count: 30
      offset: { x: 0.3, y: 0.5, z: 0.3 }
  sound:
    - id: vanilla:entity.lightning_bolt.thunder
      volume: 0.5
      pitch: 1.5
```

### 3. (任意) i18n を追加

`content/languages/ja.yaml` と `en.yaml` に対応 key があれば翻訳キーも自動で参照されます。

### 4. local で確認

```bash
docker compose up -d
docker compose exec paper rcon-cli "/astera give @p lightning-blade"
```

in-game で武器を構えると lore とエフェクトが見えれば成功。

### 5. PR を出す

```bash
git checkout -b add-lightning-blade
git add content/weapons/my-new-sword.yaml
git commit -m "feat(weapons): add lightning-blade sword"
git push origin add-lightning-blade
gh pr create --fill
```

PR には:
- 武器の使用感を 2〜3 行で
- (任意) gameplay GIF
- バランス考察 (compared to existing weapons)

を含めると AI レビューと人間レビューがスムーズです。

## スキーマリファレンス

すべてのフィールドの完全な仕様は [[reference/config-yaml-reference]] (Phase 1 完了時に整備) を参照。

## 既存武器との対比

[[game-design/weapons/catalog]] (Phase 後半で自動生成) で全武器のスペック表が見られます。

## トラブルシューティング

### 武器がゲーム内に出ない
- YAML がパース失敗していないか: `docker compose logs paper | grep ERROR`
- id がユニークか (既存と被ると lazy override される)

### エフェクトが見えない
- particle/sound の id が `vanilla:` 接頭辞付きか
- player の particle 設定が ON か (`/astera settings particle on`)
