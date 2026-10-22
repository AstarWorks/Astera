# Astera — local dev / k8s bootstrap helpers.
# `make help` lists everything.

SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

GRADLEW := ./gradlew
DOCKER_COMPOSE := docker compose -f docker/docker-compose.yml
KIND_CLUSTER ?= astera
NS ?= astera
ARGOCD_NS ?= argocd

# ----------------------------------------------------------------------------
# meta
# ----------------------------------------------------------------------------

.PHONY: help
help: ## Show this help
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z0-9_-]+:.*?## / {printf "  %-22s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# ----------------------------------------------------------------------------
# build / test
# ----------------------------------------------------------------------------

.PHONY: build
build: ## Build the 7 Astera plugin shadow jars (plugin/*/build/libs/astera-*.jar)
	$(GRADLEW) $(foreach p,core items combat match stats economy persistence,:plugin:$(p):shadowJar)

.PHONY: check
check: ## Run unit tests + Konsist architecture tests
	$(GRADLEW) check

.PHONY: clean
clean: ## Clean Gradle build outputs
	$(GRADLEW) clean

# ----------------------------------------------------------------------------
# local docker compose
# ----------------------------------------------------------------------------

.PHONY: docker-up
docker-up: ## (Re)build image (jars + content baked in) and start Velocity + lobby + game + Postgres + Redis
	@test -f docker/.env || cp docker/.env.example docker/.env
	@grep -q '^VELOCITY_FORWARDING_SECRET=.' docker/.env || { \
		grep -v '^VELOCITY_FORWARDING_SECRET=' docker/.env > docker/.env.tmp; \
		echo "VELOCITY_FORWARDING_SECRET=$$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')" >> docker/.env.tmp; \
		mv docker/.env.tmp docker/.env; \
		echo "Generated a Velocity forwarding secret in docker/.env"; }
	$(DOCKER_COMPOSE) up -d --build

.PHONY: dev-plugins
dev-plugins: ## Fast loop: build jars, push changed ones into the running backends, restart only those (players are moved, not kicked)
	./scripts/dev-deploy.sh plugins

.PHONY: dev-content
dev-content: ## Fast loop: push content/ into the running backends and reload it in place (no restart)
	./scripts/dev-deploy.sh content

.PHONY: docker-down
docker-down: ## Stop local stack (keeps volumes)
	$(DOCKER_COMPOSE) down

.PHONY: docker-nuke
docker-nuke: ## Stop local stack and DROP volumes
	$(DOCKER_COMPOSE) down -v

.PHONY: docker-logs
docker-logs: ## Tail Velocity + lobby + game logs
	$(DOCKER_COMPOSE) logs -f velocity lobby game

# ----------------------------------------------------------------------------
# kind / k8s
# ----------------------------------------------------------------------------

.PHONY: kind-create
kind-create: ## Create a local kind cluster named $(KIND_CLUSTER)
	kind create cluster --name $(KIND_CLUSTER)

.PHONY: kind-delete
kind-delete: ## Delete the local kind cluster
	kind delete cluster --name $(KIND_CLUSTER)

.PHONY: argocd-install
argocd-install: ## Install ArgoCD in the current kubectl context
	kubectl create namespace $(ARGOCD_NS) --dry-run=client -o yaml | kubectl apply -f -
	kubectl apply -n $(ARGOCD_NS) -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

.PHONY: argocd-bootstrap
argocd-bootstrap: ## Apply the Astera root ArgoCD Application (app-of-apps)
	kubectl apply -f deploy/apps/argocd-apps.yaml

.PHONY: argocd-password
argocd-password: ## Print the initial ArgoCD admin password
	@kubectl -n $(ARGOCD_NS) get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo

.PHONY: argocd-port-forward
argocd-port-forward: ## Forward ArgoCD UI to localhost:8080
	kubectl port-forward -n $(ARGOCD_NS) svc/argocd-server 8080:443

.PHONY: status
status: ## Show pod/service state in the astera namespace
	@echo "--- pods ---"
	@kubectl get pods -n $(NS) 2>/dev/null || echo "(namespace $(NS) not present)"
	@echo "--- services ---"
	@kubectl get svc -n $(NS) 2>/dev/null || true

.PHONY: local-e2e
local-e2e: ## Boot kind, install KEDA + ArgoCD, sync apps, smoke probe (< 5 min after warm cache)
	bash scripts/local-e2e.sh

.PHONY: local-e2e-teardown
local-e2e-teardown: ## Tear down the local-e2e kind cluster
	bash scripts/local-e2e.sh --teardown

# ----------------------------------------------------------------------------
# Content authoring tools (Astera Studio + texture-gen + model-gen) live in
# their own repo: https://github.com/AstarWorks/AsteraStudio. Clone it as a
# sibling and run `bun run dev` there to author content into Astera/content/.
# ----------------------------------------------------------------------------

# ----------------------------------------------------------------------------
# submodule
# ----------------------------------------------------------------------------

.PHONY: private-update
private-update: ## Pull the latest commit from the private/ submodule
	git submodule update --remote private
