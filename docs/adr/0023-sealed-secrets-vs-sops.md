---
status: Proposed
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0023: Secrets — Sealed-Secrets adopted over SOPS

## Status

Proposed (Wave _ops Phase C close — flip to Accepted on user approval before push)

## Context

Astera は ArgoCD (GitOps) で全マニフェストを同期する構造 (`deploy/apps/argocd-apps.yaml`
の app-of-apps)。一方、Redis / Postgres / Discord webhook / RCON password 等の
secret は git の public リポジトリ (`deploy/`) には置けない。

現状の暫定取り扱い:

- 平文 secret は `deploy/**` 配下に **`REPLACE_ME` プレースホルダ** のみ commit
- 実値は `private/` (`AsteraPrivate` git submodule) の overlay 配下に居る
- 復号 / 注入経路は未確定 (=今回の決定対象)

選択肢は 2 派閥:

1. **[Bitnami Sealed-Secrets](https://github.com/bitnami-labs/sealed-secrets)** —
   `kubeseal` CLI で平文 `Secret` を `SealedSecret` (envelope-encrypted) に変換、
   git に sealed yaml を commit、cluster の sealed-secrets controller が復号して
   普通の `Secret` を生成。鍵は cluster scope (1 master key per cluster)。
2. **[Mozilla SOPS](https://github.com/getsops/sops)** (+ ArgoCD の SOPS plugin
   / kustomize-sops) — yaml の value だけを selective 暗号化、AGE/PGP/KMS 鍵で
   復号。複数の recipient 鍵を許容するので「PR 作成者は自分の鍵で署名、cluster
   は cluster 鍵で復号」みたいな multi-recipient フローが組める。

Phase 1.5 では以下を満たす必要がある:

- ArgoCD が cluster で sync 時に復号 → `Secret` を kube-API に提出
- 開発者 (1 人 = ryuzu) が `kubeseal` ないし `sops` 1 コマンドで暗号化
- git に push されたファイルからは値が **読めない** こと
- kind cluster (`make local-e2e` の dev 環境) で 1 コマンドセットアップが効くこと
- Phase 4 で multi-env (production cluster 追加) が来たときに鍵管理が破綻しないこと
  (Phase 1.5 の決定が Phase 4 で 0 から書き直しにならない)

## Decision

**Sealed-Secrets を採用** する。

- 開発者ワークフロー: `kubectl create secret ... --dry-run=client -o yaml | kubeseal -o yaml > sealed.yaml` →
  `private/secrets/<env>/<name>.yaml` に保存 → submodule commit & push。
- ArgoCD は `private/secrets/` を kustomize overlay として参照し、cluster の
  sealed-secrets controller が復号して plain `Secret` を `kube-system` 経由で
  各 namespace に materialize。
- master key は cluster scope。kind dev cluster と production cluster は別 key、
  sealed yaml は **対象 cluster でのみ復号可** (= production yaml を dev で展開
  しても復号できない、これが安全弁になる)。
- 鍵 backup の運用は `private/ops/sealed-secrets-key-backup.md` (作成は本 ADR
  と同セッションでは行わず、初回 production cluster 立ち上げ時に landing)。

実装スコープ (本 ADR で確定する範囲):

- `make local-e2e` script で kind cluster へ sealed-secrets controller を
  helm install (Worker δ delivery)
- `deploy/platform/` 配下に sealed-secrets controller の Application は **置かない**
  (Phase 1.5 PoC では kind に helm 直接 install で十分; Phase 4 で `deploy/platform/security/`
  サブツリーを作って ArgoCD 管理化)

## Consequences

### Positive

- **ArgoCD との親和性が最も高い**: cluster controller 方式は ArgoCD plugin を
  必要としない。sealed yaml は単なる kubernetes manifest なので ArgoCD の標準
  sync で復号反映までシームレス。SOPS は ArgoCD plugin (kustomize-sops + sops-plugin)
  を介する形で hop が増え、plugin の version 追従が運用コストになる。
- **`kubeseal` CLI の同期性**: 暗号化に必要なのは cluster 公開鍵のみ (`kubeseal --fetch-cert`
  で取得)。1-shot で off-by-one ミスが少ない。SOPS は AGE / PGP / KMS と鍵プロバイダ
  選択肢が多く、初期セットアップで決めることが多い。
- **値漏洩耐性**: SOPS の selective encryption は yaml の構造を保ったまま値のみ
  暗号化するので、key 名・comment ・コミット履歴から内容推測の余地が残る。
  Sealed-Secrets は payload 全体が opaque で、metadata 以外は git diff からも
  読めない。
- **single dev cluster の前提**: SOPS の multi-recipient (複数開発者鍵 + cluster
  鍵) は今 (= dev 1 人) overkill。Phase 4 で開発者が増えても sealed-secrets で
  困らない (再 seal は 1 コマンド、鍵 rotation 手順は Phase 4 で起草)。
- **drift 検出が自然**: ArgoCD が `SealedSecret` リソースの diff を即座に検出。
  SOPS は復号後の `Secret` diff を見るので「同じ平文の別暗号化」を区別できず
  常時 drift 扱いになるケースがある (ArgoCD plugin の ignoreDifferences で
  回避は可能だが運用が増える)。

### Negative / Trade-offs

- **cluster 鍵 backup が運命線**: master key (`tls.key` in `kube-system/sealed-secrets-key*`)
  を喪失すると過去の sealed yaml が全て復号不能 = production 鍵 backup の
  手順整備が **必須**。
- **再 seal が要る場面**: cluster の master key が rotate されたら、全 sealed
  yaml を再 seal する必要がある。Bitnami は key rotation を自動でやる
  (4 ヶ月ごとに新鍵生成、旧鍵で復号は引き続き可能) ので運用上は気にしなくて
  良いが、CI で再 seal 検証する frame を Phase 4 で入れる。
- **multi-cluster で同じ secret を共有する用途には弱い**: dev / prod で同じ
  Discord webhook を使う場合、両方の cluster 公開鍵で別々に seal する必要。
  SOPS の multi-recipient なら 1 ファイルで両 cluster 復号可。Phase 4 で
  multi-cluster が現実化したら再評価。
- **kubeseal CLI が必要**: 開発者ワークステーションに `kubeseal` インストールが
  必要 (Go binary 1 個なので軽い)。

### Mitigations

- **鍵 backup 手順**: Phase 4 production cluster 立ち上げ時に
  `private/ops/sealed-secrets-key-backup.md` を起草し、`kube-system/sealed-secrets-key*`
  の definite backup target (1Password vault / offline gpg-encrypted backup
  blob) を定義。
- **CI 検証**: Phase 4 で GitHub Actions に `kubeseal --validate < private/secrets/**.yaml`
  を実行する step を入れ、master key rotation のタイミングで再 seal 漏れを
  検出。
- **rotation 演習**: 半年に 1 回 `kubectl delete secret -n kube-system sealed-secrets-key*`
  で強制 rotation を実施、再 seal 手順が壊れていないことを定期確認。
- **multi-cluster 拡張**: Phase 4 で必要になったら ADR-0023-bis (Sealed-Secrets
  multi-cluster strategy) を起草、本 ADR は supersede ではなく延長扱い。

## Alternatives Considered

- **SOPS (+ ArgoCD plugin)**: multi-recipient と selective encryption は
  attractive だが、ArgoCD plugin 経由の hop が運用負荷。dev 1 人・single cluster
  の現状では over-engineering。Phase 4 で multi-cluster + multi-developer
  が定着した時点で再検討余地あり。
- **Vault (HashiCorp)**: external secret store と Vault Agent injector の構成。
  独立 stateful service の運用が重く、Phase 1.5 では完全に overkill。Phase 7
  で SaaS 化を本格化する場合に再検討。
- **External Secrets Operator + AWS Secrets Manager**: クラウドプロバイダ依存
  の secret 連携。Astera は今 self-hosted (Hetzner / 自宅) で動く前提なので
  AWS lock-in は避けたい。Phase 7 で multi-cloud 化したら ESO を adapter として
  検討。
- **平文 secret を private repo に直接 commit**: 暗号化なし。private repo の
  access control に依存。git history が漏れた瞬間に全 secret が漏洩する一方向
  ハザード。受け入れ難い。

## References

- 関連 ADR: [[adr/0022-multi-plugin-portable-architecture]] (ArgoCD app-of-apps
  構造の起点)
- 関連 docs: `deploy/apps/argocd-apps.yaml` (root Application), `private/`
  submodule (secret 実体の置き場)
- 外部リンク:
  - [Bitnami Sealed-Secrets](https://github.com/bitnami-labs/sealed-secrets)
  - [kubeseal CLI](https://github.com/bitnami-labs/sealed-secrets/releases)
  - [Mozilla SOPS](https://github.com/getsops/sops) (alternative — declined)
  - [ArgoCD + Sealed-Secrets pattern](https://argo-cd.readthedocs.io/en/stable/operator-manual/secret-management/)
