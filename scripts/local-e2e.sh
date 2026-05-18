#!/usr/bin/env bash
# Astera local-e2e — Wave _ops smoke test.
#
# Boots a kind cluster, installs KEDA + ArgoCD, syncs the app-of-apps, then
# probes the Prometheus metrics surface for the Astera metric series. Designed
# to complete in <5 min on a warm cache; first run may take longer while Helm
# / ArgoCD images pull.
#
# Usage:
#     bash scripts/local-e2e.sh             # bring up + probe
#     bash scripts/local-e2e.sh --teardown  # delete the kind cluster and exit
#
# Env:
#     KIND_CLUSTER   cluster name (default: astera-e2e)
#     BUILD_IMAGE    future: build + `kind load` the astera:latest image
#                    (not implemented yet — see TODO below)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
KIND_CLUSTER="${KIND_CLUSTER:-astera-e2e}"
KIND_CONFIG="${SCRIPT_DIR}/../tools/local-e2e/kind-config.yaml"

PORT_FORWARD_PID=""
cleanup() {
  if [[ -n "${PORT_FORWARD_PID}" ]] && kill -0 "${PORT_FORWARD_PID}" 2>/dev/null; then
    kill "${PORT_FORWARD_PID}" 2>/dev/null || true
    wait "${PORT_FORWARD_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

banner() {
  echo
  echo "==> $*"
}

# ----------------------------------------------------------------------------
# --teardown short-circuit
# ----------------------------------------------------------------------------
if [[ "${1:-}" == "--teardown" ]]; then
  banner "Tearing down kind cluster: ${KIND_CLUSTER}"
  if kind get clusters 2>/dev/null | grep -qx "${KIND_CLUSTER}"; then
    kind delete cluster --name "${KIND_CLUSTER}"
    echo "    cluster deleted."
  else
    echo "    cluster ${KIND_CLUSTER} not present — nothing to do."
  fi
  exit 0
fi

# ----------------------------------------------------------------------------
# 1. Tool checks
# ----------------------------------------------------------------------------
banner "Checking required CLIs"
missing=()
for tool in kind kubectl helm; do
  if ! command -v "${tool}" >/dev/null 2>&1; then
    missing+=("${tool}")
  fi
done
if (( ${#missing[@]} > 0 )); then
  echo "ERROR: missing required CLI(s): ${missing[*]}" >&2
  echo "       install kind (https://kind.sigs.k8s.io/), kubectl, and helm before retrying." >&2
  exit 1
fi
echo "    kind:    $(kind version 2>/dev/null | head -n 1)"
echo "    kubectl: $(kubectl version --client -o yaml 2>/dev/null | awk '/gitVersion/ {print $2; exit}')"
echo "    helm:    $(helm version --short 2>/dev/null)"

# ----------------------------------------------------------------------------
# 2. Cluster create (idempotent)
# ----------------------------------------------------------------------------
banner "Ensuring kind cluster: ${KIND_CLUSTER}"
if kind get clusters 2>/dev/null | grep -qx "${KIND_CLUSTER}"; then
  echo "    cluster already exists — reusing."
else
  kind create cluster \
    --name "${KIND_CLUSTER}" \
    --config "${KIND_CONFIG}"
fi
kubectl cluster-info --context "kind-${KIND_CLUSTER}" >/dev/null

# TODO(BUILD_IMAGE): when BUILD_IMAGE=true, run `./gradlew shadowJar`, build
# the docker image, then `kind load docker-image astera:latest --name "${KIND_CLUSTER}"`
# so the deploy/minecraft/game deployment resolves locally instead of trying
# ghcr.io/astarworks/astera:latest.

# ----------------------------------------------------------------------------
# 3. KEDA operator
# ----------------------------------------------------------------------------
banner "Installing KEDA operator (namespace: keda)"
helm repo add kedacore https://kedacore.github.io/charts >/dev/null
helm repo update >/dev/null
helm upgrade --install keda kedacore/keda \
  --namespace keda \
  --create-namespace \
  --version 2.15.1 \
  --wait

# ----------------------------------------------------------------------------
# 4. ArgoCD install
# ----------------------------------------------------------------------------
banner "Installing ArgoCD via make argocd-install"
make -C "${REPO_ROOT}" argocd-install

# ----------------------------------------------------------------------------
# 5. Wait for argocd-server
# ----------------------------------------------------------------------------
banner "Waiting for argocd-server rollout (180s timeout)"
kubectl -n argocd rollout status deployment/argocd-server --timeout=180s

# ----------------------------------------------------------------------------
# 6. Bootstrap app-of-apps
# ----------------------------------------------------------------------------
banner "Applying Astera root ArgoCD Application via make argocd-bootstrap"
make -C "${REPO_ROOT}" argocd-bootstrap

# ----------------------------------------------------------------------------
# 7. Wait for game deployment (best-effort)
# ----------------------------------------------------------------------------
banner "Waiting for astera-game deployment (300s, best-effort)"
if ! kubectl -n astera wait \
     --for=condition=Available deployment/astera-game \
     --timeout=300s; then
  cat >&2 <<'WARN'
WARNING: astera-game deployment did not become Available within 300s.
         The deployment references ghcr.io/astarworks/astera:latest which is
         not present in the kind cluster. Build + `kind load` the image (or
         set BUILD_IMAGE=true once implemented) before expecting a green probe.
         Continuing with the smoke probe — Prometheus may report zero series.
WARN
fi

# ----------------------------------------------------------------------------
# 8. Smoke probe — Prometheus query
# ----------------------------------------------------------------------------
banner "Smoke-probing Prometheus for astera_match_active"
kubectl -n monitoring port-forward svc/prometheus 19090:9090 >/dev/null 2>&1 &
PORT_FORWARD_PID=$!
sleep 3

probe_response="$(curl -s --max-time 5 \
  'http://localhost:19090/api/v1/query?query=astera_match_active' \
  || true)"

if [[ -z "${probe_response}" ]]; then
  echo "ERROR: Prometheus did not respond on localhost:19090." >&2
  echo "       Check that the monitoring/prometheus Service exists." >&2
  exit 1
fi

if grep -q '"status":"success"' <<<"${probe_response}"; then
  echo "    Prometheus responded with status=success."
  echo "    payload: ${probe_response}"
else
  echo "ERROR: Prometheus response did not contain status=success." >&2
  echo "       payload: ${probe_response}" >&2
  exit 1
fi

# ----------------------------------------------------------------------------
# 9. Success summary
# ----------------------------------------------------------------------------
banner "Local e2e succeeded"
cat <<EOF
  context:     kind-${KIND_CLUSTER}
  namespaces:  astera, monitoring, keda, argocd

  Inspect with:
    kubectl -n astera get pods
    kubectl -n monitoring get pods
    kubectl -n keda get pods

  Tear down with:
    bash scripts/local-e2e.sh --teardown
EOF
