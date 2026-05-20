---
status: Accepted (Studio extracted to AsteraStudio repo on 2026-05-20 per ADR-0079; this file is preserved here for historical cross-reference, primary copy now lives in AsteraStudio/docs/adr/)
date: 2026-05-20
deciders: Astera workspace (post Wave _items-1 retrospective)
supersedes: [[adr/0061]], [[adr/0073]] (Tier I — Studio UI 部分のみ)
superseded_by: null
---

# ADR-0075: Remove Studio `/items` tab — read-only YAML preview adds insufficient plus-alpha

## Status

Accepted

## Context

Phase 1 PoC closing 中の Astera Studio は ADR-0061 で `/weapons` route を、
ADR-0073 Tier I で `/items` route を導入した。両者は **YAML を bowser
側で list + 詳細 preview する read-only UI** だった:

- list pane: `content/items/*.yaml` を全件 sidebar 表示
- detail pane: 1 item の 3D model preview + material icon + i18n 表示
  (display_name / lore を ja/en で切り替え) + attributes table + skill
  bindings table

実装規模は frontend 約 400 行 (`ItemsView.vue` + `ItemPreview.vue` +
`lib/i18n.ts` + `I18nText.vue`) + backend 約 180 行 (`server/routes/items.ts`)
+ 関連 API 4 つ + i18n loader (`server/routes/languages.ts`) + 関連 path
constants 2 つ。

**しかし retrospective で判明した致命的な認知ズレ:**

1. **ユーザーが期待していた価値**: 「MC 起動なしで skill を browser で
   triggerして visualization できる playground」 (post-merge feedback)。
2. **実装した価値**: 「YAML を見るだけ」。skill bindings table は read-only、
   click handler なし、3D preview は archetype hardcode (`items.ts:45`
   `ARCHETYPE_MODEL`) で gun/wand 全部 `sword.model.json` に解決される
   ため実質壊れている。

つまり「ユーザーが意義を見出す機能 = 未実装」「現状実装機能 = エディタで
YAML 直接編集すれば足りる」状態。Plus-alpha と言えるのは以下 3 点:

- 3D モデル preview (←ただし archetype hardcode で実質ほぼ壊れている)
- マテリアル icon と inline i18n の解決表示
- 全 item リスト + 検索

これら 3 つはいずれも `bun run dev` で起動して MC textures cache が
warm な状態で初めて成立する。YAML 編集体験そのものを劇的に改善する
ものではなく、追加価値は実装コスト (frontend 400 + backend 180 + i18n
infra + 関連テスト) を正当化できる規模ではない。

**CLAUDE.md §Pre-release posture**: Phase 1 PoC closing 中は破壊的
変更を積極的に取る。fallback shim や @Deprecated は残さず forward-only
で削除。Skill Playground (真の plus-alpha) は別 Wave で改めて設計。

## Decision

**`/items` tab + 関連 backend/frontend インフラを撤去する**。

Wave _items-1 で導入された ADR-0073 Tier I (Studio UI 部分) と、その前身
ADR-0061 (`/weapons` preview route) は本 ADR で supersede。`content/items/`
の YAML 自体および `plugin:items` runtime は一切変更しない。

### 撤去対象

**Frontend** (`tools/studio/web/src/`):
- `routes/ItemsView.vue` — list + detail pane
- `components/ItemPreview.vue` — 3D + meta 表示
- `components/I18nText.vue` — i18n helper (Items 以外で未使用)
- `lib/i18n.ts` + `lib/i18n.test.ts` — locale loader (Items 以外で未使用)
- `main.ts` から ItemsView import + `/items` route 登録
- `api.ts` から Item types (`ItemListEntry` / `LoadedItem` /
  `ItemYamlConfig` / `ItemSkillBindingYaml` / `ItemAttributesYaml`) と
  関連 API メソッド (`api.items` / `loadItem` / `itemModelBySlug` /
  `itemModelByArchetype` / `languages` / `loadLocale`)
- `App.vue` から `<RouterLink to="/items">⚔️ アイテム` タブ

**Backend** (`tools/studio/web/server/`):
- `routes/items.ts` — Item YAML list + load + model 解決
- `routes/languages.ts` + `routes/languages.test.ts` — i18n loader
  (Items 以外で未使用)
- `index.ts` から両 route の import + mount
- `paths.ts` から `itemsContentDir` + `languagesDir` exports

**Doc**:
- `README.md` API table + env var table から /items / /languages 行と
  STUDIO_LANGUAGES_DIR / STUDIO_WEAPONS_DIR / STUDIO_ITEMS_DIR 行を削除

### 撤去しない (runtime 側は一切変更なし)

- `content/items/*.yaml` — YAML 自体 (plugin:items が読み続ける)
- `plugin/items/` の application + adapter — Paper runtime での item
  registration / skill binding は無関係に動作継続
- `content/languages/{ja,en}.yaml` — runtime i18n で plugin:core が利用
- `ADR-0073` の Item-centric data model 決定 — Tier I (UI) のみ supersede、
  Tier II/III (multi-skill binding + inline i18n) は確定済として残る

## Consequences

### Positive

- **コード削減**: ~600 行 (frontend 400 + backend 180 + test 20) を一気に
  削除。Studio の責務が「textures / models / effects」に絞られ、各 route
  の意図が明確化。
- **誤誘導の解消**: Studio を初めて見たユーザーが「アイテム」タブを開いて
  「skill が試せそうな雰囲気があるのに何もできない」体験をしなくなる。
- **将来再設計の余地**: Skill Playground (下記 Future work) を ADR-0073
  の制約に縛られず clean に設計できる。

### Negative / Trade-offs

- **All items 俯瞰機能の消失**: 「全 sword YAML を一覧」「inline i18n を
  resolve して表示」ができなくなる。代替: 普通に `ls content/items/` +
  YAML editor (VS Code, IDEA 等) で十分。
- **3D preview UI のregression**: Studio 内で item ↔ model の結合関係を
  視覚的に確認する道が消える。`/models` route で model 単体は依然 preview
  可能。`content/items/<id>.yaml` の `model:` field と
  `content/models/<slug>.model.json` の対応は手動確認。

### Mitigations

- 将来 Skill Playground (post Phase 1) で `/items` の優位機能 (3D preview
  + i18n 解決 + skill binding 表示) を「**実際にスキルを動かせる**」価値
  と統合して再設計。read-only viewer 単体の機能では戻さない。
- `content/items/<id>.yaml` 編集時の体験は VS Code / IDEA のような汎用
  YAML エディタが既に提供 (schema validation、syntax highlight、search)。
  Studio 固有の優位は「3D + skill 実行」両揃いの場合のみ意義がある。

## Future work — Skill Playground (Proposed, not implemented)

ユーザーが当初期待していた「**MC 起動なしで skill が試せる**」を
真に成立させるには、純粋な YAML viewer ではなく実行 sandbox が必要。
要件スケッチ (実装は別 Wave、本 ADR では概念のみ):

1. **Skill 実行 runtime の切り出し** — `plugin:combat` 内の skill
   resolution / damage calculation / status effect application を Kotlin
   から TypeScript / WASM / shared headless runtime に切り出して
   browser から呼べる形にする (もしくは headless JVM を WebWorker
   相当で動かす)。ADR-0074 (composable-skill-effect-tree) と連動。
2. **Three.js でターゲットダミー + エフェクト visualization** —
   `fxgraph` の particle / sound stub を browser でも再生できるよう、
   `content/effects/` の export 形式を runtime-agnostic に標準化。
3. **時間制御 UI** — slow-mo / pause / replay / step。ダメージ popup、
   status effect tick、projectile flight を時間軸でスクラブできる。
4. **Item と skill の結合確認** — Skill Playground 上で「この item を
   持って left-click したら何が起きる」を再生できる (Items tab で
   本来したかったやつ)。
5. **regression test 兼用** — Playground 実行ログを golden snapshot
   として diff し、skill 改修時の挙動変化を視覚的 + 数値的に catch。

実装規模は 1 Wave 以上 (Skill runtime 切り出しが最大)。Phase 1 PoC
closing が片付き、Phase 2 (combat polish) のスコープ確定後に着手判断。
本 ADR は **要件のみ Proposed 状態で残す**。

## Alternatives Considered

- **Items タブを残して bug 修正 + UX banner 追加** (中規模 ~50 行):
  ARCHETYPE_MODEL を動的に解決し、UX banner で「Read-only preview.
  Skills cannot be triggered here yet」を明示。**却下理由**: skill
  発動できない状態のままでは plus-alpha が薄く、Wave _items-1 の
  「Item 中心化」と矛盾しない範囲で改修しても根本問題 (read-only
  以上の価値が無い) は残る。
- **Items タブを feature flag (`STUDIO_FEATURE_ITEMS=1`) で隠す**
  (低コスト ~10 行): コードを残しつつ default 非表示。**却下理由**:
  CLAUDE.md §Pre-release posture が「`@Deprecated`、移行期間コメント、
  fallback shim を残すな」と明示。flag 隠蔽は実質 fallback shim 相当で
  同じ問題 (リリース後に判断負債化)。今のうちに clean に削除。
- **validation + hot-reload を足して Items を育てる** (大規模 ~200 行):
  cross-ref validation (skill_id / texture path) + YAML watcher で
  自動再読込。**却下理由**: skill 実行不能の本丸が残ったまま周辺機能
  だけ育てても「ゴミ」評価は変わらない。育てるなら Skill Playground
  という別設計が筋。

## References

- 関連 ADR:
  - [[adr/0061]] Studio `/weapons` preview route — 本 ADR で supersede
    (本文 immutable、Status のみ Superseded に更新)
  - [[adr/0066]] Promote studio to mainline — `/items` route が mainline
    であることを宣言した ADR。本 ADR の削除はその mainline scope を
    縮小するが、Studio の mainline 位置付け自体は不変
  - [[adr/0070]] Weapon-item-model chain — `ARCHETYPE_MODEL` の不完全な
    archetype→model 解決が本 ADR 撤去の補強根拠
  - [[adr/0073]] Item-centric content model — Tier I (Studio UI) のみ
    本 ADR で supersede。Tier II/III (data model + multi-skill binding +
    inline i18n) は確定済として残る
  - [[adr/0074]] Composable skill effect tree — 将来 Skill Playground で
    runtime 切り出しが必要になる skill resolution の SSoT
- 関連 docs:
  - [[contributing/add-an-item-yaml]] — content authoring 体験は YAML +
    editor で完結する前提に整合
- 外部リンク:
  - MythicCrucible / Oraxen / ItemAdder の Studio 相当機能との比較
    (どれも runtime 連動した skill preview を持っていない — Skill
    Playground は他 plugin にもない新規領域)
