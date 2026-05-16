# content/

User-editable game content. **Anything in here can be added/changed by non-Kotlin contributors** — open a PR with a YAML file and you're done. See [docs/contributing/add-a-weapon-yaml.md](../docs/contributing/add-a-weapon-yaml.md).

## Layout

```
content/
├── weapons/        武器定義 (YAML 1 ファイル = 1 武器)
├── skills/         スキル / 状態異常 (Phase 2 以降)
├── stages/         マップ・ステージ (Phase 3 以降)
└── languages/      i18n リソース (ja, en, ...)
```

## Schema policy

Phase 1 の schema は**最小限**。Phase 2 でスキル/状態異常システムが入る時に拡張する予定。後方互換性は努力目標であって保証ではない (Phase 後半で `version:` フィールド導入予定)。

正式 schema は将来 [docs/reference/config-yaml-reference.md](../docs/reference/config-yaml-reference.md) (Phase 後半で生成) に固定される。

## Reload

Phase 1 ではサーバー起動時のみ読み込み。Hot reload は Phase 2 以降。
