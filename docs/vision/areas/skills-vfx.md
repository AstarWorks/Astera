# Area: skills and VFX (Limbus track)

Owner track `wp/skills-vfx`. Items: Board prefix `SKL-`. Sources: handoff `limbus-skill-team` (2026-10-25), `docs/contributing/limbus-skill-reproduction.md`, `docs/reference/vfx/`.

## 構想 (source)
- Reproduce Limbus Company skills in Minecraft: motion first, then VFX (Display core + particles), then sound.
- Founder 2026-10-25: motion roughly the same as the reference FIRST; Minecraft-look design later; first person out of scope; match the third-person side view first.
- Work split by part: modeler, weapon motion, character body motion (skin rig, ADR-0250), BlockDisplay VFX core, particles, sound.
- **Done = reviewer PASS + founder approval.** The reviewer judges motion match per beat against the reference and the founder's words, never particle counts.

## 現状
- 基準点は 079262164 (integrate19, 2026-10-30)。スキル engine は v3 (ADR-0303, Status **Proposed**, rev4.14 `docs/adr/0303-natural-skill-schema.md`)。
- スコープ: `content/skills/` に Limbus は bastion-greatsword (def/s1/s2/ego) と couture-rapier (def/s1/s2/s3/ego) の 9 文書のみ。他 8 family は founder 2026-10-29 の決定で削除 (`content/skills` に id なし)。基本スキルは残し v3 へ移行、sample-* 14 を含め計 131 文書 (ls)。
- 時間: 事前 compile された tick 表 (TablePlayer) を進める。ランダムは visual のみ (founder 10/29, ADR-0303 rev4 precompute contract, `TablePlayerRulesScenarioTest.kt`)。
- 継承: tier は `extends` で base skill から派生 (ADR-0303 l.921-933)。
- detach / tail: 表示行は detach 可能 (rev4.12)。tail 中の run の live-value 欠落で cast が abort する不具合を修正 (`a8f6e0d71`, `RigAttachScenarioTest.kt`)。
- ハンドラ drain: hit / block / expire の handler 行は final 後も drain (rev4.14, check 8b `HandlerCertainExit`, ADR-0303 l.1091)。死亡・退出・ワールド変更・試合終了は draining cast を切る (`9586cefb7`)。
- 連鎖: `use: fragment` と `chain: {hops, every, pick}` (rev4.11)。chain-lightning は jagged polyline の core+glow (`f36064ddb`, `ChainLightningBeamScenarioTest.kt`)。
- Line: `segments` / `jitter` の polyline (rev4.14, `9983db096`, `LinePolylineScenarioTest.kt`)。
- Lint: check 10 = 全 tick の display peak を `max_live_displays` (default 64) と比較 (ADR-0303 l.1253, `82cc67df1`)。PackRefLint は display kind item/block も検査 (`d3a75999f`, `tools/content-lint/.../PackRefLint.kt`)。
- 銃の tracer: 射線は交差 2 quad の line ribbon (`1288f286d`, `GunTracerScenarioTest.kt`)。1 人称の muzzle は hand 位置 (`0fa37220d`)。
- end_rod: 撃ち込み・詠唱の burst 13 行を element 別の electric_spark / snowflake / crit に置換。count-1 の trail は end_rod のまま (`0428c51ab`)。content で end_rod を参照するファイルは 12 (grep)。
- 既知ギャップ: 1 枚 quad は片面のため側面視で消える (se-quad-cull は commit なし)。line の一般解 (item_display 1 segment, crossed double-faced model) は設計 accepted (`/tmp/claude-1000/skill-engine/design/line-primitive.md`) だが未実装 (ADR-0303 は rev4.14 止まり、line-item の commit なし)。chain-lightning の first link は途切れた dash に見える (manager 目視, 推測: line primitive で解消見込み)。
- 近カメラの crit / electric_spark の拡大は未確認 (shot 待ち、`/tmp/claude-1000/skill-engine/board-pending.md` l.411)。
- 検証: 079262164 時点の game-sim は 724 件中 30 件 fail (`board-pending.md` l.421)。実クライアントの再撮影と e2e は manager の統合 window 待ち。
- 14 `sample-*` (machine/display 形式) と shader demo 3 件 (ADR-0305, `content/vfx/demo-shader-{dissolve,uv-flow,emissive-pulse}.yaml`)。旧 capture は v2 世代の記録。v3 での再撮影は未。
- ツール: skill-compare (`make skill-compare-up`, 192.168.11.5:8097) が閲覧入口。`make live-view SKILL=<id>` が実 Java クライアントの capture (`tools/live-view/README.md`)。sprite は `tools/item-art` の YAML node graph (ADR-0304)。
- bastion S1 は v2 世代の r1-r26 が founder に却下 (「S1 got worse」)。v3 での founder 確認は未。couture-ego の look は v1 に戻した (`242c54b39`) が founder 確認待ち。
- 高速化案 (warm vfx-shot stack, keypoint → IK, local client particle sim) は founder go 待ち。

## 理想形
Each skill is a short performance: caster body motion, one real weapon, Display-core VFX, finishing particles and sound, all driven from the reference beats. Reviewer rows are tied to per-beat reference frames; captures are client-faithful (never an approximation).

## 未決 (founder, unanswered)
- First-person empty hand and the global instant hotbar swap.
- ADR-0274 (server-wide particle reskin) and ADR-0275 (held-sword shading) landed without approval: keep or revert?
- When and by whom are the other 9 families re-reviewed?

## Governing ADRs
0231, 0238, 0239, 0241, 0250, 0274, 0275, 0276.
