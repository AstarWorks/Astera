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
build: ## Build the 8 Astera plugin shadow jars (plugin/*/build/libs/astera-*.jar)
	$(GRADLEW) $(foreach p,core chat items combat match stats economy persistence,:plugin:$(p):shadowJar)

.PHONY: anim-import
anim-import: ## Import Blockbench player animations: make anim-import BB=path.bbmodel [OUT=content/animations]
	@test -n "$(BB)" || { echo "usage: make anim-import BB=<path.bbmodel> [OUT=content/animations]"; exit 2; }
	$(GRADLEW) :tools:anim-import:importAnimations -Pbb="$(BB)" -PanimOut="$(or $(OUT),content/animations)"

.PHONY: check
check: ## Run unit tests + Konsist architecture tests
	$(GRADLEW) check

.PHONY: clean
clean: ## Clean Gradle build outputs
	$(GRADLEW) clean

# ----------------------------------------------------------------------------
# local docker compose
# ----------------------------------------------------------------------------

# Remote dev stack: ASTERA_DEV_HOST=user@host drives the compose project on another machine over
# DOCKER_HOST=ssh://... (see docs/contributing/playtest.md "Running the dev stack on a separate host").
# Only the dev-stack targets get it, so `make e2e` and friends stay on the local daemon. Unset = local.
ifneq ($(ASTERA_DEV_HOST),)
DEV_STACK_TARGETS := docker-up dev-hotswap-up dev-hotswap dev-plugins dev-deploy dev-content dev-pack
$(DEV_STACK_TARGETS): export DOCKER_HOST := ssh://$(ASTERA_DEV_HOST)
$(DEV_STACK_TARGETS): export POSTGRES_BIND := $(or $(POSTGRES_BIND),127.0.0.1)
$(DEV_STACK_TARGETS): export REDIS_BIND := $(or $(REDIS_BIND),127.0.0.1)
# Always derived from ASTERA_DEV_HOST (a stale external ASTERA_PUBLIC_HOST / ASTERA_RESOURCE_PACK_URL must not win).
$(DEV_STACK_TARGETS): export ASTERA_PUBLIC_HOST := $(lastword $(subst @, ,$(ASTERA_DEV_HOST)))
DOCKER_COMPOSE += -f docker/docker-compose.remote.yml
DOCKER_COMPOSE_HOTSWAP += -f docker/docker-compose.remote.yml
endif

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

.PHONY: content-validate
content-validate: ## Decode every content/ file through the server's own loaders (compile+run only, no tests); fails naming file + field
	./gradlew -q :tools:content-lint:run

.PHONY: dev-deploy
dev-deploy: content-validate ## Fast loop: rebuild+push the resource pack, build jars + push content/ into the running backends, restart each ONCE (matching jar+content, arenas built in that boot; players are moved, not kicked)
	./scripts/dev-deploy.sh deploy

.PHONY: dev-content
dev-content: content-validate ## Fast loop: push content/ into the running backends, reload it in place and refresh held items (no restart)
	./scripts/dev-deploy.sh content

.PHONY: dev-pack
dev-pack: ## Fast loop: after `docker compose up -d --build resource-pack`, re-read the pack hash and re-push the pack to everybody online
	./scripts/dev-deploy.sh pack

.PHONY: dev-autodeploy-up dev-autodeploy-down dev-autodeploy-status
dev-autodeploy-up: ## Start the host-local origin/main watcher (survives reboot)
	./scripts/dev-autodeploy.sh up

dev-autodeploy-down: ## Pause auto-deploy after any active deployment finishes
	./scripts/dev-autodeploy.sh down

dev-autodeploy-status: ## Show watcher status and recent deploy events
	./scripts/dev-autodeploy.sh status

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
e2e: ## Integration-window e2e: skips the slowest low-churn suites (E2E_PROFILE=window), writes build/e2e-report.md (exit code = result)
	bash scripts/e2e.sh

.PHONY: e2e-smoke
e2e-smoke: ## Merge-gate e2e (E2E_PROFILE=smoke, < 10 min): boot, client packets, first ten minutes, lobby, shop, one objective, bot-vs-bot
	E2E_PROFILE=smoke bash scripts/e2e.sh build
	flock $${E2E_LOCK:-/tmp/claude-$$(id -u)/astera-e2e.lock} env E2E_PROFILE=smoke bash scripts/e2e.sh

.PHONY: e2e-build
e2e-build: ## Pre-build this checkout's e2e images without the host-wide lock (overlaps with whoever holds it)
	bash scripts/e2e.sh build

.PHONY: e2e-full
e2e-full: ## Full e2e: every suite incl. PersonaWeapons / Weapons (same as `make e2e E2E_PROFILE=full`)
	E2E_PROFILE=full bash scripts/e2e.sh

.PHONY: e2e-down
e2e-down: ## Stop + drop THIS checkout's e2e backend (project astera-e2e-<id>, volumes, images)
	bash scripts/e2e.sh down

.PHONY: live-view live-view-down
live-view: ## Live real Minecraft video + audio on 0.0.0.0:8098: make live-view SKILL=<id> [VIEW=caster|side|compare] [CASE=<id>] [PORT=8098]
	SKILL="$(SKILL)" VIEW="$(or $(VIEW),caster)" CASE="$(CASE)" PORT="$(or $(PORT),8098)" bash tools/live-view/live-view.sh

live-view-down: ## Stop this checkout's live viewer, clients, private audio daemon and capture stack
	bash tools/live-view/live-view.sh down

.PHONY: vfx-shot
vfx-shot: ## Film a skill with a real headless MC client (serialized host-wide, torn down on exit; SHOT_KEEP=1 keeps it warm for SHOT_KEEP_TTL): make vfx-shot SKILL=<skill-id> [VIEW=caster|opponent|side|compare|all] [CASE=<case>] [TICKS=60] [FOV=90] [BEAT_TICKS=case|0,8,16]
	@test -n "$(SKILL)" || { echo "usage: make vfx-shot SKILL=<skill-id> [VIEW=caster|opponent|side|compare|all] [CASE=<case>] [TICKS=60]"; exit 2; }
	SKILL="$(SKILL)" VIEW="$(or $(VIEW),all)" TICKS="$(TICKS)" DUMMIES="$(DUMMIES)" bash tools/vfx-shot/vfx-shot.sh

.PHONY: vfx-shot-up
vfx-shot-up: ## Create/refresh this checkout's kept capture stack and park the clients for SHOT_KEEP_TTL s (VIEW=compare CASE=<case> | side), no capture
	VIEW="$(or $(VIEW),all)" bash tools/vfx-shot/vfx-shot.sh up

.PHONY: vfx-shot-status
vfx-shot-status: ## Warm capture stack state: server, deployed content/pack/code vs working tree, parked clients, locks
	bash tools/vfx-shot/vfx-shot.sh status

.PHONY: anim-shot
anim-shot: ## Film a skin-rig animation for the skill-compare /anim review: make anim-shot ANIM=<animation-id | skill-id>
	@test -n "$(ANIM)" || { echo "usage: make anim-shot ANIM=<animation-id | skill-id>"; exit 2; }
	bash tools/vfx-shot/anim-shot.sh "$(ANIM)"

.PHONY: vfx-shot-down
vfx-shot-down: ## Stop the parked clients and drop this checkout's capture stack (server, pack host, volumes), kept or not
	bash tools/vfx-shot/vfx-shot.sh down

.PHONY: vfx-auto-up vfx-auto-down vfx-auto-status vfx-auto-once
vfx-auto-up: ## Capture farm: on every dev "deployed <sha>" film the changed skills (top+side, stepped) and publish to skill-compare as "auto <sha>"
	bash tools/vfx-shot/auto-capture.sh up
vfx-auto-down: ## Stop the capture farm daemon and drop its warm stack
	bash tools/vfx-shot/auto-capture.sh down
vfx-auto-status: ## Capture farm: daemon, last run, warm stack, log tail
	bash tools/vfx-shot/auto-capture.sh status
vfx-auto-once: ## Run the farm for one commit now: make vfx-auto-once SHA=<sha> [BASE=<sha>]
	@test -n "$(SHA)" || { echo "usage: make vfx-auto-once SHA=<sha> [BASE=<sha>]"; exit 2; }
	bash tools/vfx-shot/auto-capture.sh once "$(SHA)" "$(BASE)"

.PHONY: vfx-shot-reap
vfx-shot-reap: ## Kill vfx-shot clients / astera-shot-* stacks of dead runs, host-wide (live runs + kept stacks within TTL survive; SHOT_REAP_DRY=1 only lists)
	bash tools/vfx-shot/vfx-shot.sh reap

# ----------------------------------------------------------------------------
# bots inside the LIVE stack (QA-2, ADR-0221) - Velocity modern forwarding, needs the astera-dev stack
# ----------------------------------------------------------------------------

.PHONY: live-bots
live-bots: ## Run bot scenarios against the live stack: make live-bots SCENARIO=smoke|weapons|match|chat|all (report: build/live-report.md)
	bash scripts/live-bots.sh $(or $(SCENARIO),all)

.PHONY: bots
bots: ## Fill the live queue with AI bots (ADR-0247): make bots N=2 LANE=carry-tnt  (starts the bot-runner service if needed)
	bash scripts/bots.sh $(or $(N),2) $(or $(LANE),norma)

.PHONY: skill-compare
skill-compare: ## Video vs Minecraft (+ text beat sheet) compare web app on 0.0.0.0:8097 (PORT=, HOST= to change)
	cd tools/skill-compare && bun install && bun server.mjs

.PHONY: skill-compare-up skill-compare-down skill-compare-status
skill-compare-up: ## Serve skill-compare on :8097 and keep it in sync with dev: follows every "deployed <sha>" (own detached worktree), restarts the server when tools/skill-compare changed or it dies (SC_PORT=, SC_ROOT=, ...)
	bash scripts/skill-compare-follow.sh up
skill-compare-down: ## Stop the skill-compare follower and its server
	bash scripts/skill-compare-follow.sh down
skill-compare-status: ## skill-compare follower: server up?, serve sha vs dev sha, log tails
	bash scripts/skill-compare-follow.sh status

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

.PHONY: skill-brief
skill-brief: ## Browser-free AI brief: reference|in-game contact sheets + manifest.json -> build/brief/<case>/: make skill-brief CASE=<id> [SKILL=..] [VIEW=compare] [AT="t:label .."]
	@test -n "$(CASE)" || { echo "usage: make skill-brief CASE=<id> [SKILL=<skill>] [VIEW=compare|input|caster|opponent|side] [AT=\"19.7:extra\"]"; exit 2; }
	cd tools/skill-compare && bun install >/dev/null
	python3 -I tools/skill-compare/scripts/brief.py --case "$(CASE)" $(if $(SKILL),--skill "$(SKILL)") $(if $(VIEW),--view "$(VIEW)") $(if $(OUT),--out "$(OUT)") $(AT)

.PHONY: skill-pairs
skill-pairs: ## Video vs MC frame pairs at video times -> build/pairs/<case>.png: make skill-pairs CASE=<id> [SKILL=<skill>] AT="t1:label t2:label ..."
	@test -n "$(CASE)" -a -n "$(AT)" || { echo "usage: make skill-pairs CASE=<id> [SKILL=<skill>] AT=\"19.7:hit1 20.9:hit2\" (t = video seconds)"; exit 2; }
	python3 -I tools/skill-compare/scripts/pairs.py --case "$(CASE)" $(if $(SKILL),--skill "$(SKILL)") --out "build/pairs/$(CASE).png" $(AT)

.PHONY: skill-trace
skill-trace: ## Cast a v2 skill once in game-sim (real cast runner, dummy 6 blocks ahead) -> tools/game-sim/build/sim-traces/v2/<skill>.json (astera-sim-trace/2): make skill-trace SKILL=<skill-id>[,<skill-id>...]
	@test -n "$(SKILL)" || { echo "usage: make skill-trace SKILL=<skill-id>[,<skill-id>...]"; exit 2; }
	$(GRADLEW) :tools:game-sim:test --tests '*SkillTraceExportScenarioTest' -Pskill="$(SKILL)" --offline -q
	@for s in $$(echo "$(SKILL)" | tr ',' ' '); do python3 -I tools/vfx-shot/trace_v2.py "tools/game-sim/build/sim-traces/v2/$$s.json" && echo "trace ok: tools/game-sim/build/sim-traces/v2/$$s.json"; done

.PHONY: skill-preview
skill-preview: ## Cast a v2 skill in game-sim (skill-trace), render it from the skill-compare camera without the client and publish PREVIEW + PREVIEW-SIDE (+ the hit-volume OVERLAY render with OVERLAY=1) to skill-compare (PUBLISH=0 skips): make skill-preview SKILL=<skill-id> [TRACE=<astera-sim-trace/2 file>] [CASE=<case id>] [TICKS=0-53] [EVERY=4] [FOV=<deg>] [OVERLAY=1] [NOREF=1]
	@test -n "$(SKILL)" || { echo "usage: make skill-preview SKILL=<skill-id> [TRACE=f] [CASE=..] [TICKS=a-b] [EVERY=n] [FOV=deg] [OVERLAY=1] [NOREF=1]"; exit 2; }
	$(if $(TRACE),,$(MAKE) skill-trace SKILL="$(SKILL)")
	cd tools/skill-viewer && bun install --frozen-lockfile >/dev/null && bun run build >/dev/null && bun scripts/trace-preview.mjs --skill "$(SKILL)" $(if $(TRACE),--trace "$(abspath $(TRACE))") $(if $(CASE),--case "$(CASE)") $(if $(TICKS),--ticks "$(TICKS)") $(if $(EVERY),--every "$(EVERY)") $(if $(FOV),--fov "$(FOV)") $(if $(NOREF),--no-ref) $(if $(OVERLAY),--overlay) && bun scripts/trace-preview.mjs --skill "$(SKILL)" $(if $(TRACE),--trace "$(abspath $(TRACE))") --no-ref --camera side --out "$(CURDIR)/build/skill-preview/$(SKILL)/side"
	$(if $(filter 0,$(PUBLISH)),,node tools/skill-compare/scripts/publish-preview.mjs "$(SKILL)" && node tools/skill-compare/scripts/publish-preview.mjs "$(SKILL)" --src "build/skill-preview/$(SKILL)/side" --kind preview-side)

.PHONY: pose-solve
pose-solve: ## Reference keypoints -> skin-rig body keys + wrist keys in content/skill-lint/solved/ (never overwrites shipped keys): make pose-solve SKILL=<skill-id>
	@test -n "$(SKILL)" || { echo "usage: make pose-solve SKILL=<skill-id>"; exit 2; }
	python3 tools/pose-solve/solve.py "$(SKILL)"

.PHONY: skill-round
skill-round: ## Publish the director's round notes (tools/skill-compare/data/<case>/round.md) to skill-compare: make skill-round SKILL=<skill-id> [FILE=<round.md>]
	@test -n "$(SKILL)" || { echo "usage: make skill-round SKILL=<skill-id> [FILE=<round.md>]"; exit 2; }
	node tools/skill-compare/scripts/publish-round.mjs "$(SKILL)" $(FILE)

.PHONY: report-check
report-check: ## First line of every founder report: is <sha> deployed to dev? prints 見られる場所 (exit 0) or まだ見られるものはありません (exit 1): make report-check SHA=<sha>
	@bash tools/report-check.sh "$(SHA)"

.PHONY: arena-preview
arena-preview: ## Render every stage to build/arena-previews/ and measure generation/routing
	$(GRADLEW) :tools:arena-preview:run

.PHONY: rig-fade-shot
rig-fade-shot: ## Verify the skin-rig first-person fade (ADR-0285): first person vs F5 vs bystander: make rig-fade-shot [ANIM=<skill-id>]
	bash tools/vfx-shot/rig-fade-shot.sh "$(ANIM)"
