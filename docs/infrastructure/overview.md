# Infrastructure Overview

Astera の k8s 構成。Re-DIVERSE_infrastructure を base にした GitOps スタイル。

## クラスタ全体図

```
         ┌───────────────────────┐
         │  Internet / Players   │
         └──────────┬────────────┘
                    │
              ┌─────▼──────┐
              │  Ingress   │   (Velocity port 33411 公開)
              │  + DNS/TLS │
              └─────┬──────┘
                    │
   ┌────────────────▼─────────────────┐
   │      namespace: astera           │
   │  ┌──────────────────────────┐    │
   │  │  Velocity Proxy (1 Pod)  │    │
   │  └────────────┬─────────────┘    │
   │               │                  │
   │   ┌───────────┴────────────┐     │
   │   │                        │     │
   │  ┌▼─────────────────┐ ┌────▼───┐ │
   │  │ Lobby (1 Pod)    │ │ Game   │ │
   │  │ itzg/mc-server:  │ │ Pod    │ │
   │  │   java25         │ │ (P1.5  │ │
   │  └──────────────────┘ │  以降  │ │
   │                       │ KEDA で│ │
   │                       │ 動的)  │ │
   │                       └────────┘ │
   └──────────────────────────────────┘
   ┌────────────────────────────────────┐
   │   namespace: databases             │
   │  ┌────────────┐  ┌──────────────┐  │
   │  │ PostgreSQL │  │   Redis      │  │
   │  └────────────┘  └──────────────┘  │
   └────────────────────────────────────┘
   ┌────────────────────────────────────┐
   │   namespace: monitoring            │
   │  ┌─────────┐  ┌──────────┐         │
   │  │ Grafana │  │Prometheus│         │
   │  └─────────┘  └──────────┘         │
   └────────────────────────────────────┘
   ┌────────────────────────────────────┐
   │   namespace: argocd                │
   │   ArgoCD (App-of-Apps root)        │
   └────────────────────────────────────┘
```

## コンポーネント

| コンポーネント | image | port | リソース (req/limit) | 備考 |
|---|---|---|---|---|
| Velocity Proxy | `itzg/mc-proxy:latest` | 25577 | 2G/3G, 1.5/2c | Aikar flags |
| Lobby | `itzg/minecraft-server:java25` + Astera plugin | 31001 | 4G/6G, 1.2/1.5c | Paper 26.x |
| Game | `itzg/minecraft-server:java25` + Astera plugin | 31002 | 8G/12G, 1.5/2.5c | Phase 1 は 1 Pod 固定 |
| PostgreSQL | `postgres:17.5-alpine` | 5432 | 1G/2G | 永続データ |
| Redis | `redis:7-alpine` | 6379 | 256M/512M | キュー + Pub/Sub |
| Grafana | `grafana/grafana` | 3000 | 256M/512M | TPS / Player |
| ArgoCD | (公式) | 8080 | 1G | self-manage |

## GitOps の流れ

```
git push to Astera/deploy/
  ↓
ArgoCD が変更検知
  ↓
ArgoCD が自動 sync (automated.prune + selfHeal)
  ↓
クラスタが新状態に
```

詳細: [[argocd-setup]]

## 環境別 overlay

Kustomize の overlay で環境差を表現:

```
deploy/
├── (base manifest, public)
└── private/                  ← submodule
    └── overlays/
        ├── local/            (docker compose 用 ConfigMap 差分)
        ├── staging/
        └── production/
```

詳細: [[private-overlay]]

## 永続化

Phase 1 では **hostPath PV** (Re-DIVERSE 同等、MicroK8s 想定):
- `/srv/astera/proxy/` (Velocity 設定)
- `/srv/astera/lobby/` (Lobby world データ)
- `/srv/astera/game/` (Game world データ)
- `/srv/astera/shared/` (server 間共有)

将来 managed k8s では PVC + StorageClass に置換。

## 関連

- [[argocd-setup]]
- [[kustomize-conventions]]
- [[scaling]] (Phase 1.5 KEDA)
- [[secrets]]
- [[monitoring]]
- [[adr/0006-gitops-with-argocd-kustomize]]
- [[adr/0007-itzg-mc-server-base-image]]
