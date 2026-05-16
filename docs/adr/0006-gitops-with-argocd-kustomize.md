---
status: Accepted
date: 2026-05-16
deciders: ryuzu
---

# ADR-0006: GitOps with ArgoCD + Kustomize

## Status

Accepted

## Context

RTM の `kubernetes/minecraft.yml` は空。実運用には k8s manifest の管理戦略が必要。

Re-DIVERSE_infrastructure は **ArgoCD + Kustomize の App-of-Apps パターン**で確立した運用がある:
- `deploy/` を Git に置き ArgoCD が自動 sync
- `automated.prune + selfHeal` で git が真実の源
- root app (`argocd-apps`) が子 app (`minecraft`, `platform`) を管理

## Decision

Astera も **ArgoCD + Kustomize の App-of-Apps** を採用。`deploy/` 配下を Astera 自身の repo で管理し、ArgoCD は root app を 1 つ apply するだけでクラスタが自己構築するようにする。

## Consequences

### Positive
- `git push` がデプロイ。クラスタ操作の差分が全て履歴に残る
- Re-DIVERSE と運用パターンを共有できる
- 環境ごとの差分は Kustomize overlay で表現できる
- ArgoCD UI で同期状態を可視化できる

### Negative / Trade-offs
- ArgoCD 自体の運用 (アップグレード / セルフマネジメント) が必要
- 初期セットアップ手順が増える

### Mitigations
- ArgoCD は `argocd-self-manage` パターンで自己管理させる ([[infrastructure/argocd-setup]])
- Phase 1 では `private/infra/` で本番固有の overlay を分離

## Alternatives Considered

- **手動 `kubectl apply`**: 履歴が残らず再現性が低い
- **Helm**: テンプレート言語の複雑さ。kustomize の方が宣言的で読みやすい
- **Flux**: ArgoCD と類似。Re-DIVERSE 整合性で ArgoCD 採用

## References

- 計画書 §3.3
- Re-DIVERSE_infrastructure `apps/argocd-apps.yaml`
- [[infrastructure/argocd-setup]]
