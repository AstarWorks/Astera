# ADR-0187: Ordered server hand-off (token, transfer HUD, heartbeat)

Status: Accepted (F3-6). Builds on ADR-0179, ADR-0132 (its log-only stubs are removed).

## Decision
- Transfer request: "Moving to <server>" title + action bar (i18n), then the inventory is saved with a strictly
  increasing per-player version (epoch ms) and a `HandoffToken` goes out on `astera.handoff` BEFORE the proxy Connect.
  The following quit does not save again.
- Arrival: the player stays `Loading` until a token whose version is <= the stored snapshot arrives (max 40 ticks,
  poll every 2); then restore and publish `PlayerArrivedOnServer`. No token in time: restore latest, WARN.
  A single `ALL` server restores on join immediately. Match entry on the game server waits for the arrival event.
- Heartbeat every 5 s on `astera.heartbeat`; a peer is down after 15 s (`IServerLiveness`). The lobby holds the
  queue and tells players (`server-down`) instead of assigning to a down game server.
- Known cost: a direct (non-transfer) login on a lobby/game backend waits the 2 s bound before restoring.
