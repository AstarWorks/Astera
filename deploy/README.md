# `deploy/` — Astera Kubernetes Manifests (ArgoCD + Kustomize)

GitOps source of truth for Astera's cluster. Modeled after the
`Re-DIVERSE/Re-DIVERSE_infrastructure` layout.

## Structure

```
deploy/
├── apps/                      ArgoCD Applications (App-of-Apps root)
│   ├── argocd-apps.yaml       Root: sync deploy/apps/*
│   ├── minecraft/             → deploy/minecraft (astera namespace)
│   └── platform/              → deploy/platform (databases / monitoring / ingress / jobs)
├── minecraft/                 Minecraft stack (namespace: astera)
│   ├── proxy/                 Velocity (itzg/mc-proxy:latest, NodePort 33411)
│   ├── lobby/                 Lobby server (ghcr.io/astarworks/astera:latest)
│   ├── game/                  Game server (Phase 1: static 1 replica; Phase 1.5 → KEDA)
│   └── jobs/                  RCON secret + daily restart CronJob (04:00 JST)
└── platform/                  Shared platform
    ├── databases/             PostgreSQL 17 + Redis 7 (namespace: databases)
    ├── monitoring/            Grafana 11 + provisioned dashboards (namespace: monitoring)
    ├── ingress/               Ingress for ArgoCD + Grafana (placeholder hosts)
    └── jobs/                  Daily playerdata snapshot CronJob
```

## Bootstrap

```bash
# 1. Install ArgoCD (see docs/infrastructure/argocd-setup.md)
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# 2. Apply the root App-of-Apps. ArgoCD picks up everything else.
kubectl apply -f deploy/apps/argocd-apps.yaml
```

## Image

The Minecraft Lobby and Game pods both run **`ghcr.io/astarworks/astera:latest`**,
which is built and published by the workflow in `.github/workflows/image.yml` (Step 5).
That image is `itzg/minecraft-server:java25` with the Astera Paper plugin jar baked in
(`docker/Dockerfile`). Paper version is pinned with `VERSION: "26.3"` (Paper
"year.drop.hotfix" format, MC Java 26.3 / JDK 25).

## Versions

| Component   | Image / Version                                     |
|-------------|-----------------------------------------------------|
| Paper       | `VERSION: "26.3"` (MC 26.3 / JDK 25)                |
| Velocity    | `itzg/mc-proxy:latest`                              |
| Astera plug | `ghcr.io/astarworks/astera:latest` (built Step 5)   |
| PostgreSQL  | `postgres:17-alpine`                                |
| Redis       | `redis:7-alpine`                                    |
| Grafana     | `grafana/grafana:11.3.0`                            |

## Secrets

All Secret manifests in this tree contain the literal value **`REPLACE_ME`**.
Real values live in `private/infra/overlays/<env>/` (a separate, private repo
attached as a git submodule under `private/`) as sealed-secrets or SOPS blobs.

The `AstarWorks/AsteraPrivate` submodule is mounted at `private/`. See
`.gitmodules`.

`astera-runtime` (`deploy/minecraft/astera-runtime-secret.yaml`) carries the
**Velocity forwarding secret** (Velocity pod: `VELOCITY_FORWARDING_SECRET`;
lobby / game pods: `CFG_VELOCITY_SECRET`, applied to Paper's
`proxies.velocity.secret` by the image's `/patches`) and the Postgres app
credentials. Use one value of `velocity-forwarding-secret` everywhere; rotate by
changing the secret and restarting all three pods.

## Lobby / game split (ADR-0179)

Players connect to Velocity only (NodePort 33411; `online-mode=true`). The lobby
and game pods run the same image with `ASTERA_SERVER_ROLE=lobby|game` and
`ASTERA_SERVER_NAME=lobby|game` (the names in `velocity-config`), reach Postgres and
Redis through `ASTERA_PG_*` / `ASTERA_REDIS_URI`, and accept only
forwarding-signed connections (`ONLINE_MODE=false` + Paper velocity forwarding).
The lobby queue hands formed matches to the game pod over Redis and moves players
with the BungeeCord `Connect` plugin message. The game Deployment stays a single
replica: it hosts one match at a time.

## Storage

Phase 1 uses **hostPath** PVs (`/srv/astera/...`) for the lobby world, game world,
shared data, Postgres data dir, Redis AOF, and the proxy config — same approach as
Re-DIVERSE, suitable for MicroK8s / k3s single-node. Managed-k8s deployments swap
these for `StorageClass`-backed PVCs via overlay.

## Scaling roadmap

Phase 1 ships `astera-game` as a **static 1-replica Deployment**. Phase 1.5 replaces
it with a KEDA `ScaledObject` driven by the matchmaking queue length in Redis. The
deployment manifest carries a `TODO(Phase 1.5)` marker.

## References

- `docs/infrastructure/overview.md`
- `docs/infrastructure/argocd-setup.md`
- `docs/adr/0006-gitops-with-argocd-kustomize.md`
- `docs/adr/0007-itzg-mc-server-base-image.md`
- Re-DIVERSE upstream layout: <https://github.com/Re-DIVERSE/Re-DIVERSE_infrastructure>
