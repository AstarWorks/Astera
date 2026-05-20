/**
 * Claude chat proxy for the fxgraph editor.
 *
 * Body:
 *   { user_input: string, graph: FxGraph, history: ChatTurn[], model: 'sonnet'|'opus' }
 *
 * Caching: system prompt (built from schema) is sent with
 * `cache_control: ephemeral` once per turn. Same prefix → cache hit on
 * subsequent turns until 5-min TTL.
 *
 * Per ADR-0018: dev-only. ANTHROPIC_API_KEY is read from process env.
 */
import Anthropic from '@anthropic-ai/sdk'
import { buildSystemPrompt } from '../../utils/effects/schema-prompt'
import {
  MODEL_IDS,
  computeCostUsd,
  parseAssistantText,
  type ChatBody,
  type ChatTier,
  type Msg,
} from '../../utils/effects/chat-lib'

export default defineEventHandler(async (event) => {
  const apiKey = process.env.ANTHROPIC_API_KEY
  if (!apiKey) {
    throw createError({
      statusCode: 503,
      statusMessage: 'ANTHROPIC_API_KEY missing. Set it in your shell env before `bun run dev`.',
    })
  }

  let body: ChatBody
  try {
    body = (await readBody(event)) as ChatBody
  } catch {
    throw createError({ statusCode: 400, statusMessage: 'invalid json' })
  }
  if (!body.user_input || typeof body.user_input !== 'string') {
    throw createError({ statusCode: 400, statusMessage: 'user_input required' })
  }
  if (!body.graph || typeof body.graph !== 'object') {
    throw createError({ statusCode: 400, statusMessage: 'graph required' })
  }
  const tier: ChatTier = body.model === 'opus' ? 'opus' : 'sonnet'

  const client = new Anthropic({ apiKey })
  const system = buildSystemPrompt()

  // The user-content block embeds the current graph + the user's new
  // instruction. History is paired up as plain alternating turns. We don't
  // re-embed the graph in old turns (it would balloon input cost) — only
  // current state matters to Claude.
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
    return {
      raw: text,
      ...parsed,
      usage,
      cost_usd: computeCostUsd(tier, usage),
      model: MODEL_IDS[tier],
    }
  } catch (e) {
    throw createError({ statusCode: 500, statusMessage: `anthropic call failed: ${String(e)}` })
  }
})
