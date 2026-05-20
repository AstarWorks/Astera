/**
 * Pricing, model IDs, and assistant-response parsing for the Claude chat
 * proxy at /api/effects/chat. Prices per million tokens per Anthropic's
 * 2026-05 web docs (cache-write = 1.25× input, cache-read = 0.1× input).
 * Update if Anthropic moves prices.
 */
export const PRICES = {
  sonnet: { input: 3.0, cache_read: 0.3, cache_creation: 3.75, output: 15.0 },
  opus: { input: 5.0, cache_read: 0.5, cache_creation: 6.25, output: 25.0 },
} as const

export const MODEL_IDS = {
  sonnet: 'claude-sonnet-4-6',
  opus: 'claude-opus-4-7',
} as const

export type ChatTier = keyof typeof PRICES

export type ChatTurn = { role: 'user' | 'assistant'; content: string }

export type ChatBody = {
  user_input: string
  graph: unknown
  history: ChatTurn[]
  model?: ChatTier
}

export type ContentBlock = {
  type: 'text'
  text: string
  cache_control?: { type: 'ephemeral' }
}

export type Msg = {
  role: 'user' | 'assistant'
  content: string | ContentBlock[]
}

export function computeCostUsd(
  tier: ChatTier,
  usage: {
    input: number
    cache_read: number
    cache_creation: number
    output: number
  },
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
export function parseAssistantText(text: string): {
  explanation: string
  patch: unknown[] | null
  question: string | null
} {
  // Find a fenced ```json block. The expected layout is `explanation\n```json\n[...]\n```...
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
