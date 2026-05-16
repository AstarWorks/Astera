---
date: YYYY-MM-DD
severity: SEV-1 | SEV-2 | SEV-3
duration: <minutes>
responders: ...
status: Resolved | Ongoing
---

# Incident YYYY-MM-DD: <Title>

## Summary

1〜2 行で何が起きたか。ユーザー影響と原因を簡潔に。

## Impact

- 影響を受けたプレイヤー数 / 試合数
- 影響を受けた機能
- ダウンタイム長

## Timeline

すべて JST + 24h 表記。

| 時刻 | 出来事 |
|---|---|
| 14:00 | Grafana で TPS が 5 を下回るアラート発火 |
| 14:02 | on-call が認知 |
| 14:05 | Lobby Pod の OOM 確認 |
| 14:08 | メモリ limit 引き上げで rollout |
| 14:15 | 復旧確認 |

## Root Cause (5 Whys)

1. **Why** TPS が落ちた?
   → Lobby Pod が OOMKill された
2. **Why** OOM?
   → メモリ使用が limit (6Gi) を超えた
3. **Why** メモリ使用が上昇した?
   → 新規追加されたパーティクルエフェクトがリークしていた
4. **Why** リークしていた?
   → エフェクトハンドルの dispose 漏れ
5. **Why** dispose 漏れに気づかなかった?
   → エフェクト周りに leak 検出テストがなかった

## Resolution

何をして直したか。

- メモリ limit を 8Gi に引き上げ (緊急対応)
- パーティクルハンドルの dispose 漏れを fix (#PR-123)

## Action Items

- [ ] エフェクト周りに leak detection テスト追加 (担当: @user, 期限: YYYY-MM-DD)
- [ ] Grafana に "particle effect handle count" panel 追加
- [ ] OOM 時の Pod dump 取得を有効化

## Lessons Learned

- 新機能投入時の memory profiling を CI に組み込みたい
- "TPS < 10" アラートを "TPS < 15" に下げて早期検知
