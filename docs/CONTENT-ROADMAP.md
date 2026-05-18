# Astera Content Roadmap

**Speculative + post-launch content idea backlog**, distinct from
`RTM-PARITY-TRACKER.md`.

> **Last updated**: 2026-05-19 (initial seeding after Wave 5b close audit)

---

## §1 Why this doc

The `RTM-PARITY-TRACKER.md` does a single job well: track every concrete RTM
feature Astera intends to port, with `❌ / 🟨 / 🔌 / ✅` state + the wave
where it lands. It is a **1:1 mapping** between RTM source code / issues /
README and Astera implementation status.

What it deliberately doesn't capture:

- **Astera-original content ideas** that have no RTM counterpart (e.g.,
  "Quick spell assist macro" originated in RTM's `コンテンツ案一覧.md`
  post-launch wishlist — it's not implemented in RTM upstream either).
- **Speculative infrastructure / monetization items** whose suggested
  wave is "Phase 5+ research" — too far out for parity-tracker
  prioritization, but worth recording so they aren't re-discovered by
  every future contributor.
- **Sub-task variations** within an already-tracked feature (e.g.,
  parity tracker has "Cosmetics (skin / emote / BGM packs)" as one row;
  the user-facing **sound** vs **particle** vs **nickname** vs
  **color-code** variations are this doc's territory).

This file is the Astera equivalent of RTM's Writerside
`コンテンツ案一覧.md` (content idea list) — a *non-prioritized backlog* that
feeds the parity tracker once an item has a concrete wave commitment.

## §2 Content idea catalog (Phase 4+)

13 items grouped by category. Suggested-wave columns are aspirational —
items promote to `RTM-PARITY-TRACKER.md` when a real wave handoff doc
references them.

### Cosmetics (Wave 8e expansion)

| # | Item | Source | Suggested wave | Open questions |
|---|---|---|---|---|
| C-1 | **Sound effect cosmetics** — per-kill / respawn / victory / join audio overrides distinct from skill SFX | RTM issue #12 sub-item | Wave 8e | Player-overridable vs server-curated? Volume cap policy? |
| C-2 | **Particle cosmetics beyond standard** — kill / respawn / victory / join particle overlays distinct from skill VFX | RTM issue #12 sub-item | Wave 8e | Interacts with the Wave 5e particle-hide setting (Cat 8 tracker) — must defer to viewer's hide flag |
| C-3 | **Nickname custom display + chat color codes** — per-player display name override + color tag in chat | RTM issue #12 sub-items | Wave 8e | Moderation policy (forbidden words / impersonation) needed before launch |

### Match queue (Wave 7b extension + Phase 4+)

| # | Item | Source | Suggested wave | Open questions |
|---|---|---|---|---|
| Q-1 | **Private game mode** — exclusive match lobby for an invited player list | RTM issue #12 sub-item | Wave 8e or new Wave 7b extension | Friend list (Wave 5e) integration depth — does Private mode pull from Friendship domain? |
| Q-2 | **Ranked matchmaking** — ELO / TrueSkill-style queueing | Writerside `コンテンツ案一覧.md` | Wave 7b extension (Tournament + Leaderboard) | Algorithm choice (TrueSkill vs Glicko-2 vs simple ELO)? Season concept (Wave 8e) integration? |
| Q-3 | **Tournament automation** — bracket / round scheduling | Writerside `コンテンツ案一覧.md` | Wave 7b extension | Single-elimination vs round-robin vs swiss? Auto-rebracketing on no-show? |
| Q-4 | **Betting 1v1 mode (賭け1v1)** — currency-wagered duel | Writerside `コンテンツ案一覧.md` | Phase 4+ (Wave 8e+ post-economy) | JP regulatory (Wave 5f currency compliance) interaction — non-redeemable currency only? |

### Authoring UX (Wave 5f extension + Wave 10e integration)

| # | Item | Source | Suggested wave | Open questions |
|---|---|---|---|---|
| A-1 | **Quick spell assist macro (クイックスペルアシスト)** — author convenience: bind a sequence of skill casts to a single keypress | Writerside `コンテンツ案一覧.md` | Phase 5+ — depends on Wave 5e PersonalSettings QuickCast slot map | Macro language (CEL — Wave 5f) vs flat-list? Per-account vs per-server? |
| A-2 | **Skill tagging from CEL grammar (自作言語タグ付け)** — content tools auto-tag skills by parsing their CEL expressions | Writerside `コンテンツ案一覧.md` | Wave 5f (CEL) + Wave 10e (weapon-designer agent integration) | Tag taxonomy (mechanic / element / role)? Reverse-tagging when CEL changes? |

### Ops (Wave _ops extension)

| # | Item | Source | Suggested wave | Open questions |
|---|---|---|---|---|
| O-1 | **GitHub→Discord patch note auto-notification** — release-noter agent output (Wave 11a) auto-posted to `#announcements` Discord channel via webhook | Writerside `コンテンツ案一覧.md` | Wave _ops-8 (new sub-item) or Wave 11a follow-on | Webhook channel ownership (server admin Discord vs Astera team Discord)? Throttle rate? |

### Speculative infrastructure (Phase 5+ research)

| # | Item | Source | Suggested wave | Open questions |
|---|---|---|---|---|
| I-1 | **Vector search / embedding subscription** — LLM-powered content discovery service (player asks "show me skills like X" in natural language) | RTM issue #12 sub-item | Phase 5+ research item (no concrete wave yet) | Provider (OpenAI embeddings / Voyage / self-hosted Sentence-Transformers)? Cost ceiling? Privacy (no chat logs sent off-server)? |
| I-2 | **分散型ツリー型ホスティング** — distributed tree-based world hosting (peer-to-peer match servers in a hierarchical mesh, possibly to amortize cloud costs) | Writerside `コンテンツ案一覧.md` | Phase 5+ research item (no concrete wave yet) | Trust model — is the tree centrally-coordinated or DHT-style? Latency for cross-region matchmaking? Anti-cheat under reduced server trust? |

### Architectural decision pending

| # | Item | Source | Suggested wave | Open questions |
|---|---|---|---|---|
| D-1 | **Shop NPC implementation decision** — RTM issue #4 explicitly declined WeaponMechanics-NPC-as-vendor model and asked for an alternative (MythicMobs / Citizens / self-built). Astera's Wave 4.5 shipped GUI-only shop; if physical-world NPC vendors are wanted for Place / lobby UX, this needs an ADR. | RTM issue #4 body | Wave 5e or Phase 4 (Wave 8a admin tooling) | Does Place (Phase 5 Wave 9a) bring NPC vendors? Use Citizens as soft-dep with vanilla Villager fallback? |

## §3 Per-item depth

Each row above is a *seed*, not a spec. When an item is promoted to a
wave handoff doc, it gains:

1. A one-paragraph design sketch (~100 words)
2. Required domain types (sealed unions / value classes)
3. Persistence requirement (Postgres migration / JSONB column / ephemeral)
4. Integration touchpoints with existing Astera ports
5. Open question resolution (the column above closed out)

Until promoted, an item lives here as a one-liner with attribution.

## §4 Promotion criteria

An item promotes from this doc to `RTM-PARITY-TRACKER.md` (or a wave
handoff doc) when **any** of the following is true:

- A user (server operator / player community / content creator) explicitly
  asks for it with a deadline
- RTM upstream lands an implementation (we re-audit and absorb)
- A wave's scope review surfaces a dependency on it (e.g., Wave 8e
  expansion review identifies Sound cosmetics as P1)
- A research wave (Wave 10e weapon-designer / Wave 10d LLM translation /
  etc.) discovers it's a prerequisite

Demotion (item moves back to this doc) happens when an originally-promoted
item is determined out of scope for its assigned wave — record the
reasoning in the wave's handoff doc rather than silently re-shelving.

## §5 References

- [[architecture/rtm-divergence]] §"Add" table — Astera-original additions
- [[adr/0029-rtm-empirical-catalog]] §"Deep audit (2026-05-19)" §"Newly-discovered Phase 4+ backlog items"
- [[RTM-PARITY-TRACKER]] — 1:1 RTM port tracker (this doc's complement)
- [[IMPLEMENTATION-ROADMAP]] — 12-tier master roadmap; Phase 4+ waves (8a-f / 9a-c / 10a-f / 11a-d) are where most items here promote into
- RTM Writerside `コンテンツ案一覧.md`: https://github.com/Y-RyuZU/RyuZUTechnicalMagic/blob/master/docs/Writerside/topics/%E3%82%B3%E3%83%B3%E3%83%86%E3%83%B3%E3%83%84%E6%A1%88%E4%B8%80%E8%A6%A7.md
