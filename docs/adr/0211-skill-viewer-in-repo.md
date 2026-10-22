---
status: Accepted
date: 2026-10-22
deciders: founder + Claude
supersedes: null
superseded_by: null
---

# ADR-0211: Skill visual viewer lives in this repo (`tools/skill-viewer`)

## Status

Accepted. Narrows one point of ADR-0174 (viewer location); the trace schema is unchanged.

## Context

ADR-0174 planned a web viewer for `:tools:game-sim` traces in the sibling AsteraStudio repo. Nobody (human or
AI agent) could yet *see* a skill: particle animations (ADR-0196) and projectile trails are in the traces, but only as
numbers. Reviewing visuals needs a replay that an agent can also drive headlessly and read as a PNG.

## Decision

- The viewer is `tools/skill-viewer` in this repo, next to the traces and the simulator that produces them. A trace
  schema change and the viewer change then land in one commit and cannot drift; AsteraStudio stays a content
  authoring IDE.
- Static page, three.js pinned (`package.json`, exact version), bundled with Bun into `dist/`; opens from `file://`.
- Headless rendering: `playwright-core` (pinned) drives a local Chromium, writes frames, a 4x4 contact sheet and a GIF
  to `tools/skill-viewer/build/skill-frames/<skill>/`. Entry points: `./gradlew :tools:skill-viewer:render
  [-Pscenario=<skill>]`, `make skill-frames SKILL=<id>`, `make skill-frames-all`.
- `:tools:game-sim` gets one showcase scenario per file in `content/skills` (`SkillShowcaseScenarioTest`, dynamic tests,
  traces `showcase-<id>.json`) and a `showcase` task running only those.
- Rendering is not part of `check`; it needs bun and Chromium. Particles are approximations of vanilla (lifetimes,
  motion, colours), not pixel-exact; the viewer judges composition and timing, not final client art.

## Consequences

- A new dependency island (npm, locked in `bun.lock`) inside a Gradle repo; confined to `tools/skill-viewer`.
- Particle keys without a model fall back to a magenta square and a console warning, which flags content that
  needs a viewer entry.
