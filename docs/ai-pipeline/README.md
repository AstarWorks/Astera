# AI Pipeline

AI 委託パイプラインのドキュメント。

**Phase 1〜5 では生成 AI は使わない** ([[adr/0010]])。Phase 6 で本格解禁する。

## ファイル (Phase 6 以降で内容拡充)

- `overview.md` — 各 agent / skill の責務
- `delegation-policy.md` — 人間レビュー必須ラインの定義
- `agents/` — 個別 agent の仕様 (weapon-designer, texture-generator, sound-generator, model-generator, stage-builder, translator, release-noter, ugc-reviewer, incident-responder)
- `skills.md` — `/new-weapon` 等 slash command 一覧
- `prompts.md` — 共通プロンプト規約
- `safety.md` — モデレーション + 出力検証
- `cost-model.md` — API 利用予算管理

Phase 1〜5 は枠だけ。
