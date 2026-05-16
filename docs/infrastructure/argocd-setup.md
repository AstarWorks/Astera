# ArgoCD Setup

App-of-Apps パターンで Astera を完全に GitOps 化する。

## 初回セットアップ手順

### 1. クラスタ準備

- MicroK8s / k3s / managed k8s いずれか
- Phase 1 では MicroK8s 想定 ([[overview]] 参照)

### 2. ArgoCD インストール

```bash
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

### 3. ArgoCD CLI でログイン

```bash
kubectl port-forward svc/argocd-server -n argocd 8080:443
argocd login localhost:8080 --insecure
```

初回パスワード:
```bash
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d
```

### 4. Astera root app を apply

```bash
kubectl apply -f deploy/apps/argocd-apps.yaml
```

これで ArgoCD が `deploy/apps/` 配下の子 App (minecraft, platform) を自動 sync し始める。

## App-of-Apps 構造

```yaml
# deploy/apps/argocd-apps.yaml   (root)
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: astera-root
  namespace: argocd
spec:
  source:
    repoURL: https://github.com/AstarWorks/Astera.git
    targetRevision: main
    path: deploy/apps
  destination:
    server: https://kubernetes.default.svc
    namespace: argocd
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

子 App (`deploy/apps/minecraft/minecraft-app.yaml`, `deploy/apps/platform/platform-app.yaml`) が各 namespace の manifest を sync する。

## sync policy

すべての App で:

```yaml
syncPolicy:
  automated:
    prune: true       # git で消したら cluster からも消す
    selfHeal: true    # cluster で手動変更したら git に戻す
  retry:
    limit: 5
    backoff: { duration: 5s, factor: 2, maxDuration: 3m }
  syncOptions:
  - CreateNamespace=true
```

## 環境別 overlay の渡し方

production と staging を分けたい場合、Kustomize overlay を使う:

```yaml
spec:
  source:
    repoURL: https://github.com/AstarWorks/Astera.git
    path: deploy/private/overlays/production    # ← submodule 経由
```

`deploy/private/` は submodule なので、ArgoCD は submodule も clone する設定 ([repoConfig](https://argo-cd.readthedocs.io/en/stable/operator-manual/declarative-setup/#repository-credentials)) が必要。

## 動作確認

```bash
argocd app list
argocd app sync astera-root
argocd app get astera-minecraft
```

UI:
```bash
kubectl port-forward svc/argocd-server -n argocd 8080:443
# https://localhost:8080
```

## 参照

- Re-DIVERSE_infrastructure `apps/argocd-apps.yaml`
- [[overview]]
- [[adr/0006-gitops-with-argocd-kustomize]]
