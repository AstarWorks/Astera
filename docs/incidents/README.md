# Incidents

事故 (production incident) ごとに 1 ファイル、ファイル名は `YYYY-MM-DD-<slug>.md`。

Re-DIVERSE 流に **事後分析を蓄積する**ことで、運用知見をチームと AI agent が共有する。

## 書き方

[`TEMPLATE.md`](TEMPLATE.md) をコピーして使う。最低限以下を含める:

- **Summary**: 1〜2 行で何が起きたか
- **Timeline**: 何時に何があったか (UTC + JST 併記)
- **Root Cause**: 5 Whys で掘り下げる
- **Action Items**: 再発防止策 (チケット化されているなら link)

## 命名規則

- `YYYY-MM-DD-<slug>.md` (例: `2026-05-15-lobby-oom.md`)
- 日付は事故発生日 (検知日ではない)
- slug は kebab-case

## 共有

新規 incident 分析を merge したら Discord `#incidents` チャンネルに通知 (Phase 後半で自動化)。

## 過去の incident

(なし。Phase 1 中で運用が始まれば追加される)
