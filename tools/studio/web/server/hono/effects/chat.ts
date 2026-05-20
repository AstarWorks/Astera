/**
 * Claude chat proxy for the fxgraph editor.
 *
 *   POST /api/effects/chat
 *
 * Body:
 *   {
 *     user_input: string,
 *     graph: FxGraph,           // current state
 *     history: ChatTurn[],      // prior turns this session
 *     model: 'sonnet' | 'opus', // tier
 *   }
 *
 * Returns JSON (non-streaming for v1):
 *   {
 *     explanation: string,
 *     patch: Array<{op,path,value?,from?}> | null,    // null if AI asked a clarification
 *     question: string | null,                         // present if patch === null
 *     usage: { input, cache_read, cache_creation, output },
 *     cost_usd: number,
 *   }
 *
 * Caching strategy:
 *   - System prompt (built from schema) is sent with `cache_control: ephemeral`
 *     once per turn. Same prefix → cache hit on subsequent turns until 5-min TTL.
 *
 * Per ADR-0018: dev-only. ANTHROPIC_API_KEY is read from process env.
 */
import { Hono } from 'hono'
import Anthropic from '@anthropic-ai/sdk'
import { buildSystemPrompt } from '../../utils/fx-schema-prompt'

type ChatTurn = { role: 'user' | 'assistant'; content: string }
type ChatBody = {
  user_input: string
  graph: unknown
  history: ChatTurn[]
  model?: 'sonnet' | 'opus'
}

// 2026-05 published prices per million tokens (USD).
// Per latest Anthropic web docs: cache-write = 1.25× input, cache-read = 0.1× input.
// Update if Anthropic moves prices.
const PRICES = {
  sonnet: { input: 3.0, cache_read: 0.3, cache_creation: 3.75, output: 15.0 },
  opus: { input: 5.0, cache_read: 0.5, cache_creation: 6.25, output: 25.0 },
} as const

const MODEL_IDS = {
  sonnet: 'claude-sonnet-4-6',
  opus: 'claude-opus-4-7',
} as const

function computeCostUsd(
  tier: keyof typeof PRICES,
  usage: { input: number; cache_read: number; cache_creation: number; output: number },
): number {
  const p = PRICES[tier]
  return (
    (usage.input * p.input +
      usage.cache_read * p.cache_read +
      usage.cache_creation * p.cache_creation +
      usage.output * p.output) /
    1_000_000
  )
}

/** Parse the assistant's text block into {explanation, patch, question}. */
function parseAssistantText(text: string): {
  explanation: string
  patch: unknown[] | null
  question: string | null
} {
  // Find a fenced ```json block. The expected layout is `explanation\n```json\n[...]\n```` ...
  const fenceMatch = text.match(/```(?:json)?\s*([\s\S]*?)```/)
  if (!fenceMatch) {
    // No fence — treat as clarifying question or refusal.
    return { explanation: '', patch: null, question: text.trim() }
  }
  const explanation = text.slice(0, fenceMatch.index).trim()
  let parsed: unknown
  try {
    parsed = JSON.parse(fenceMatch[1].trim())
  } catch {
    return {
      explanation,
      patch: null,
      question: `(JSON parse failed in response)\n\n${text}`,
    }
  }
  if (!Array.isArray(parsed)) {
    return {
      explanation,
      patch: null,
      question: `(Expected array of patch ops, got ${typeof parsed})`,
    }
  }
  return { explanation, patch: parsed, question: null }
}

export const chat = new Hono()

chat.post('/', async (c) => {
  const apiKey = process.env.ANTHROPIC_API_KEY
  if (!apiKey) {
    return c.json(
      {
        error:
          'ANTHROPIC_API_KEY missing. Set it in your shell env before `bun run dev:api`.',
      },
      503,
    )
  }

  let body: ChatBody
  try {
    body = (await c.req.json()) as ChatBody
  } catch {
    return c.json({ error: 'invalid json' }, 400)
  }
  if (!body.user_input || typeof body.user_input !== 'string') {
    return c.json({ error: 'user_input required' }, 400)
  }
  if (!body.graph || typeof body.graph !== 'object') {
    return c.json({ error: 'graph required' }, 400)
  }
  const tier: keyof typeof PRICES = body.model === 'opus' ? 'opus' : 'sonnet'

  const client = new Anthropic({ apiKey })
  const system = buildSystemPrompt()

  // The user-content block embeds the current graph + the user's new
  // instruction. History is paired up as plain alternating turns. We don't
  // re-embed the graph in old turns (it would balloon input cost) — only
  // current state matters to Claude.
  type ContentBlock =
    | { type: 'text'; text: string; cache_control?: { type: 'ephemeral' } }

  type Msg = {
    role: 'user' | 'assistant'
    content: string | ContentBlock[]
  }

  const messages: Msg[] = []
  for (const turn of body.history ?? []) {
    if (!turn?.content) continue
    messages.push({ role: turn.role, content: turn.content })
  }

  // Current-turn user message: TWO content blocks — the graph state (cacheable,
  // invalidates when the graph changes), then the new instruction (varies every turn).
  messages.push({
    role: 'user',
    content: [
      {
        type: 'text',
        text: `Current fxgraph:\n\n\`\`\`json\n${JSON.stringify(body.graph)}\n\`\`\``,
        cache_control: { type: 'ephemeral' },
      },
      {
        type: 'text',
        text: `Request: ${body.user_input}`,
      },
    ],
  })

  try {
    const resp = await client.messages.create({
      model: MODEL_IDS[tier],
      max_tokens: 768,
      system: [
        {
          type: 'text',
          text: system,
          cache_control: { type: 'ephemeral' },
        },
      ],
      messages,
    })

    // Extract text content blocks (skip tool_use etc.).
    const text = resp.content
      .filter((b) => b.type === 'text')
      .map((b) => (b as { type: 'text'; text: string }).text)
      .join('\n')
      .trim()

    const parsed = parseAssistantText(text)

    const usage = {
      input: resp.usage.input_tokens,
      cache_read: resp.usage.cache_read_input_tokens ?? 0,
      cache_creation: resp.usage.cache_creation_input_tokens ?? 0,
      output: resp.usage.output_tokens,
    }
    const cost_usd = computeCostUsd(tier, usage)

    return c.json({
      raw: text,
      ...parsed,
      usage,
      cost_usd,
      model: MODEL_IDS[tier],
    })
  } catch (e) {
    return c.json({ error: `anthropic call failed: ${String(e)}` }, 500)
  }
})
