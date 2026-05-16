# docs/ の使い方

このディレクトリは Astera の**生きた設計ドキュメント**です。

## 原則

- **1 ファイル 1 主題**: 長文の README に詰め込まない
- **ADR で「なぜ」を残す**: 後から AI も人間も判断できるように
- **コードと同じ PR で更新する**: `architecture/` は実装と同期
- **絶対 path リンク前提**: GitHub レンダリングと Claude Code 両対応

## エントリポイント

すべては [`INDEX.md`](INDEX.md) から辿れます。

## 書く時のルール

### ADR (`adr/`)

- 番号順 (`NNNN-slug.md`)
- 状態: Proposed / Accepted / Superseded / Deprecated
- 一度書いたら**変更しない**。変えたい時は新 ADR を起こして supersede 関係を貼る
- テンプレート: [`adr/0000-template.md`](adr/0000-template.md)

### Architecture (`architecture/`)

- コード変更 PR と同じ PR で更新する
- 「現在こうなっている」を書く。歴史は ADR にある

### Incidents (`incidents/`)

- 事故ごとに 1 ファイル、`YYYY-MM-DD-<slug>.md`
- テンプレート: [`incidents/TEMPLATE.md`](incidents/TEMPLATE.md)
- 5 Whys / Timeline / Action Items を含める

### 言語

- Phase 1 は日本語ベース
- 英語 twin docs は強制しない (将来 translator agent が補完)
