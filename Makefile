# Astera — local dev / k8s bootstrap helpers.
# `make help` lists everything.

SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

GRADLEW := ./gradlew
DOCKER_COMPOSE := docker compose -f docker/docker-compose.yml
DOCKER_COMPOSE_HOTSWAP := docker compose -f docker/docker-compose.yml -f docker/docker-compose.hotswap.yml
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

.PHONY: skill-frames
skill-frames: ## Render PNG frames + contact sheet + GIF for one skill: make skill-frames SKILL=simple-shoot
	@test -n "$(SKILL)" || { echo "usage: make skill-frames SKILL=<skill id>"; exit 2; }
	$(GRADLEW) :tools:skill-viewer:render -Pscenario=$(SKILL)

.PHONY: skill-frames-all
skill-frames-all: ## Contact sheets for every skill (tools/skill-viewer/build/skill-frames/<skill>/sheet.png)
	$(GRADLEW) :tools:skill-viewer:render

.PHONY: check
check: ## Run unit tests + Konsist architecture tests
	$(GRADLEW) check

.PHONY: clean
clean: ## Clean Gradle build outputs
	$(GRADLEW) clean

# ----------------------------------------------------------------------------
# local docker compose
# ----------------------------------------------------------------------------

.PHONY: docker-env
docker-env: ## Create docker/.env (from the example) and a Velocity forwarding secret if absent
	@test -f docker/.env || cp docker/.env.example docker/.env
	@grep -q '^VELOCITY_FORWARDING_SECRET=.' docker/.env || { \
		grep -v '^VELOCITY_FORWARDING_SECRET=' docker/.env > docker/.env.tmp; \
		echo "VELOCITY_FORWARDING_SECRET=$$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')" >> docker/.env.tmp; \
		mv docker/.env.tmp docker/.env; \
		echo "Generated a Velocity forwarding secret in docker/.env"; }

.PHONY: docker-up
docker-up: docker-env ## (Re)build image (jars + content baked in) and start Velocity + lobby + game + Postgres + Redis
	$(DOCKER_COMPOSE) up -d --build

.PHONY: dev-hotswap-up
dev-hotswap-up: docker-env ## DEV ONLY: (re)start the stack with the backends on JetBrains Runtime + hotswap agent (once; enables `make dev-hotswap`)
	$(DOCKER_COMPOSE) build lobby
	$(DOCKER_COMPOSE_HOTSWAP) up -d --build

.PHONY: dev-hotswap
dev-hotswap: ## Fast loop: build jars, apply the changed classes to the RUNNING backends (no restart; restarts only if the JVM refuses)
	COMPOSE_FILES="docker/docker-compose.yml docker/docker-compose.hotswap.yml" ./scripts/dev-deploy.sh hotswap

.PHONY: dev-plugins
dev-plugins: ## Fast loop: build jars, push changed ones into the running backends, restart only those (players are moved, not kicked)
	./scripts/dev-deploy.sh plugins

.PHONY: dev-content
dev-content: ## Fast loop: push content/ into the running backends, reload it in place and refresh held items (no restart)
	./scripts/dev-deploy.sh content

.PHONY: dev-pack
dev-pack: ## Fast loop: after `docker compose up -d --build resource-pack`, re-read the pack hash and re-push the pack to everybody online
	./scripts/dev-deploy.sh pack

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

# ----------------------------------------------------------------------------
# in-game E2E with protocol bots (QA-1, ADR-0222) - needs docker, not part of `check`
# ----------------------------------------------------------------------------

.PHONY: e2e
e2e: ## Boot the offline e2e backend, run the MCProtocolLib bot suite, write build/e2e-report.md (exit code = result)
	bash scripts/e2e.sh

.PHONY: e2e-down
e2e-down: ## Stop + drop THIS checkout's e2e backend (project astera-e2e-<id>, volumes, images)
	bash scripts/e2e.sh down

.PHONY: vfx-shot
vfx-shot: ## Film a skill with a real headless MC client: make vfx-shot SKILL=<skill-id> [VIEW=caster|opponent|side|all] [TICKS=60]
	@test -n "$(SKILL)" || { echo "usage: make vfx-shot SKILL=<skill-id> [VIEW=caster|opponent|side|all] [TICKS=60]"; exit 2; }
	SKILL="$(SKILL)" VIEW="$(or $(VIEW),all)" TICKS="$(or $(TICKS),60)" bash tools/vfx-shot/vfx-shot.sh

.PHONY: vfx-shot-down
vfx-shot-down: ## Stop + drop the vfx-shot server (this checkout + SKILL/CASE; only needed after SHOT_KEEP=1)
	bash tools/vfx-shot/vfx-shot.sh down

# ----------------------------------------------------------------------------
# bots inside the LIVE stack (QA-2, ADR-0221) - Velocity modern forwarding, needs the astera-dev stack
# ----------------------------------------------------------------------------

.PHONY: live-bots
live-bots: ## Run bot scenarios against the live stack: make live-bots SCENARIO=smoke|weapons|match|all (report: build/live-report.md)
	bash scripts/live-bots.sh $(or $(SCENARIO),all)

.PHONY: bots
bots: ## Fill the live queue with AI bots (ADR-0247): make bots N=2 LANE=norma  (starts the bot-runner service if needed)
	bash scripts/bots.sh $(or $(N),2) $(or $(LANE),norma)

.PHONY: skill-compare
skill-compare: ## Video vs intent vs Minecraft compare web app on 0.0.0.0:8097 (PORT=, HOST= to change)
	cd tools/skill-compare && bun install && bun server.mjs

.PHONY: skill-compare-fetch
skill-compare-fetch: ## Download a reference video + native-fps frames/waveform for its cases: make skill-compare-fetch VIDEO=<youtube id> [CASE=<id>]
	@test -n "$(VIDEO)" || { echo "usage: make skill-compare-fetch VIDEO=<youtube id> [CASE=<case id>]"; exit 2; }
	cd tools/skill-compare && bun install && bun scripts/prepare.mjs "$(VIDEO)" $(CASE)

# Reference videos are shared by every worktree (one download, atomic rename); frames/analysis stay per worktree.
export SKILL_COMPARE_VIDEO_DIR ?= $(HOME)/.cache/astera-skill-compare/videos

.PHONY: skill-compare-case
skill-compare-case: ## New case + native-fps frames: make skill-compare-case CASE=<id> VIDEO=<youtubeId> START=<s> END=<s> SKILL=<astera-skill-id> [NAME=..] [COINS=..] [IDENTITY=..] [WEAPON=<item-id>] [TITLE=<video title>]
	@test -n "$(CASE)" -a -n "$(VIDEO)" -a -n "$(START)" -a -n "$(END)" -a -n "$(SKILL)" || { echo "usage: make skill-compare-case CASE=<id> VIDEO=<youtubeId> START=<s> END=<s> SKILL=<astera-skill-id> [NAME=..] [COINS=..] [IDENTITY=..] [WEAPON=<item-id>] [TITLE=<video title>]"; exit 2; }
	cd tools/skill-compare && bun install && bun scripts/scaffold.mjs "$(CASE)" "$(VIDEO)" "$(START)" "$(END)" "$(SKILL)" "$(NAME)" "$(COINS)" "$(IDENTITY)" "$(WEAPON)" "$(TITLE)"

.PHONY: skill-pairs
skill-pairs: ## Video vs MC frame pairs at video times -> build/pairs/<case>.png: make skill-pairs CASE=<id> [SKILL=<skill>] AT="t1:label t2:label ..."
	@test -n "$(CASE)" -a -n "$(AT)" || { echo "usage: make skill-pairs CASE=<id> [SKILL=<skill>] AT=\"19.7:hit1 20.9:hit2\" (t = video seconds)"; exit 2; }
	python3 -I tools/skill-compare/scripts/pairs.py --case "$(CASE)" $(if $(SKILL),--skill "$(SKILL)") --out "build/pairs/$(CASE).png" $(AT)
