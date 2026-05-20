---
status: Accepted (Studio extracted to AsteraStudio repo on 2026-05-20 per ADR-0079; this file is preserved here for historical cross-reference, primary copy now lives in AsteraStudio/docs/adr/)
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0062: ASDSL v2 schema consolidation (B6-B11)

## Status

Accepted

## Context

2026-05 の OSS 監査 (`docs/research/model-gen-llm-survey.md` §5.4) で ASDSL
(Astera Shape DSL) に対する schema-level 改修案が 6 件 (B6-B11) 並列に出た。
個別実装すると schema 変更が複数 PR に分散し、LLM-facing contract が漸進的
に揺れる。Astera は Phase 1 PoC closing 中で後方互換要求ゼロ
(`.claude/CLAUDE.md §"Pre-release posture"`) なので、**1 本の ADR で v2
schema として統合し、breaking change を 1 リリースで済ませる**。

### 6 件の出典

| Tier | 概要 | 出典 |
|---|---|---|
| **B6** diff-editing 強制 (`patch` verb) | `asdsl place` の re-call 禁止、差分編集の prompt enforcement | LL3M (§3.5b → §8.2) |
| **B7** diagnostics-first 宣言的 assertions | ASDSL part に `assertions: [...]`、envelope に `diagnostics` 追加 | cad-khana (§10.1.1) |
| **B8** 5-status truth envelope | `{ok}` → `{status: OK\|INFO\|WARNING\|ERROR\|UNKNOWN}` | Onshape MCP × Claude Opus 4.7 (§9.2.1) |
| **B9** `image_id` + `crop_image` | iter-screenshot 戻り値を SHA256-keyed cache id に間接化 | Onshape MCP (§9.2.3) |
| **B10** 構造化 geometry diff | `changes` に texture refs と summary を加える | Onshape MCP (§9.2.2) |
| **B11** `@cad[name]` handle syntax | cross-iteration part 参照、diff edit と整合的 | text-to-cad / earthtojake (§10.1.3) |

### v1 partial alignment (Phase 1 探索で判明)

ASDSL v1 surface は survey が想定したより成熟しており、**6 件のうち 3 件
(B6 / B8 / B10) は v1 で部分実装済み**である。ADR の語り口は "big-bang v2
redesign" ではなく "**Tier B 一括 schema 整流 (部分実装の正規化 + 不足分
の追加)**" となる:

- **B6**: v1 に `move_part` (field-level shallow merge) 既存
  (`tools/studio/web/server/asdsl/state.ts:86-95`)。差分は verb rename と
  `place_part` の既存名 overwrite を禁止する semantic 変更。
- **B8**: v1 envelope は `'OK' | 'INFO' | 'WARNING' | 'ERROR'` の 4-status
  既存 (`tools/studio/web/server/asdsl/types.ts:144`)。差分は `UNKNOWN`
  追加 + INFO/WARNING 境界線の semantic 明文化。
- **B10**: v1 `changes` は `bbox_delta`, `voxel_count_delta`,
  `parts_added/modified/removed` を構造化済み (`types.ts:155-167`)。差分は
  `texture_refs_added`/`texture_refs_removed`/`summary` の追加。

残る 3 件 (B7, B9, B11) は新規追加。

### なぜ 1 本に統合するか

- envelope 拡張 (B8 + B10) と DSL 拡張 (B6 + B7 + B11) と iter-screenshot
  戻り値変更 (B9) は **互いに依存し合う single LLM-facing contract** を
  形成する。B7 の `diagnostics` array は B8 envelope への field 追加を前提
  とし、B11 の `@cad[name]` は B6 の patch verb との組み合わせで意義を
  完成する。
- 6 件を別々の ADR にすると ordering / partial-merge state が生まれ、
  proposed → accepted → superseded の遷移管理が複雑化する。
- Phase 1 PoC closing posture が破壊的変更の bundling を許す。

## Decision

ASDSL v2 として下記 6 項目を同時導入する。互換 shim・dual-decoder・
`@Deprecated` alias は一切書かない。

### 1. Truth envelope (B8 + B10)

`tools/studio/web/server/asdsl/types.ts` の `Envelope<T>` を下記に拡張:

```typescript
type EnvelopeStatus = 'OK' | 'INFO' | 'WARNING' | 'ERROR' | 'UNKNOWN';

type Envelope<T> = {
  ok: boolean;
  status: EnvelopeStatus;
  error_code?: string;
  hints: Hint[];
  changes: {
    bbox_delta?: { min: Vec3; max: Vec3 };
    voxel_count_delta?: number;
    parts_added?: string[];
    parts_modified?: string[];
    parts_removed?: string[];
    texture_refs_added?: string[];      // NEW (B10)
    texture_refs_removed?: string[];    // NEW (B10)
    summary?: string;                   // NEW (B10) — human-readable one-liner
  };
  diagnostics?: AssertionResult[];      // NEW (B7) — see §3
  data?: T;
};
```

**INFO / WARNING / ERROR / UNKNOWN の semantic**:

- **OK** (`ok=true`): mutation succeeded、systems nominal。
- **INFO** (`ok=true`): compiler が機械的に rectify した auto-adjust。
  agent は ignore してよい。例:
  - rotation snap to `{-45, -22.5, 0, 22.5, 45}`
  - palette quantize で nearest color に clamp
  - part bbox を MC voxel bounds に auto-clip
- **WARNING** (`ok=true`): soft constraint violation。mutation 自体は成功
  したが agent が再評価判断すべき。例:
  - B7 assertion 失敗 (e.g., `aligned_with` 違反、`fits_within_voxel_envelope`
    違反)
  - part 同士の overlap
  - unused variable / unused texture slot
  - 依存型 dead-end
- **ERROR** (`ok=false`): mutation 失敗。例: `UNKNOWN_PART`,
  `DEPENDENCY_CYCLE`, `ALREADY_EXISTS`。
- **UNKNOWN** (`ok=false`): status metadata 欠落 (defensive default)。
  通常出ない。出たら envelope 生成側のバグ。

### 2. `patch_part` verb (B6)

- `move_part` HTTP endpoint を `patch_part` に rename
  (`tools/studio/web/server/routes/asdsl.ts`)
- semantic: 現行の field-level shallow merge を踏襲 (JSON Patch RFC 6902
  は採用しない — over-engineering)
  - patch dict の field のみ既存 part に merge、`kind` は変更不可
  - patch fields: `relative_to`, `align`, `delta_*`, `anchor`, `size`,
    `length`, `base_size`, `tip_size`, その他 shape-specific params
- `place_part` の semantic 変更: 既存名は `ALREADY_EXISTS` ERROR を返す
  (現行は overwrite していた)。新規 part のみ受け付ける
- LL3M (§3.5b) の "diff editing 強制" を verb-level で enforce

### 3. `assertions` schema + diagnostics (B7)

ASDSL `Part` schema に optional `assertions` field を追加:

```typescript
type Assertion = AlignedWith | NoSelfIntersection | FitsWithinVoxelEnvelope | ClearanceMin;

type AlignedWith = {
  kind: 'aligned_with';
  a: string;  // "part_name.anchor_slot" e.g. "blade.base_center"
  b: string;
};

type NoSelfIntersection = {
  kind: 'no_self_intersection';
};

type FitsWithinVoxelEnvelope = {
  kind: 'fits_within_voxel_envelope';
  width: number;
  height: number;
  depth: number;
};

type ClearanceMin = {
  kind: 'clearance_min';
  from: string;   // "part_name"
  to: string;     // "part_name"
  voxels: number;
};
```

戻り envelope に `diagnostics: AssertionResult[]` を追加:

```typescript
type AssertionResult = {
  kind: string;
  part_name: string;
  passed: boolean;
  detail?: string;
};
```

- 形式は **object 形式** (kaml-friendly、parser は switch on `kind`、kind
  追加で schema 安定)
- 関数呼び出し風文字列 (`aligned_with(blade.base, guard.top)`) は採用
  しない (parser 実装 2 倍、type-safety run-time のみ)
- assertion 失敗は **WARNING** (ok=true) として envelope に surface。
  compile 自体は止めない
- 初期 kind は 4 種 (`aligned_with`, `no_self_intersection`,
  `fits_within_voxel_envelope`, `clearance_min`)。追加は post-merge で
  可 (additive)

### 4. `@cad[name]` cross-iteration handle (B11)

ASDSL DSL に cross-iteration ref 構文を導入:

```json
{
  "parts": {
    "guard": {
      "kind": "cuboid",
      "size": [8, 2, 4],
      "relative_to": "@cad[blade]",
      "align": "base_center"
    }
  }
}
```

- v1 の `relative_to: "blade"` (same-document) を上位互換で extend:
  - `relative_to: "blade"` — same-document ref (現行と同じ、後方互換 OK
    だが「並存」ではなく upgrade として扱う)
  - `relative_to: "@cad[blade]"` — prior-state ref (新規)
- anchor resolver (`tools/studio/web/server/asdsl/anchor.ts:135 resolve()`)
  が `@cad[...]` を prior state map から pluck できるよう拡張
- patch verb (B6) と組み合わせて selective mutation 実現 (LLM が full
  re-emit せず既存 part に対する patch + 新規 part の `@cad` ref で
  delta-only emit 可能)
- text-to-cad (§10.1.3) の `@part[barrel].rotate(15deg)` から着想

### 5. `image_id` + `crop_image` (B9)

iter-screenshot 戻り値を inline PNG bytes から SHA256-keyed cache 参照に
切り替え:

```typescript
type RenderedView = {
  view_id: string;        // "iso" | "top" | "front" | "right"
  image_id: string;       // "img_<sha256-hex>"
  width: number;
  height: number;
  bytes_size: number;     // size only, not the bytes themselves
};
```

新規 tool:

- `crop_image(image_id, bbox)` — cache hit で再 render なし
- `crop_model_view(view_id, part_anchor)` — MC-native 拡張、named part の
  bbox に crop (vision-decompose が limb 単位 inspect 可能に)

cache lifecycle:

- ASDSL session lifetime 単位 (reset 時 flush)
- in-process LRU、初期 size は 100 entries 程度 (調整は実装時)

### 6. Implementation 順序 (次 session 向け)

論理依存関係に従う:

1. **Envelope refinement** (B8 `UNKNOWN` + B10 fields) — pure type addition、
   他全ての変更が依存する foundational change
2. **`patch_part` verb** (B6) — API surface 変更、後続 worker が新 verb を
   前提に書ける
3. **`@cad[name]` syntax** (B11) — DSL parser + anchor resolver 拡張
4. **`assertions` + `diagnostics`** (B7) — Envelope の B8 `UNKNOWN` + B7
   semantic 確定後 (assertion 失敗を WARNING に流す配線が前提)
5. **`image_id` + `crop_image`** (B9) — iter-screenshot pipeline、他項目と
   独立、別 PR 可

## Consequences

### Positive

- LLM-facing contract が 1 リリースで揃う (envelope + verb + DSL + iter
  戻り値が同時 update)
- diff edit (B6 + B11) と diagnostics-first (B7 + B8) の組み合わせで
  iteration 回数低減 (LL3M / cad-khana の知見を直輸入)
- truth envelope 5-status により agent が "成功 + 自動修正" を silent
  ignore 可能、surface noise 低減 (現行は全 hint flat、auto-adjust と
  soft violation の区別不可)
- `image_id` indirection で iter-screenshot の memory pressure 低減 +
  crop による spatial inspection が cheap に
- `@cad[name]` で full re-emit を強制せず、LLM の output token 量も削減

### Negative / Trade-offs

- `move_part` → `patch_part` の verb 切り替えで build-model skill
  SKILL.md と Studio UI prompt template 双方の改訂が必要
- `assertions` DSL の object 形式は LLM が emit する JSON 量が文字列形式
  より verbose (kind + args の objectification cost)
- `image_id` 導入で iter-screenshot の cache lifecycle (memory pressure)
  管理が新たな運用負担
- `place_part` の "既存名 → ERROR" semantic 変更は、LLM が誤って overwrite
  しようとした場合に明示的 ERROR で stop する。これは意図 (silent
  overwrite を防ぐ) だが LLM prompt 側で「先に describe して既存名を
  避ける」protocol を追加する必要
- 5-status の INFO/WARNING 境界線は judgement call が残る。post-merge で
  例が積み上がる中で refine

### Mitigations

- skill / Studio prompt 改訂は本 ADR 採択直後の単発 PR で吸収
  (deferred されない、別 wave に持ち越さない)
- 必要なら DSL parser 側で文字列糖衣 (`aligned_with(a, b)` → object) を
  後追い実装可能 (今回は採用しない、ADR の retrospective 拡張可能性として
  残す)
- iter-screenshot cache TTL を ASDSL session lifetime 単位 (reset 時 flush)
  に bound、in-process LRU で memory ceiling を保証
- INFO/WARNING 境界線は本 ADR の §Decision 1 に例を列挙、実装時に
  borderline ケースが出たら ADR-supersede で更新 (本文 immutable 原則は
  維持、Status のみ更新)

## Alternatives Considered

- **ADR 6 本分散**: B6 / B7 / B8 / B9 / B10 / B11 を ADR-0062 〜 0066 に
  個別起票。履歴粒度は上がるが overhead 大、6 件が互いに参照し合うので
  consolidation の方が読みやすい。reject。
- **ADR 2 本 (envelope vs grammar)**: ADR-0062 "truth envelope + structured
  changes + image_id" (B8/B9/B10) と ADR-0062 "DSL extensions: patch +
  assertions + @cad" (B6/B7/B11) に分割。defensible だが ordering /
  partial-merge state を生む (片方が merge され片方が遅れた場合に v1.5
  状態が発生)。reject。
- **`assertions` 文字列形式** (`'aligned_with(blade.base, guard.top)'`):
  cad-khana 流儀、LLM emit が短い。だが mini-parser (BNF + arg escaping)
  実装必須で type-safety run-time のみ、kind 追加で parser 改訂。kaml-
  friendly object 形式を採用。reject。
- **v1 並存 (typealias / dual-decoder / @Deprecated alias)**: Pre-release
  posture (`.claude/CLAUDE.md`) が明示禁止。reject。
- **JSON Patch RFC 6902 ベースの `patch_part`**: pointer ベースで強力だが
  Astera の part field 数は 10 未満で shallow merge で十分。over-
  engineering。reject。

## Breaking Changes (Phase 1 posture 適用範囲)

下記は migration shim なしで一発置換する。**`.claude/CLAUDE.md
§"Pre-release posture"` + `.claude/rules/coding-posture.md §"Pre-release
breaking changes"` の forward-only commit 方針に従う**:

| 変更 | 種別 | 影響先 |
|---|---|---|
| `move_part` HTTP endpoint 削除 → `patch_part` に rename | API breaking | build-model skill prompt、Studio UI prompt template、Studio CLI |
| `place_part` 既存名 → `ALREADY_EXISTS` ERROR (現行は overwrite) | Semantic breaking | LLM iteration protocol |
| `Envelope.status` enum に `UNKNOWN` 追加 | Additive (列挙網羅 `when` は build-fail で catch) | envelope consumer 全般 |
| `Envelope.changes` に `texture_refs_added`/`texture_refs_removed`/`summary` 追加 | Additive | 既存 caller は無視可 |
| `Envelope.diagnostics` 追加 | Additive | 既存 caller は無視可 |
| `Part` schema に `assertions?: Assertion[]` 追加 | Additive | 既存 YAML は変更不要 |
| `relative_to` field が `@cad[name]` 構文を受け付け | 上位互換 extend | 既存 same-document ref は引き続き valid |
| iter-screenshot 戻り値: inline PNG → `RenderedView` (`image_id` 間接化) | API breaking | build-model skill、Studio UI |

理由: 後方互換要求ゼロ (`.claude/CLAUDE.md §"Pre-release posture"`)、
release 前 deprecation は単なる decay (`.claude/rules/coding-posture.md
§"Pre-release breaking changes"`)。`@Deprecated` alias、旧 type の
typealias、移行期間コメントは一切書かない。

## References

- 関連 ADR:
  - [[adr/0017-adopt-datadriven-contents-pattern]] — datadriven SSOT pattern
    (off-limits per `.claude/rules/wave-execution.md`、本 ADR は触らない)
  - [[adr/0018-ai-asset-creation-policy]] — studio/texture carve-out
    (off-limits per `.claude/rules/wave-execution.md`、本 ADR は触らない)
  - [[adr/0027-phase-1-poc-closing-scope]] — Phase 1 PoC closing scope の
    根拠
- 関連 docs:
  - `docs/research/model-gen-llm-survey.md` §5.4 (B6-B11 提案本体)
  - 〃 §9.2.1 (5-status truth envelope)
  - 〃 §9.2.2 (構造化 geometry diff)
  - 〃 §9.2.3 (image_id + crop_image)
  - 〃 §10.1.1 (cad-khana diagnostics-first)
  - 〃 §10.1.3 (text-to-cad `@cad[name]`)
- 関連実装:
  - `tools/studio/web/server/asdsl/types.ts` (Envelope, Part union, AnchorSlot)
  - `tools/studio/web/server/asdsl/anchor.ts` (resolve(), ResolvedPart)
  - `tools/studio/web/server/asdsl/state.ts` (placePart, movePart, removePart)
  - `tools/studio/web/server/asdsl/compile.ts` (expandPart, quality tiers)
  - `tools/studio/web/server/asdsl/envelope.ts` (Hint examples)
  - `tools/studio/web/server/routes/asdsl.ts` (10 HTTP endpoints)
  - `.claude/skills/build-model/SKILL.md` (documented v1 grammar + hard rules)
- 外部:
  - LL3M (arXiv 2508.08228) — diff editing
  - cad-khana — declarative assertions on build123d
  - Onshape MCP × Claude Opus 4.7 (Reshef Elisha, 2026) — 5-status envelope
    / image_id / structured diff
  - text-to-cad (earthtojake) — `@cad[name]` handle syntax
