---
status: Accepted
date: 2026-10-28
deciders: Founder (chat-amp follow-up)
supersedes: null
superseded_by: null
---

# ADR-0294: Ampersand-only chat decoration controlled by rank

## Context

ADR-0289 mixed MiniMessage administrator templates/titles with ampersand player colours.
The founder requires one decoration notation and decoration eligibility per configured rank.
This decision replaces those portions of ADR-0289; its relay and Discord architecture remains.

## Decision

Chat format, rank prefix/suffix and `/astera chat title` use only legacy `&0-9a-f`,
`&k-o`, `&r` and `&#RRGGBB` decoration. MiniMessage is never decoded on these paths;
click/hover tags remain literal text. Templates use `{server}`, `{prefix}`, `{name}`,
`{suffix}`, `{message}`. Replace placeholders once with components, never reparse
inserted message text as a template. Equipped cosmetic titles are serialized to ampersand notation.
Discord's localized chat template uses the same notation and literal component insertion.

The sender's highest-priority matching rank supplies the required `decoration` boolean:
admin/mod true, member false in shipped content. Remove `astera.chat.color` and the
caller-provided colour override entirely, without compatibility decoding or permission fallback.

Japanize converts each word only if conversion leaves no ASCII letters. Preserve rejected
words, casing, spacing and punctuation. Protect ampersand codes from transliteration.
Append the original only if at least one word converted; existing URL/command/non-ASCII
bypasses and optional asynchronous kanji conversion remain. Google receives the Japanized
text, including preserved non-romaji words, when kanji conversion is enabled.

## Consequences

Existing MiniMessage chat configuration must be rewritten; no dual decoder is provided.
Member ampersand text stays visible. Unknown words such as `red` no longer become `れd`;
`konnnitiha red` becomes `こんにちは red (konnnitiha red)` before optional kanji conversion.
Integration coverage lives in CrossServerChatScenarioTest and ChatLiveTest; the manager
runs their scenarios in the integration window.

## Alternatives Considered

- Retaining both decoration syntaxes or the old permission: rejected by the pre-release policy.
- Parsing the final interpolated string: rejected because messages could become formatting/placeholders.

## References

- ADR-0289; ADR-0266; founder chat-amp follow-up (2026-10-28).
