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
build: ## Build the Astera shadowJar
	$(GRADLEW) :plugin:platform-paper-plugin:shadowJar

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
docker-up: build ## Start local Paper + Postgres + Redis
	@test -f docker/.env || cp docker/.env.example docker/.env
	$(DOCKER_COMPOSE) up -d

.PHONY: docker-down
docker-down: ## Stop local stack (keeps volumes)
	$(DOCKER_COMPOSE) down

.PHONY: docker-nuke
docker-nuke: ## Stop local stack and DROP volumes
	$(DOCKER_COMPOSE) down -v

.PHONY: docker-logs
docker-logs: ## Tail Paper logs
	$(DOCKER_COMPOSE) logs -f paper

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

# ----------------------------------------------------------------------------
# submodule
# ----------------------------------------------------------------------------

.PHONY: private-update
private-update: ## Pull the latest commit from the private/ submodule
	git submodule update --remote private
