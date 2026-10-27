---
status: Accepted
date: 2026-10-27
deciders: Founder (scope), Astera chat implementation track
supersedes: null
superseded_by: null
---

# ADR-0289: Dedicated cross-server chat plugin

## Context

Normal lobby/game chat needs one cross-server route, Japanese conversion, permission-controlled colours,
rank decoration and two-way Discord. Administrative screen titles use the same backend topology.
Command chat (`say`, `team`, `party`, `msg`) retains its existing Core use case and scope.

The brief predates ADR-0265: the formatter is now in `core/feature-social`, Redis and the messenger port
in `kernel/paper` and `kernel/runtime`, and channel names in `kernel/domain`. Core already publishes
`IProxyMessenger` through ServiceRegistry. ADR-0086's role model exists, including persisted role
assignments; it is unnecessary to introduce a separate rank permission or persistence model.
ADR-0245 is already Accepted for the world baseline. The old implementation roadmap is absent
(retired by ADR-0266); 0289 was the next unused number when this decision was written.

## Decision

Add `:plugin:chat`, Paper plugin `Astera-Chat`, depending only on the existing `Astera-Core` plugin name.
Remove Core's normal-chat formatter. A cancelled normal AsyncChatEvent is rendered once locally,
then published on `ProxyChannel.CHAT` through Core's existing Redis messenger. Subscribers ignore
frames originating on their own server. Title frames use `ProxyChannel.TITLE`, an `all` or exact
server-name target, and Adventure's 10/70/20 tick timings. No additional Redis clients are opened.
Redis pub/sub is best-effort: unavailable backends miss messages; there is no durable history.

`content/chat/chat.yaml` owns the trusted MiniMessage template and rank decorations. Player text and
names enter the template as components, never parsed MiniMessage. `astera.chat.color` enables only
legacy ampersand colours. Ranks use `IPermissionService.rolesOf`, highest configured priority first:
admin maps to `admin`/`astera:admin`, mod to `mod`/`moderator`/`moderator-lite`/`astera:gm`, and member
is the default (also covers player/vip). Operator status alone does not assign a rank. These are chat
ranks, separate from stats/cosmetic achievement titles. All backends must deploy the same content. The sender snapshots the existing cosmetic title and team/cosmetic name colour into the frame;
achievement/cosmetic storage and equipment flows stay unchanged.

An original, table-driven pure Kotlin converter handles romanisation, syllabic n and doubled
consonants. Conversion skips non-ASCII, `#` prefixes, commands and URL-like strings. Per-player
on/off preferences last for the connection and reset on quit/restart. When kanji is enabled,
Google Transliterate receives the **hiragana message text**, including any sensitive information a
player typed; usernames, UUIDs and the original romanised text are not included in that request.
This is an external privacy boundary: operators must disclose it to players or disable
`japanize.kanji`. The shipped configuration documents the boundary. A short asynchronous request
falls back to kana on timeout or malformed response; converted output includes the original in parentheses.
No API key or paid service is required. Google's endpoint has no availability contract for this use.

Use the JDK HttpClient/WebSocket implementation of Discord Gateway v10 (GUILD_MESSAGES and
MESSAGE_CONTENT intents), avoiding a shaded SDK. Enable the privileged Message Content intent in
the Discord developer portal. Only `discord.bridge-server` (default lobby) owns a bot; deployment
must keep that server name unique (lobby has one replica). Missing token/channel disables Discord
with one INFO line per backend. Optional secret keys `discord-bot-token` and `discord-channel-id`
in `astera-runtime` supply `ASTERA_DISCORD_BOT_TOKEN` and `ASTERA_DISCORD_CHANNEL_ID` to lobby.
Other backends relay MC text through Redis to that bridge. Bot/webhook messages are excluded from
inbound delivery to prevent loops. Discord inbound is plain text, bounded and stripped of controls.
The gateway handles fragmented messages, heartbeat ACKs, reconnect backoff, resume and invalid sessions.
Fatal authentication/intent closes stop reconnects. A bounded outbound FIFO honours HTTP 429 retry_after;
other HTTP failures are logged and dropped. Allowed mentions are disabled, preventing player-triggered pings.
Optional `ASTERA_CHAT_DISCORD_WEBHOOK_URL` supplies a separate chat webhook (username/player avatar);
without it, use bot REST messages with rank and sender in the plain body. Core's existing operator-event
webhook stays separate because it accepts domain events rather than player chat.

Network I/O is asynchronous. Permission snapshots and player delivery run on the player's entity
scheduler. Global Redis dispatch only schedules work or renders immutable components.
Plugin shutdown removes subscriptions/commands and stops HTTP, gateway and scheduled work.

## Observed documentation differences

- The brief's formatter summary omitted its current team/cosmetic name colour and equipped cosmetic-title rendering.
- `.claude/rules/coding-posture.md` still specifies Kotlin 2.2 / JVM 24; the actual catalog is Kotlin 2.3.21
  and Gradle enforces JVM 25. This implementation follows the build.
- The module-layout prose and README describe Persistence softdepending on all features; its actual plugin.yml
  depends only on Core and uses loadbefore for features. PluginLoadOrderTest enforces that implementation.
- The module-layout Core row and the external-plugin soft-dependency prose omit the actual hard dependency
  on packetevents. This change retains the existing Core declaration.
- The requested dependency spelling `AsteraCore` differs from the actual registered plugin `Astera-Core`.

## Consequences

- Chat remains optional and isolated from feature siblings; clean-layer and sibling rules cover it.
- Shipped YAML is validated by generated JSON Schema and the runtime loader in contentLint.
- Loss of Redis preserves local chat but cannot reach Discord from non-bridge servers.
- No Discord token is available for live verification; gateway authentication, reconnect and rate-limit
  behaviour require deployment verification. A bot needs View Channel, Send Messages and Message Content.
- Join/quit Discord lines and extra chat channels are not included. Command chat keeps its old delivery.

## Alternatives Considered

- A second Redis client: rejected because Core already exposes the exact required port.
- JDA: rejected because the limited channel-only bridge fits JDK networking without a large shaded dependency.
- Permission-only ranks: rejected because role storage already exists.
- Copying RyuZUPluginChat/LunaChat source: rejected; references define behaviour only.

## References

- ADR-0022, ADR-0086, ADR-0158, ADR-0179, ADR-0206, ADR-0262, ADR-0265, ADR-0266.
- https://github.com/AzisabaNetwork/RyuZUPluginChat (behaviour only)
- https://docs.discord.com/developers/events/gateway
- https://docs.discord.com/developers/resources/message#create-message
